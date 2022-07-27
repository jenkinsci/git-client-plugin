package org.jenkinsci.plugins.gitclient;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.PersistentDescriptor;
import jenkins.model.GlobalConfiguration;
import jenkins.model.GlobalConfigurationCategory;
import org.jenkinsci.plugins.gitclient.verifier.HostKeyVerifierFactory;
import org.jenkinsci.plugins.gitclient.verifier.AcceptFirstConnectionStrategy;
import org.jenkinsci.plugins.gitclient.verifier.SshHostKeyVerificationStrategy;

@Extension
public class GitHostKeyVerificationConfiguration extends GlobalConfiguration implements PersistentDescriptor {

    private SshHostKeyVerificationStrategy<? extends HostKeyVerifierFactory> sshHostKeyVerificationStrategy;

    @Override
    public @NonNull
    GlobalConfigurationCategory getCategory() {
        return GlobalConfigurationCategory.get(GlobalConfigurationCategory.Security.class);
    }

    public SshHostKeyVerificationStrategy<? extends HostKeyVerifierFactory> getSshHostKeyVerificationStrategy() {
        return sshHostKeyVerificationStrategy != null ? sshHostKeyVerificationStrategy : new AcceptFirstConnectionStrategy();
    }

    public void setSshHostKeyVerificationStrategy(SshHostKeyVerificationStrategy<? extends HostKeyVerifierFactory> sshHostKeyVerificationStrategy) {
        this.sshHostKeyVerificationStrategy = sshHostKeyVerificationStrategy;
        save();
    }

    public static @NonNull GitHostKeyVerificationConfiguration get() {
        return GlobalConfiguration.all().getInstance(GitHostKeyVerificationConfiguration.class);
    }
}
