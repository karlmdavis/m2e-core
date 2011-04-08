
package org.eclipse.m2e.jdt.apt;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.apt.core.util.AptConfig;
import org.eclipse.jdt.apt.core.util.IFactoryPath;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.MavenProjectChangedEvent;
import org.eclipse.m2e.core.project.configurator.AbstractProjectConfigurator;
import org.eclipse.m2e.core.project.configurator.ProjectConfigurationRequest;
import org.eclipse.m2e.jdt.IClasspathDescriptor;
import org.eclipse.m2e.jdt.IClasspathEntryDescriptor;
import org.eclipse.m2e.jdt.IJavaProjectConfigurator;


/**
 * <p>
 * This {@link AbstractProjectConfigurator} implementation will set the APT configuration for an Eclipse Java project.
 * </p>
 * <p>
 * Please note that the <code>maven-compiler-plugin</code> (at least as of version 2.3.2) will automatically perform
 * annotation processing and generate annotation sources. This processing will include all annotation processors in the
 * project's compilation classpath.
 * </p>
 * <p>
 * However, there are a couple of problems that prevent the <code>maven-compiler-plugin</code>'s annotation processing
 * from being sufficient when run within m2eclipse:
 * </p>
 * <ul>
 * <li>The generated annotation sources are not added to the Maven project's source folders (nor should they be) and are
 * thus not found by m2eclipse.</li>
 * <li>Due to contention between Eclipse's JDT compilation and <code>maven-compiler-plugin</code> compilation, the Java
 * compiler used by Eclipse may not recognize when the generated annotation sources/classes are out of date.</li>
 * </ul>
 * <p>
 * The {@link AptProjectConfigurator} works around those limitations by configuring Eclipse's built-in annotation
 * processing: APT. Unfortunately, the APT configuration will not allow for libraries, such as m2eclipse's
 * "Maven Dependencies" to be used in the search path for annotation processors. Instead, the
 * {@link AptProjectConfigurator} adds all of the project's <code>.jar</code> dependencies to the annotation processor
 * search path.
 * </p>
 */
public final class AptProjectConfigurator extends AbstractProjectConfigurator implements IJavaProjectConfigurator {
  /**
   * The <code>groupId</code> of the <a href="http://maven.apache.org/plugins/maven-compiler-plugin/">Maven Compiler
   * Plugin</a>.
   */
  private static final String COMPILER_PLUGIN_GROUP_ID = "org.apache.maven.plugins";

  /**
   * The <code>artifactId</code> of the <a href="http://maven.apache.org/plugins/maven-compiler-plugin/">Maven Compiler
   * Plugin</a>.
   */
  private static final String COMPILER_PLUGIN_ARTIFACT_ID = "maven-compiler-plugin";

  /**
   * The name of the <a href="http://maven.apache.org/plugins/maven-compiler-plugin/">Maven Compiler Plugin</a>'s
   * "compile" goal.
   */
  private static final String GOAL_COMPILE = "compile";

  /**
   * The {@link IMarker#setAttribute(String, Object)} key used for all {@link IMarker}s created by
   * {@link AptProjectConfigurator}, to identify the reason it was created.
   */
  private static final String MARKER_ATTRIB_TYPE = AptProjectConfigurator.class.getName() + ".type";

  /**
   * The {@link IMarker#setAttribute(String, Object)} value used to identify "update your project" markers.
   * 
   * @see #MARKER_ATTRIB_TYPE
   */
  private static final String MARKER_ATTRIB_TYPE_UPDATE = "updateProject";

  /**
   * {@inheritDoc}
   */
  @Override
  public void configure(ProjectConfigurationRequest request, IProgressMonitor monitor) throws CoreException {
    // This method may be called with null parameters to ensure its API is correct. We
    // can ignore such calls.
    if(request == null || monitor == null)
      return;

    // Clear any old "update your project" markers
    IResource pomResource = findPomResource(request.getMavenProjectFacade());
    clearUpdateWarnings(pomResource);

    // Get the objects needed for APT configuration
    IMavenProjectFacade mavenProjectFacade = request.getMavenProjectFacade();
    IProject eclipseProject = mavenProjectFacade.getProject();
    MavenSession mavenSession = request.getMavenSession();

    // Configure APT
    configureAptForProject(eclipseProject, mavenSession, mavenProjectFacade, monitor);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void mavenProjectChanged(MavenProjectChangedEvent event, IProgressMonitor monitor) throws CoreException {
    super.mavenProjectChanged(event, monitor);

    /*
     * I'm not sure we want to re-configure the Eclipse project every time the POM
     * changes-- might end up overwriting the user's manual changes to the Eclipse
     * project. If we do decide to do that, we'll need to create a new MavenSession,
     * as done in ProjectConfigurationManager.createMavenSession(...).
     */
    IResource pomResource = findPomResource(event.getMavenProject());
    applyUpdateWarning(pomResource);
  }

  /**
   * {@inheritDoc}
   */
  public void configureClasspath(IMavenProjectFacade facade, IClasspathDescriptor classpath, IProgressMonitor monitor) {
    /*
     * Implementations of this method are supposed to configure the Maven project
     * classpath: the "Maven Dependencies" container. We don't have any need to do
     * that here.
     */
  }

  /**
   * {@inheritDoc}
   */
  public void configureRawClasspath(ProjectConfigurationRequest request, IClasspathDescriptor classpath,
      IProgressMonitor monitor) throws CoreException {
    /*
     * We need to prevent/recover from the JavaProjectConfigurator removing the
     * generated annotation sources directory from the classpath: it will be added
     * when we configure the Eclipse APT preferences and then removed when the
     * JavaProjectConfigurator runs.
     */

    // Get the various project references we'll need
    IProject eclipseProject = request.getProject();
    MavenProject mavenProject = request.getMavenProject();
    IMavenProjectFacade projectFacade = request.getMavenProjectFacade();

    // Get the generated annotation sources directory as an IFolder
    File generatedSourcesDirectory = getGeneratedSourcesDirectory(request.getMavenSession(), projectFacade, monitor);
    File generatedSourcesRelativeDirectory = convertToProjectRelativePath(eclipseProject, generatedSourcesDirectory);
    String generatedSourcesRelativeDirectoryPath = generatedSourcesRelativeDirectory.getPath();
    IFolder generatedSourcesFolder = eclipseProject.getFolder(generatedSourcesRelativeDirectoryPath);

    // Get the output folder to use as an IPath
    File outputFile = new File(mavenProject.getBuild().getOutputDirectory());
    File outputRelativeFile = convertToProjectRelativePath(eclipseProject, outputFile);
    IFolder outputFolder = eclipseProject.getFolder(outputRelativeFile.getPath());
    IPath outputPath = outputFolder.getFullPath();

    // Create the includes & excludes specifiers
    IPath[] includes = new IPath[] {};
    IPath[] excludes = new IPath[] {};

    // If the source folder exists and is non-nested, add it
    // TODO test this
    if(generatedSourcesFolder != null && generatedSourcesFolder.exists()
        && generatedSourcesFolder.getProject().equals(eclipseProject)) {
      IClasspathEntryDescriptor cped = getEnclosingEntryDescriptor(classpath, generatedSourcesFolder.getFullPath());
      if(cped == null) {
        classpath.addSourceEntry(generatedSourcesFolder.getFullPath(), outputPath, includes, excludes, false);
      }
    } else {
      if(generatedSourcesFolder != null) {
        classpath.removeEntry(generatedSourcesFolder.getFullPath());
      }
    }
  }

  /**
   * TODO
   * 
   * @param classpath
   * @param fullPath
   * @return
   */
  private static IClasspathEntryDescriptor getEnclosingEntryDescriptor(IClasspathDescriptor classpath, IPath fullPath) {
    for(IClasspathEntryDescriptor cped : classpath.getEntryDescriptors()) {
      if(cped.getPath().isPrefixOf(fullPath)) {
        return cped;
      }
    }
    return null;
  }

  /**
   * Configures APT for the specified Maven project.
   * 
   * @param eclipseProject an {@link IProject} reference to the Eclipse project being configured
   * @param mavenProject {@link IMavenProjectFacade} reference to the Maven project being configured
   * @param monitor the {@link IProgressMonitor} to use
   * @throws CoreException Any {@link CoreException}s thrown will be passed through.
   */
  private void configureAptForProject(IProject eclipseProject, MavenSession mavenSession,
      IMavenProjectFacade mavenProjectFacade, IProgressMonitor monitor) throws CoreException {
    IJavaProject javaProject = JavaCore.create(eclipseProject);
    File generatedSourcesDirectory = getGeneratedSourcesDirectory(mavenSession, mavenProjectFacade, monitor);

    // If this isn't a Java project, we have nothing to do
    if(!eclipseProject.hasNature(JavaCore.NATURE_ID))
      return;

    // If this project has no valid compiler plugin config, we have nothing to do
    if(generatedSourcesDirectory == null)
      return;

    // Get the project's dependencies
    Set<Artifact> artifacts = mavenProjectFacade.getMavenProject().getArtifacts();

    // Enable APT
    AptConfig.setEnabled(javaProject, true);

    // Configure APT output path
    File generatedSourcesRelativeDirectory = convertToProjectRelativePath(eclipseProject, generatedSourcesDirectory);
    String generatedSourcesRelativeDirectoryPath = generatedSourcesRelativeDirectory.getPath();
    AptConfig.setGenSrcDir(javaProject, generatedSourcesRelativeDirectoryPath);

    // Add all of the artifacts to a new IFactoryPath (in addition to the
    // workspace's default entries)
    IFactoryPath factoryPath = AptConfig.getDefaultFactoryPath(javaProject);
    for(Artifact artifact : artifacts) {
      if(artifact.isResolved()) {
        File artifactJarFile = artifact.getFile();
        factoryPath.addExternalJar(artifactJarFile);
      }
    }

    // Apply that IFactoryPath to the project
    AptConfig.setFactoryPath(javaProject, factoryPath);
  }

  /**
   * Returns the <code>generatedSourcesDirectory</code> configuration parameter of the
   * {@link #COMPILER_PLUGIN_ARTIFACT_ID} plugin, or <code>null</code> if the {@link #GOAL_COMPILE} is not being
   * executed for this project.
   * 
   * @param mavenSession the {@link MavenSession} being used
   * @param mavenProjectFacade the {@link IMavenProjectFacade} of the project to get the
   *          <code>generatedSourcesDirectory</code> configuration parameter from
   * @param monitor the {@link IProgressMonitor} for this operation
   * @return the <code>generatedSourcesDirectory</code> configuration parameter of the
   *         {@link #COMPILER_PLUGIN_ARTIFACT_ID} plugin, or <code>null</code> if the {@link #GOAL_COMPILE} is not being
   *         executed for this project
   * @throws CoreException Any {@link CoreException}s encountered will be passed through.
   */
  private File getGeneratedSourcesDirectory(MavenSession mavenSession, IMavenProjectFacade mavenProjectFacade,
      IProgressMonitor monitor) throws CoreException {
    for(MojoExecution mojoExecution : mavenProjectFacade.getMojoExecutions(COMPILER_PLUGIN_GROUP_ID,
        COMPILER_PLUGIN_ARTIFACT_ID, monitor, GOAL_COMPILE)) {
      File generatedSourcesDirectory = maven.getMojoParameterValue(mavenSession, mojoExecution,
          "generatedSourcesDirectory", File.class);
      if(generatedSourcesDirectory != null)
        return generatedSourcesDirectory;
    }

    return null;
  }

  /**
   * Converts the specified relative or absolute {@link File} to a {@link File} that is relative to the base directory
   * of the specified {@link IProject}.
   * 
   * @param project the {@link IProject} whose base directory the returned {@link File} should be relative to
   * @param fileToConvert the relative or absolute {@link File} to convert
   * @return a {@link File} that is relative to the base directory of the specified {@link IProject}
   */
  private File convertToProjectRelativePath(IProject project, File fileToConvert) {
    // Get an absolute version of the specified file
    File absoluteFile = fileToConvert.getAbsoluteFile();
    String absoluteFilePath = absoluteFile.getAbsolutePath();

    // Get a File for the absolute path to the project's directory
    File projectBasedirFile = project.getLocation().toFile().getAbsoluteFile();
    String projectBasedirFilePath = projectBasedirFile.getAbsolutePath();

    // Compute the relative path
    if(absoluteFile.equals(projectBasedirFile)) {
      return new File(".");
    } else if(absoluteFilePath.startsWith(projectBasedirFilePath)) {
      String projectRelativePath = absoluteFilePath.substring(projectBasedirFilePath.length() + 1);
      return new File(projectRelativePath);
    } else {
      return absoluteFile;
    }
  }

  /**
   * Returns the {@link IResource} for the specified {@link IMavenProjectFacade}'s POM.
   * 
   * @param mavenProjectFacade the {@link IMavenProjectFacade} to get the POM for
   * @return the {@link IResource} for the specified {@link IMavenProjectFacade}'s POM
   */
  private static IResource findPomResource(IMavenProjectFacade mavenProjectFacade) {
    IFile pomFile = mavenProjectFacade.getPom();
    IResource pomResource = pomFile;
    return pomResource;
  }

  /**
   * Returns the update warning {@link IMarker}s attached to the specified {@link IResource}.
   * 
   * @param resource the {@link IResource} to find the update warning {@link IMarker}s of
   * @return the update warning {@link IMarker}s attached to the specified {@link IResource}
   * @throws CoreException Any {@link CoreException}s encountered will be passed through.
   */
  private static List<IMarker> findUpdateWarnings(IResource resource) throws CoreException {
    IMarker[] problemMarkers = resource.findMarkers(IMarker.PROBLEM, false, IResource.DEPTH_ZERO);
    List<IMarker> updateWarningMarkers = new ArrayList<IMarker>();
    for(IMarker problemMarker : problemMarkers)
      if(MARKER_ATTRIB_TYPE_UPDATE.equals(problemMarker.getAttribute(MARKER_ATTRIB_TYPE)))
        updateWarningMarkers.add(problemMarker);
    return updateWarningMarkers;
  }

  /**
   * Applies an update warning {@link IMarker} to the specified {@link IResource}. If one is already present, this
   * method does nothing.
   * 
   * @param resource the {@link IResource} to apply the update warning {@link IMarker} to
   * @throws CoreException Any {@link CoreException}s encountered will be passed through.
   */
  private static void applyUpdateWarning(IResource resource) throws CoreException {
    // No need to add the same marker twice to the same resource
    if(!findUpdateWarnings(resource).isEmpty())
      return;

    IMarker warningMarker = resource.createMarker(IMarker.PROBLEM);
    warningMarker.setAttribute(MARKER_ATTRIB_TYPE, MARKER_ATTRIB_TYPE_UPDATE);
    warningMarker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_WARNING);
    warningMarker.setAttribute(IMarker.MESSAGE,
        "The Maven POM has changed, but the Eclipse project's APT configuration was not updated."
            + " Run \"Maven > Update Project Configuration\" to resolve this.");
  }

  /**
   * Clears all update warning {@link IMarker}s attached to the specified {@link IResource}.
   * 
   * @param resource the {@link IResource} to clear update warning {@link IMarker}s from
   * @throws CoreException Any {@link CoreException}s encountered will be passed through.
   */
  private static void clearUpdateWarnings(IResource resource) throws CoreException {
    List<IMarker> warningMarkers = findUpdateWarnings(resource);
    for(IMarker warningMarker : warningMarkers)
      warningMarker.delete();
  }
}
