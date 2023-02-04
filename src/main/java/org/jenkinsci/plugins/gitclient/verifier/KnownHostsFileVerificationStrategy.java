package org.jenkinsci.plugins.gitclient.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class KnownHostsFileVerificationStrategy extends SshHostKeyVerificationStrategy<KnownHostsFileVerifier> {

    @DataBoundConstructor
    public KnownHostsFileVerificationStrategy() {
        // stapler needs @DataBoundConstructor
    }

    @Override
    public KnownHostsFileVerifier getVerifier() {
        return new KnownHostsFileVerifier();
    }

    @Extension
    public static class KnownHostsFileVerificationStrategyDescriptor extends Descriptor<SshHostKeyVerificationStrategy<KnownHostsFileVerifier>> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Known hosts file";
        }

    }
}
