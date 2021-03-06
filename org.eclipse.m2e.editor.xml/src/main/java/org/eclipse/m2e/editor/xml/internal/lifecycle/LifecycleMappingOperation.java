/*******************************************************************************
 * Copyright (c) 2008-2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Sonatype, Inc. - initial API and implementation
 *******************************************************************************/

package org.eclipse.m2e.editor.xml.internal.lifecycle;

import static org.eclipse.m2e.core.ui.internal.editing.PomEdits.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import org.eclipse.m2e.core.internal.lifecyclemapping.LifecycleMappingFactory;
import org.eclipse.m2e.core.lifecyclemapping.model.PluginExecutionAction;
import org.eclipse.m2e.core.ui.internal.editing.PomEdits.Operation;
import org.eclipse.m2e.core.ui.internal.editing.PomHelper;

public class LifecycleMappingOperation implements Operation {
  

  private static final Logger log = LoggerFactory.getLogger(LifecycleMappingOperation.class);

  private static final String LIFECYCLE_PLUGIN_VERSION = LifecycleMappingFactory.LIFECYCLE_MAPPING_PLUGIN_VERSION;

  private static final String LIFECYCLE_PLUGIN_ARTIFACTID = LifecycleMappingFactory.LIFECYCLE_MAPPING_PLUGIN_ARTIFACTID;

  private static final String LIFECYCLE_PLUGIN_GROUPID = LifecycleMappingFactory.LIFECYCLE_MAPPING_PLUGIN_GROUPID;

  private String version;
  private String groupId;
  private String artifactId;

  private PluginExecutionAction action;
  private String[] goals;

  public LifecycleMappingOperation(String pluginGroupId, String pluginArtifactId, String pluginVersion,
      PluginExecutionAction action, String[] goals) {
    this.artifactId = pluginArtifactId;
    this.groupId = pluginGroupId;
    this.version = pluginVersion;
    assert !PluginExecutionAction.configurator.equals(action);
    this.action = action;
    this.goals = goals;
  }

  public void process(Document document) {
    Element root = document.getDocumentElement();
    Element managedPlugins = getChild(root, BUILD, PLUGIN_MANAGEMENT, PLUGINS);
    //now find the lifecycle stuff if it's there.
    Element lifecyclePlugin = findChild(managedPlugins, PLUGIN, 
        childEquals(GROUP_ID, LIFECYCLE_PLUGIN_GROUPID), 
        childEquals(ARTIFACT_ID, LIFECYCLE_PLUGIN_ARTIFACTID));
    if (lifecyclePlugin == null) {
      //not found, create
      lifecyclePlugin = PomHelper.createPlugin(managedPlugins, LIFECYCLE_PLUGIN_GROUPID, LIFECYCLE_PLUGIN_ARTIFACTID, LIFECYCLE_PLUGIN_VERSION);
      Comment comment = document.createComment("TODO TEXT. This plugin's configuration is used in m2e only.");
      managedPlugins.insertBefore(comment, lifecyclePlugin);
      format(comment);
    }
    
    Element pluginExecutions = getChild(lifecyclePlugin, CONFIGURATION, "lifecycleMappingMetadata", "pluginExecutions");
    //now find the plugin execution for the plugin we have..
    Element execution = null;
    for (Element exec : findChilds(pluginExecutions, "pluginExecution")) {
      Element filter = findChild(exec, "pluginExecutionFilter", 
          childEquals("groupId", groupId), 
          childEquals("artifactId", artifactId));
      //the action needs to match the action we want..
      Element actionEl = findChild(findChild(exec, "action"), action.toString());
      if (filter != null && actionEl != null) {
        String versionRange = getTextValue(getChild(filter, "versionRange"));
        if (versionRange != null) { //  paranoid null check
          //now we shall do some smart matching on the existing versionRange and our version..
          //so far the "smart" thing involves just overwriting the range.
          try {
            VersionRange range = VersionRange.createFromVersionSpec(versionRange);
            if (!range.containsVersion(new DefaultArtifactVersion(version))) {
              Element rangeEl = findChild(filter, "versionRange");
              setText(rangeEl, "[" + version + ",)");
            }
          } catch(InvalidVersionSpecificationException e) {
            log.error("Failed to parse version range:" + versionRange, e);
          }
        }
        execution = exec;
        break;
      }
    }
    if (execution == null) {
      execution = createPluginExecution(document, pluginExecutions);
    }
    //now enter/update the goal(s)..
    Element goalsEl = getChild(execution, "pluginExecutionFilter", "goals");
    List<String> toAddGoals = new ArrayList<String>(Arrays.asList(goals));
    for (Element existingGoal : findChilds(goalsEl, "goal")) {
      String glValue = getTextValue(existingGoal);
      if (glValue != null && toAddGoals.contains(glValue)) {
        toAddGoals.remove(glValue);
      }
    }
    if (toAddGoals.size() > 0) {
      for (String goal : toAddGoals) {
        format(createElementWithText(goalsEl, "goal", goal));
      }
    }
    
  }

  private Element createPluginExecution(Document document, Element parent) {
    Element exec = document.createElement("pluginExecution");
    parent.appendChild(exec);
    Element filter = document.createElement("pluginExecutionFilter");
    exec.appendChild(filter);
    createElementWithText(filter, "groupId", groupId);
    createElementWithText(filter, "artifactId", artifactId);
    createElementWithText(filter, "versionRange", "[" + version + ",)");
    
    Element actionEl = document.createElement("action");
    exec.appendChild(actionEl);
    Element actionEl2 = document.createElement(action.toString());
    actionEl.appendChild(actionEl2);
    if(PluginExecutionAction.execute.equals(action)) {
      actionEl2.appendChild(document.createComment("use <runOnIncremental>false</runOnIncremental>to only execute the mojo during full/clean build"));
    }
    
    format(exec);
    return exec;
  }

}
