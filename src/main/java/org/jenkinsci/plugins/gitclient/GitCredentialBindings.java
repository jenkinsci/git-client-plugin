package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.git.GitTool;
import org.apache.commons.io.FilenameUtils;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public interface GitCredentialBindings {

    String GIT_TOOL_NAME = FilenameUtils.removeExtension(GitTool.getDefaultInstallation().getGitExe());

    void setKeyBindings(@NonNull StandardCredentials credentials);

    void setRunEnviornmentVariables(@NonNull FilePath filePath, @NonNull TaskListener listener) throws IOException, InterruptedException;

    GitClient getGitClientInstance(TaskListener listener) throws IOException, InterruptedException;

}
