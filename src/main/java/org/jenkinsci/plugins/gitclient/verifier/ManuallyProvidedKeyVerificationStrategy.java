package org.jenkinsci.plugins.gitclient.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.util.Objects;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Manually provided host key strategy for the {@link SshHostKeyVerificationStrategy host key verification strategy extension point}.
 *
 * <p>Provides a form field where the administrator inserts the host keys for the git repository servers.
 * This works well when a small set of repository servers meet the needs of most users
 */
public class ManuallyProvidedKeyVerificationStrategy
        extends SshHostKeyVerificationStrategy<ManuallyProvidedKeyVerifier> {

    private final String approvedHostKeys;

    /**
     * Creates a secure shell host key verification strategy that uses the host keys provided by the Jenkins administrator.
     * This works well when a small set of repository servers meet the needs of most users
     */
    @DataBoundConstructor
    public ManuallyProvidedKeyVerificationStrategy(String approvedHostKeys) {
        this.approvedHostKeys = approvedHostKeys.trim();
    }

    /** {@inheritDoc} */
    @Override
    public ManuallyProvidedKeyVerifier getVerifier() {
        return new ManuallyProvidedKeyVerifier(approvedHostKeys);
    }

    /**
     * Returns the approved host keys.
     * @return approved host keys
     */
    public String getApprovedHostKeys() {
        return approvedHostKeys;
    }

    @Extension
    public static class ManuallyTrustedKeyVerificationStrategyDescriptor
            extends Descriptor<SshHostKeyVerificationStrategy<ManuallyProvidedKeyVerifier>> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Manually provided keys";
        }

        public FormValidation doCheckApprovedHostKeys(@QueryParameter String approvedHostKeys) {
            return FormValidation.validateRequired(approvedHostKeys);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ManuallyProvidedKeyVerificationStrategy that = (ManuallyProvidedKeyVerificationStrategy) o;
        return Objects.equals(approvedHostKeys, that.approvedHostKeys);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hash(approvedHostKeys);
    }
}
