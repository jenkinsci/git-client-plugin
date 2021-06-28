package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.FilePath;

import hudson.Launcher;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitTool;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolLocationNodeProperty;
import jenkins.model.Jenkins;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.IOException;
import java.util.List;

public interface GitCredentialBindings {

    void setKeyBindings(@NonNull StandardCredentials credentials);

    void setRunEnvironmentVariables(@NonNull FilePath filePath, @NonNull TaskListener listener) throws IOException, InterruptedException;

    GitClient getGitClientInstance(TaskListener listener) throws IOException, InterruptedException;

    default boolean isCurrentNodeOSUnix(@NonNull Launcher launcher){
        return launcher.isUnix();
    }

    default String gitToolName(Run<?, ?> run, TaskListener listener) throws IOException, InterruptedException {

        Executor buildExecutor = run.getExecutor();
        ToolDescriptor<? extends GitTool> toolType;
        if (buildExecutor != null) {
            Node currentNode = buildExecutor.getOwner().getNode();
            //Check node is not null
            if (currentNode != null) {
                //It's on an agent type or Jenkins controller
                ToolLocationNodeProperty nodeToolLocation = currentNode.getNodeProperty(ToolLocationNodeProperty.class);
                //Tool location property is configured for the agent type or Jenkins controller
                if (nodeToolLocation != null) {
                    //Get Tool locations(could be many)
                    List<ToolLocationNodeProperty.ToolLocation> toolList = nodeToolLocation.getLocations();
                    //Iteration
                    for (ToolLocationNodeProperty.ToolLocation tool : toolList) {
                        //If 'Default' Git Tool is chosen on an agent then all the Git Tools installations added
                        // to the master controller will be available on the agent as well
                        //Check not an instance of jgit or jgitapache using tool descriptor not type key
                        if (tool.getType().clazz.equals(GitTool.class)) {
                            //Check for properties
                            GitTool gitTool = new GitTool(tool.getName(), tool.getHome(), null);
                            //node specific git home for git tool
                            gitTool = gitTool.forNode(currentNode, listener);
                            return gitTool.getGitExe();
                        }
                    }
                }
                //Check if Jenkins controller is using Global tools
                if (currentNode.getNodeName().equals("")) {
                    //It's on the Jenkins controller
                    toolType = Jenkins.get().getDescriptorByType(GitTool.DescriptorImpl.class);
                    if (toolType != null) {
                        ToolInstallation[] toolInstalled = toolType.getInstallations();
                        for (ToolInstallation t : toolInstalled) {
                            if (t.getClass().equals(GitTool.class)) {
                                return ((GitTool) t).getGitExe();
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
