package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.credentialsbinding.BindingDescriptor;
import org.jenkinsci.plugins.credentialsbinding.MultiBinding;
import org.jenkinsci.plugins.credentialsbinding.impl.AbstractOnDiskBinding;
import org.jenkinsci.plugins.credentialsbinding.impl.UnbindableDir;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;


public class GitUsernamePasswordBind extends MultiBinding<StandardUsernamePasswordCredentials> implements GitCredentialBindings {
    final private String usernameKey =  "Git_Username";
    final private String passwordKey =  "Git_Password";
    private Map<String,String> credMap = new LinkedHashMap<>();

    @DataBoundConstructor
    public GitUsernamePasswordBind(String credentialsId) {
        super(credentialsId);
        //Variables could be added if needed
    }

    @Override
    protected Class<StandardUsernamePasswordCredentials> type() {
        return StandardUsernamePasswordCredentials.class;
    }

    @Override
    public MultiEnvironment bind(@Nonnull Run<?, ?> run, @Nullable FilePath filePath,
                                 @Nullable Launcher launcher, @Nonnull TaskListener taskListener)
            throws IOException, InterruptedException {
        StandardUsernamePasswordCredentials credentials = getCredentials(run);
        setKeyBindings(credMap,credentials);
        if (filePath != null && launcher != null) {
            final UnbindableDir unbindTempDir = UnbindableDir.create(filePath);
            setRunEnviornmentVariables(filePath,launcher,taskListener);
            GenerateGitScript gitScript = new GenerateGitScript(credentials.getUsername(),
                                                credentials.getPassword().getPlainText(), credentials.getId(),launcher);
            FilePath gitTempFile = gitScript.write(credentials, unbindTempDir.getDirPath());
            credMap.put("GIT_ASKPASS", gitTempFile.getRemote());
            credMap.put("GCM_INTERACTIVE", "false");
            return new MultiEnvironment(credMap, unbindTempDir.getUnbinder());
        } else {
            return new MultiEnvironment(credMap);
        }
    }

    @Override
    public Set<String> variables() {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(usernameKey);
        keys.add(passwordKey);
        return keys;
    }


    @Override
    public Map<String, String> setKeyBindings(Map<String, String> mapping, @Nonnull StandardCredentials credentials) {
        mapping.put(usernameKey,((StandardUsernamePasswordCredentials) credentials).getUsername());
        mapping.put(passwordKey,((StandardUsernamePasswordCredentials) credentials).getPassword().getPlainText());
        return mapping;
    }

    @Override
    public void setRunEnviornmentVariables(@Nonnull FilePath filePath, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        if(launcher.isUnix() && getCliGitAPIInstance("git",new File(filePath.toURI()),listener,new EnvVars()
                                    ).isAtLeastVersion(2,3,0,0))
        {
            credMap.put("GIT_TERMINAL_PROMPT","false");
        }else if(!launcher.isUnix()){
            credMap.put("GCM_INTERACTIVE","false");
        }
    }

    @Override
    public CliGitAPIImpl getCliGitAPIInstance(String gitExe, File workspace, TaskListener listener, EnvVars environment) {
        return new CliGitAPIImpl(gitExe,workspace,listener,environment);
    }

    protected static final class GenerateGitScript extends AbstractOnDiskBinding<StandardUsernamePasswordCredentials> {

        private String userVariable = null;
        private String passVariable = null;
        private Launcher launcher = null;

        protected GenerateGitScript(String gitUsername, String gitPassword, String credentialId, @Nonnull Launcher launcher) {
            super(gitUsername + ":" + gitPassword, credentialId);
            this.userVariable = gitUsername;
            this.passVariable = gitPassword;
            this.launcher = launcher;
        }
        @Override
        protected FilePath write(StandardUsernamePasswordCredentials credentials, FilePath workspace)
                throws IOException, InterruptedException {
            FilePath gitEcho;
            if (launcher.isUnix()) {
                gitEcho  = workspace.createTempFile("auth", ".sh");
                gitEcho.write("#!/bin/sh\n" +
                        "case $1 in\n" +
                        "        Username*) echo " + this.userVariable + "\n" +
                        "                ;;\n" +
                        "        Password*) echo " + this.passVariable + "\n" +
                        "                ;;\n" +
                        "        esac\n", null);
                gitEcho.chmod(0500);
            }else {
                gitEcho = workspace.createTempFile("auth",".bat");
                gitEcho.write("@ECHO OFF\n" +
                        "SET ARG=%~1\n" +
                        "IF %ARG:~0,8%==Username (ECHO "+this.userVariable+")\n" +
                        "IF %ARG:~0,8%==Password (ECHO "+this.passVariable+")",null);
                gitEcho.chmod(0500);
            }
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