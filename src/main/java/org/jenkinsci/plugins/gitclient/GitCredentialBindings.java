package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.git.GitTool;
import org.apache.commons.io.FilenameUtils;

import javax.annotation.Nonnull;
import java.io.IOException;

public interface GitCredentialBindings {

    String GIT_TOOL_NAME = FilenameUtils.removeExtension(GitTool.getDefaultInstallation().getGitExe());

    void setKeyBindings(@Nonnull StandardCredentials credentials);

    void setRunEnviornmentVariables(@Nonnull FilePath filePath, @Nonnull TaskListener listener) throws IOException, InterruptedException;

    GitClient getGitClientInstance(TaskListener listener) throws IOException, InterruptedException;

}
