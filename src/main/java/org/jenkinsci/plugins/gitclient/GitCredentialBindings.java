package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.git.GitTool;
import jenkins.model.Jenkins;
import org.apache.commons.io.FilenameUtils;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public interface GitCredentialBindings {

    void setKeyBindings(@NonNull StandardCredentials credentials);

    void setRunEnviornmentVariables(@NonNull FilePath filePath, @NonNull TaskListener listener) throws IOException, InterruptedException;

    GitClient getGitClientInstance(TaskListener listener) throws IOException, InterruptedException;

    default String gitToolName(TaskListener listener) {
        String requiredToolByName = "Default";
        String actualToolByPath = null;

        GitTool gitTool = Jenkins.get().getDescriptorByType(GitTool.DescriptorImpl.class).getInstallation(requiredToolByName);
        if (gitTool == null) {
            listener.getLogger().println("Selected Git installation does not exist. Using Default");
            gitTool = GitTool.getDefaultInstallation();
        }
        if(gitTool!=null) {
            try {
                gitTool = gitTool.forNode(Jenkins.get(), listener);
                actualToolByPath = FilenameUtils.getBaseName(gitTool.getGitExe());
            } catch (IOException | InterruptedException e) {
                listener.getLogger().println("Failed to get git tool");
            }
        }

        return actualToolByPath.toLowerCase();
    }
}
