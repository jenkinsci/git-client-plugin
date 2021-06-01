package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildWrapperDescriptor;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.credentialsbinding.BindingDescriptor;
import org.jenkinsci.plugins.credentialsbinding.MultiBinding;
import org.jenkinsci.plugins.credentialsbinding.impl.AbstractOnDiskBinding;
import org.jenkinsci.plugins.credentialsbinding.impl.SecretBuildWrapper;
import org.jenkinsci.plugins.credentialsbinding.impl.UnbindableDir;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;


public class GitUsernamePasswordBind extends MultiBinding<StandardUsernamePasswordCredentials> {
    private FilePath gitTempFile = null;

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
        Map<String, String> credMap = new LinkedHashMap();
        if (filePath != null) {
            final UnbindableDir unbindTempDir = UnbindableDir.create(filePath);
            GenerateGitScript gitScript = new GenerateGitScript(credentials.getUsername(),
                    credentials.getPassword().getPlainText(), credentials.getId());
            if (launcher != null && launcher.isUnix()) {
                //TODO use it in git 2.3 version
                //run.getEnvironment(taskListener).put("GIT_TERMINAL_PROMPT","false");
                gitTempFile = gitScript.write(credentials, unbindTempDir.getDirPath());
            }else{
                //windows impl
            }
            credMap.put("GIT_ASKPASS", gitTempFile.getRemote());
            return new MultiEnvironment(credMap, unbindTempDir.getUnbinder());
        } else {
            return new MultiEnvironment(credMap);
        }
    }

    @Override                                   //TODO
    public Set<String> variables() {
        return null;
    }

    protected static final class GenerateGitScript extends AbstractOnDiskBinding<StandardUsernamePasswordCredentials> {

        private String userVariable = "";
        private String passVariable = "";

        protected GenerateGitScript(String gitUsername, String gitPassword, String credentialId) {
            super(gitUsername + ":" + gitPassword, credentialId);
            this.userVariable = gitUsername;
            this.passVariable = gitPassword;
        }

        @Override
        protected FilePath write(StandardUsernamePasswordCredentials credentials, FilePath workspace)
                throws IOException, InterruptedException {
            final FilePath gitEcho = workspace.createTempFile("auth", ".sh");
            gitEcho.write("#!/bin/sh\n" +
                    "case $1 in\n" +
                    "        Username*) echo " + this.userVariable + "\n" +
                    "                ;;\n" +
                    "        Password*) echo " + this.passVariable + "\n" +
                    "                ;;\n" +
                    "        esac\n", null);
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