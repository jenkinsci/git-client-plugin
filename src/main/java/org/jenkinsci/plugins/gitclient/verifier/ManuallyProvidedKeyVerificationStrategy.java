package org.jenkinsci.plugins.gitclient.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.Objects;

public class ManuallyProvidedKeyVerificationStrategy extends SshHostKeyVerificationStrategy<ManuallyProvidedKeyVerifier> {

    private final String approvedHostKeys;

    @DataBoundConstructor
    public ManuallyProvidedKeyVerificationStrategy(String approvedHostKeys) {
        this.approvedHostKeys = approvedHostKeys.trim();
    }

    @Override
    public ManuallyProvidedKeyVerifier getVerifier() {
        return new ManuallyProvidedKeyVerifier(approvedHostKeys);
    }

    public String getApprovedHostKeys() {
        return approvedHostKeys;
    }

    @Extension
    public static class ManuallyTrustedKeyVerificationStrategyDescriptor extends Descriptor<SshHostKeyVerificationStrategy<ManuallyProvidedKeyVerifier>> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Manually provided keys";
        }

        public FormValidation doCheckApprovedHostKeys(@QueryParameter String approvedHostKeys) {
            return FormValidation.validateRequired(approvedHostKeys);
        }

    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ManuallyProvidedKeyVerificationStrategy that = (ManuallyProvidedKeyVerificationStrategy) o;
        return Objects.equals(approvedHostKeys, that.approvedHostKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(approvedHostKeys);
    }
}
