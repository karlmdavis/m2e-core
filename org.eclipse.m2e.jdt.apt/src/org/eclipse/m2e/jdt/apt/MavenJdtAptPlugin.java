
package org.eclipse.m2e.jdt.apt;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import org.eclipse.m2e.core.project.configurator.AbstractProjectConfigurator;


/**
 * This is the {@link BundleActivator} for the Eclipse plugin providing the {@link AptProjectConfigurator}
 * {@link AbstractProjectConfigurator} implementation.
 */
public class MavenJdtAptPlugin implements BundleActivator {
  private static final String BUNDLE_ID_APT_CORE = "org.eclipse.jdt.apt.core";

  private static BundleContext context;

  static BundleContext getContext() {
    return context;
  }

  /**
   * Constructor.
   */
  public MavenJdtAptPlugin() {
  }

  /**
   * @see org.osgi.framework.BundleActivator#start(org.osgi.framework.BundleContext)
   */
  public void start(BundleContext bundleContext) throws Exception {
    MavenJdtAptPlugin.context = bundleContext;

    /* Ensure that APT is available. If not, bail out with an exception to prevent this 
     * bundle from being used.*/
    boolean aptBundleFound = false;
    for(Bundle bundle : bundleContext.getBundles()) {
      if(bundle.getSymbolicName().equals(BUNDLE_ID_APT_CORE))
        aptBundleFound = true;
    }
    if(!aptBundleFound)
      throw new IllegalStateException(String.format("Cannot start %s because %s is unavailable.", this.getClass()
          .getName(), BUNDLE_ID_APT_CORE));
  }

  /**
   * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
   */
  public void stop(BundleContext bundleContext) throws Exception {
    MavenJdtAptPlugin.context = null;
  }
}
