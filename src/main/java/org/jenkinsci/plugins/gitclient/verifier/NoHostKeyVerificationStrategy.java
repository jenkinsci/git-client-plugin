package org.jenkinsci.plugins.gitclient.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

@Extension
public class NoHostKeyVerificationStrategy extends SshHostKeyVerificationStrategy<NoHostKeyVerifier> {

    @DataBoundConstructor
    public NoHostKeyVerificationStrategy() {
        super();
    }

    @Override
    public NoHostKeyVerifier getVerifier() {
        return new NoHostKeyVerifier();
    }

    @Extension
    public static class NoHostKeyVerificationStrategyDescriptor
            extends Descriptor<SshHostKeyVerificationStrategy<NoHostKeyVerifier>> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "No verification";
        }
    }
}
