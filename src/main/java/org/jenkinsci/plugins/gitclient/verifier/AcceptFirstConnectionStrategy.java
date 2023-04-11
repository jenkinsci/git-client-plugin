package org.jenkinsci.plugins.gitclient.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class AcceptFirstConnectionStrategy  extends SshHostKeyVerificationStrategy<AcceptFirstConnectionVerifier> {

    @DataBoundConstructor
    public AcceptFirstConnectionStrategy() {
        // stapler needs @DataBoundConstructor
    }

    @Override
    public AcceptFirstConnectionVerifier getVerifier() {
        return new AcceptFirstConnectionVerifier();
    }

    @Extension
    public static class AcceptFirstConnectionStrategyDescriptor extends Descriptor<SshHostKeyVerificationStrategy<AcceptFirstConnectionVerifier>> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Accept first connection";
        }

    }
}
