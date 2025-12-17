package org.jenkinsci.plugins.gitclient;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Main;
import hudson.model.PersistentDescriptor;
import jenkins.model.GlobalConfiguration;
import jenkins.model.GlobalConfigurationCategory;
import org.jenkinsci.plugins.gitclient.verifier.HostKeyVerifierFactory;
import org.jenkinsci.plugins.gitclient.verifier.KnownHostsFileVerificationStrategy;
import org.jenkinsci.plugins.gitclient.verifier.NoHostKeyVerificationStrategy;
import org.jenkinsci.plugins.gitclient.verifier.SshHostKeyVerificationStrategy;

@Extension
public class GitHostKeyVerificationConfiguration extends GlobalConfiguration implements PersistentDescriptor {

    private SshHostKeyVerificationStrategy<? extends HostKeyVerifierFactory> sshHostKeyVerificationStrategy;

    private boolean sshVerbose = false;

    @Override
    public @NonNull GlobalConfigurationCategory getCategory() {
        return GlobalConfigurationCategory.get(GlobalConfigurationCategory.Security.class);
    }

    public SshHostKeyVerificationStrategy<? extends HostKeyVerifierFactory> getSshHostKeyVerificationStrategy() {
        return sshHostKeyVerificationStrategy != null ? sshHostKeyVerificationStrategy : getDefaultStrategy();
    }

    private SshHostKeyVerificationStrategy<?> getDefaultStrategy() {
        return Main.isUnitTest ? new NoHostKeyVerificationStrategy() : new KnownHostsFileVerificationStrategy();
    }

    public void setSshHostKeyVerificationStrategy(
            SshHostKeyVerificationStrategy<? extends HostKeyVerifierFactory> sshHostKeyVerificationStrategy) {
        this.sshHostKeyVerificationStrategy = sshHostKeyVerificationStrategy;
        save();
    }

    /**
     * Check if SSH verbose mode is enabled.
     * When enabled, SSH commands will include -vvv flag for detailed diagnostic output.
     * This helps troubleshoot SSH connection issues without requiring GIT_SSH_COMMAND environment variable.
     *
     * @return true if SSH verbose mode is enabled, false otherwise
     */
    public boolean isSshVerbose() {
        return sshVerbose;
    }

    /**
     * Set SSH verbose mode.
     * When enabled, SSH commands will include -vvv flag for detailed diagnostic output.
     * This helps troubleshoot SSH connection issues without requiring GIT_SSH_COMMAND environment variable.
     *
     * @param sshVerbose true to enable SSH verbose mode, false to disable
     */
    public void setSshVerbose(boolean sshVerbose) {
        this.sshVerbose = sshVerbose;
        save();
    }

    public static @NonNull GitHostKeyVerificationConfiguration get() {
        return GlobalConfiguration.all().getInstance(GitHostKeyVerificationConfiguration.class);
    }
}
