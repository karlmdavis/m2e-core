/*******************************************************************************
 * Copyright (c) 2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Sonatype, Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.m2e.internal.udc;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectChangedListener;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.MavenProjectChangedEvent;
import org.eclipse.m2e.core.project.configurator.MojoExecutionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Listens for Add project events and captures packaging and plugin types
 */
public class MavenListener implements IMavenProjectChangedListener {
    private static final Logger log = LoggerFactory.getLogger( MavenListener.class );

	private static final String SEPARATOR = ":"; //$NON-NLS-1$

	private static final String PLUGINS = "m2e.plugins"; //$NON-NLS-1$

	private static final String PACKAGING = "m2e.packaging"; //$NON-NLS-1$

	private static final String VERSION = "0.13.0"; //$NON-NLS-1$

	private static final String ID = "org.eclipse.m2e.core"; //$NON-NLS-1$

	private PomImportMonitor udcMonitor;

	MavenListener(PomImportMonitor udcMonitor) {
		this.udcMonitor = udcMonitor;
		MavenPlugin.getDefault().getMavenProjectManagerImpl().addMavenProjectChangedListener(this);
	}

	public void mavenProjectChanged(MavenProjectChangedEvent[] events, IProgressMonitor monitor) {
		SubMonitor subMon = SubMonitor.convert(monitor, 2 * events.length);
		try {
			for (MavenProjectChangedEvent event : events) {
				if (monitor.isCanceled()) {
					return;
				}
				if (event.getKind() != MavenProjectChangedEvent.KIND_ADDED) {
					continue;
				}
				IMavenProjectFacade facade = event.getMavenProject();
				udcMonitor.recordEvent(facade.getPackaging(), PACKAGING, facade.getPackaging(), ID, VERSION);
				subMon.worked(1);

				if (facade.getMojoExecutionMapping() != null) {
					for (MojoExecutionKey key : facade.getMojoExecutionMapping().keySet()) {
						udcMonitor.recordEvent(key.getGoal() + SEPARATOR + key.getExecutionId() + SEPARATOR + key.getLifecyclePhase(), PLUGINS, key.getGroupId() + SEPARATOR + key.getArtifactId()
								+ SEPARATOR + key.getVersion(), ID, VERSION);
					}
				}
				subMon.worked(1);

				if (subMon.isCanceled()) {
					return;
				}
			}
		} catch (Exception e) {
            log.error( e.getMessage(), e );
		} finally {
			subMon.done();
		}
	}

	void stopListener() {
		MavenPlugin.getDefault().getMavenProjectManagerImpl().removeMavenProjectChangedListener(this);
	}
}
