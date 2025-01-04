package org.jenkinsci.plugins.gitclient.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Accept known hosts strategy for the {@link SshHostKeyVerificationStrategy host key verification strategy extension point}.
 *
 * <p>Remembers the first host key encountered for each git server and requires that the same host key must be used for later access.
 * This is usually the most convenient setting for administrators while still providing ssh host key verification
 */
public class AcceptFirstConnectionStrategy extends SshHostKeyVerificationStrategy<AcceptFirstConnectionVerifier> {

    /**
     * Creates a secure shell host key verification strategy that accepts known hosts on first connection.
     * Remembers the first host key encountered for each git server and requires that the same host key must be used for later access.
     */
    @DataBoundConstructor
    public AcceptFirstConnectionStrategy() {
        // stapler needs @DataBoundConstructor
    }

    /** {@inheritDoc} */
    @Override
    public AcceptFirstConnectionVerifier getVerifier() {
        return new AcceptFirstConnectionVerifier();
    }

    @Extension
    public static class AcceptFirstConnectionStrategyDescriptor
            extends Descriptor<SshHostKeyVerificationStrategy<AcceptFirstConnectionVerifier>> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Accept first connection";
        }
    }
}
