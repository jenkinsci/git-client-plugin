package org.jenkinsci.plugins.gitclient;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.Map;

public interface GitCredentialBindings {
    Map<String, String> setKeyBindings(Map<String, String> mapping, @Nonnull StandardCredentials credentials);

    void setRunEnviornmentVariables(@Nonnull FilePath filePath, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws IOException, InterruptedException;

    CliGitAPIImpl getCliGitAPIInstance(String gitExe, File workspace, TaskListener listener, EnvVars environment);
}
