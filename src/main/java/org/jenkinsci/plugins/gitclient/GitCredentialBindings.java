package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitTool;
import jenkins.model.Jenkins;
import org.apache.commons.io.FilenameUtils;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;

public interface GitCredentialBindings {

    void setKeyBindings(@Nonnull StandardCredentials credentials);

    void setRunEnviornmentVariables(@Nonnull FilePath filePath, @Nonnull TaskListener listener) throws IOException, InterruptedException, ClassNotFoundException;

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
