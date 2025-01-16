package org.jenkinsci.plugins.gitclient.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Known hosts strategy for the {@link SshHostKeyVerificationStrategy host key verification strategy extension point}.
 *
 * <p>Uses the existing 'known_hosts' file on the controller and on the agent.
 * This assumes the administrator has already configured this file on the controller and on all agents
 */
public class KnownHostsFileVerificationStrategy extends SshHostKeyVerificationStrategy<KnownHostsFileVerifier> {

    /**
     * Creates a secure shell host key verification strategy that uses the existing 'known_hosts' file on the controller and on the agent.
     * This assumes the administrator has already configured this file on the controller and on all agents
     */
    @DataBoundConstructor
    public KnownHostsFileVerificationStrategy() {
        // stapler needs @DataBoundConstructor
    }

    /** {@inheritDoc} */
    @Override
    public KnownHostsFileVerifier getVerifier() {
        return new KnownHostsFileVerifier();
    }

    @Extension
    public static class KnownHostsFileVerificationStrategyDescriptor
            extends Descriptor<SshHostKeyVerificationStrategy<KnownHostsFileVerifier>> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Known hosts file";
        }
    }
}
