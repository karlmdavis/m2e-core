/*******************************************************************************
 * Copyright (c) 2008-2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Sonatype, Inc. - initial API and implementation
 *******************************************************************************/

package org.eclipse.m2e.core.ui.internal.wizards;

import java.io.File;
import java.lang.reflect.InvocationTargetException;

import org.apache.maven.model.Model;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.core.IMavenConstants;
import org.eclipse.m2e.core.embedder.MavenModelManager;
import org.eclipse.m2e.core.ui.internal.Messages;
import org.eclipse.osgi.util.NLS;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWizard;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * New POM wizard
 */
public class MavenPomWizard extends Wizard implements INewWizard {
  private static final Logger log = LoggerFactory.getLogger(MavenPomWizard.class);
  private MavenPomWizardPage artifactPage;

  private ISelection selection;

  /**
   * Constructor for MavenPomWizard.
   */
  public MavenPomWizard() {
    super();
    setNeedsProgressMonitor(true);
    setWindowTitle(Messages.MavenPomWizard_title);
  }

  /**
   * Adding the page to the wizard.
   */

  public void addPages() {
    artifactPage = new MavenPomWizardPage(selection);

    addPage(artifactPage);
  }

  /**
   * This method is called when 'Finish' button is pressed in the wizard. We will create an operation and run it using
   * wizard as execution context.
   */
  public boolean performFinish() {
    final String projectName = artifactPage.getProject();
    final Model model = artifactPage.getModel();

    IRunnableWithProgress op = new IRunnableWithProgress() {
      public void run(IProgressMonitor monitor) throws InvocationTargetException {
        monitor.beginTask(Messages.MavenPomWizard_task, 1);
        try {
          doFinish(projectName, model, monitor);
          monitor.worked(1);
        } catch(CoreException e) {
          throw new InvocationTargetException(e);
        } finally {
          monitor.done();
        }
      }
    };

    try {
      getContainer().run(true, false, op);
    } catch(InterruptedException e) {
      return false;
    } catch(InvocationTargetException e) {
      Throwable realException = e.getTargetException();
      MessageDialog.openError(getShell(), Messages.MavenPomWizard_error_title, realException.getMessage());
      return false;
    }
    return true;
  }

  /**
   * The worker method. It will find the container, create the file if missing or just replace its contents, and open
   * the editor on the newly created file.
   */
  void doFinish(String projectName, final Model model, IProgressMonitor monitor) throws CoreException {
    // monitor.beginTask("Creating " + fileName, 2);
    IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
    IResource resource = root.findMember(new Path(projectName));
    if(!resource.exists() || (resource.getType() & IResource.FOLDER | IResource.PROJECT) == 0) {
      // TODO show warning popup
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
          NLS.bind(Messages.MavenPomWizard_status_not_exists, projectName), null));
    }

    IContainer container = (IContainer) resource;
    final IFile file = container.getFile(new Path(IMavenConstants.POM_FILE_NAME));
    if(file.exists()) {
      // TODO show warning popup
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1, Messages.MavenPomWizard_error_exists, null));
    }

    final File pom = file.getLocation().toFile();

    try {
      MavenModelManager modelManager = MavenPlugin.getDefault().getMavenModelManager();
      modelManager.createMavenModel(file, model);

      getShell().getDisplay().asyncExec(new Runnable() {
        public void run() {
          IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
          try {
            IDE.openEditor(page, file, true);
          } catch(PartInitException e) {
          }
        }
      });

    } catch(Exception ex) {
      log.error("Unable to create POM " + pom + "; " + ex.getMessage(), ex);

    }
  }

  /**
   * We will accept the selection in the workbench to see if we can initialize from it.
   * 
   * @see IWorkbenchWizard#init(IWorkbench, IStructuredSelection)
   */
  public void init(IWorkbench workbench, IStructuredSelection selection) {
    this.selection = selection;
  }

}
