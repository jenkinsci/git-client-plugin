package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;

public interface GitCredentialBindings {

    void setKeyBindings(@Nonnull StandardCredentials credentials);

    void setRunEnviornmentVariables(@Nonnull FilePath filePath, @Nonnull TaskListener listener) throws IOException, InterruptedException;

    CliGitAPIImpl getCliGitAPIInstance(String gitExe, File workspace, TaskListener listener, EnvVars environment);

}
