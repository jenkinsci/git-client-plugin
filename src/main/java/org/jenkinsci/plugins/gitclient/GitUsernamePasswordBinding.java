package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;

import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.credentialsbinding.BindingDescriptor;
import org.jenkinsci.plugins.credentialsbinding.MultiBinding;
import org.jenkinsci.plugins.credentialsbinding.impl.AbstractOnDiskBinding;
import org.jenkinsci.plugins.credentialsbinding.impl.UnbindableDir;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;


public class GitUsernamePasswordBinding extends MultiBinding<StandardUsernamePasswordCredentials> implements GitCredentialBindings {
    final static private String GIT_USERNAME_KEY = "GIT_USERNAME";
    final static private String GIT_PASSWORD_KEY = "GIT_PASSWORD";
    private final Map<String, String> credMap = new LinkedHashMap<>();
    private String gitTool = null;

    @DataBoundConstructor
    public GitUsernamePasswordBinding(String credentialsId) {
        super(credentialsId);
        //Variables could be added if needed
    }

    @Override
    protected Class<StandardUsernamePasswordCredentials> type() {
        return StandardUsernamePasswordCredentials.class;
    }

    @Override
    public MultiEnvironment bind(@NonNull Run<?, ?> run, FilePath filePath,
                                 Launcher launcher,@NonNull TaskListener taskListener)
            throws IOException, InterruptedException {
        StandardUsernamePasswordCredentials credentials = getCredentials(run);
        setKeyBindings(credentials);
        gitTool = gitToolName(run, taskListener);
        if (gitTool !=null && filePath != null && launcher != null) {
            final UnbindableDir unbindTempDir = UnbindableDir.create(filePath);
            setRunEnviornmentVariables(filePath, taskListener);
            GenerateGitScript gitScript = new GenerateGitScript(credentials.getUsername(),
                    credentials.getPassword().getPlainText(), credentials.getId());
            FilePath gitTempFile = gitScript.write(credentials, unbindTempDir.getDirPath());
            credMap.put("GIT_ASKPASS", gitTempFile.getRemote());
            return new MultiEnvironment(credMap, unbindTempDir.getUnbinder());
        } else {
            return new MultiEnvironment(credMap);
        }
    }

    @Override
    public Set<String> variables() {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(GIT_USERNAME_KEY);
        keys.add(GIT_PASSWORD_KEY);
        return keys;
    }


    @Override
    public void setKeyBindings(@NonNull StandardCredentials credentials) {
        credMap.put(GIT_USERNAME_KEY, ((StandardUsernamePasswordCredentials) credentials).getUsername());
        credMap.put(GIT_PASSWORD_KEY, ((StandardUsernamePasswordCredentials) credentials).getPassword().getPlainText());
    }

    @Override
    public void setRunEnviornmentVariables(@NonNull FilePath filePath, @NonNull TaskListener listener) throws IOException, InterruptedException {
        if (!Functions.isWindows() && ((CliGitAPIImpl) getGitClientInstance(listener)).
                isAtLeastVersion(2, 3, 0, 0)) {
            credMap.put("GIT_TERMINAL_PROMPT", "false");
        } else {
            credMap.put("GCM_INTERACTIVE", "false");
        }
    }

    @Override
    public GitClient getGitClientInstance(TaskListener listener) throws IOException, InterruptedException {
        Git gitInstance = Git.with(listener, new EnvVars()).using(gitTool);
        return gitInstance.getClient();
    }

    protected static final class GenerateGitScript extends AbstractOnDiskBinding<StandardUsernamePasswordCredentials> {

        private String userVariable = null;
        private String passVariable = null;

        protected GenerateGitScript(String gitUsername, String gitPassword, String credentialId) {
            super(gitUsername + ":" + gitPassword, credentialId);
            this.userVariable = gitUsername;
            this.passVariable = gitPassword;
        }

        @Override
        protected FilePath write(StandardUsernamePasswordCredentials credentials, FilePath workspace)
                throws IOException, InterruptedException {
            FilePath gitEcho;
            if (!Functions.isWindows()) {
                gitEcho = workspace.createTempFile("auth", ".sh");
                gitEcho.write("#!/bin/sh\n" +
                        "case $1 in\n" +
                        "        Username*) echo " + this.userVariable + "\n" +
                        "                ;;\n" +
                        "        Password*) echo " + this.passVariable + "\n" +
                        "                ;;\n" +
                        "        esac\n", null);
            } else {
                gitEcho = workspace.createTempFile("auth", ".bat");
                gitEcho.write("@ECHO OFF\n" +
                        "SET ARG=%~1\n" +
                        "IF %ARG:~0,8%==Username (ECHO " + this.userVariable + ")\n" +
                        "IF %ARG:~0,8%==Password (ECHO " + this.passVariable + ")", null);
            }
            gitEcho.chmod(0500);
            return gitEcho;
        }

        @Override
        protected Class<StandardUsernamePasswordCredentials> type() {
            return StandardUsernamePasswordCredentials.class;
        }
    }

    @Symbol("GitUsernamePassword")
    @Extension
    public static final class DescriptorImpl extends BindingDescriptor<StandardUsernamePasswordCredentials> {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.GitUsernamePasswordBind_DisplayName();
        }

        @Override
        protected Class<StandardUsernamePasswordCredentials> type() {
            return StandardUsernamePasswordCredentials.class;
        }

        @Override
        public boolean requiresWorkspace() {
            return true;
        }
    }
}
