package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import java.io.Serial;
import java.util.logging.Logger;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;

/**
 * Disable all host key verification by the {@link SshHostKeyVerificationStrategy host key verification strategy extension point}.
 *
 * <p>Disables all verification of ssh host keys.
 * <strong>Not recommended</strong> because it provides no protection from "man-in-the-middle" attacks
 */
public class NoHostKeyVerifier extends HostKeyVerifierFactory {

    private static final Logger LOGGER = Logger.getLogger(NoHostKeyVerifier.class.getName());

    /**
     * Creates a secure shell host key verification strategy that performs no host key verification.
     * <strong>Not recommended</strong> because it provides no protection from "man-in-the-middle" attacks.
     */
    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> "-o StrictHostKeyChecking=no";
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {
        return new NoHostJGitKeyVerifier(listener, this);
    }

    public static class NoHostJGitKeyVerifier extends AbstractJGitHostKeyVerifier {

        /***
         * let's make spotbugs happy....
         */
        @Serial
        private static final long serialVersionUID = 1L;

        public NoHostJGitKeyVerifier(TaskListener listener, HostKeyVerifierFactory hostKeyVerifierFactory) {
            super(listener, hostKeyVerifierFactory);
        }

        @Override
        public ServerKeyDatabase.Configuration getServerKeyDatabaseConfiguration() {
            return new AbstractJGitHostKeyVerifier.DefaultConfiguration(
                    this.getHostKeyVerifierFactory(),
                    () -> ServerKeyDatabase.Configuration.StrictHostKeyChecking.ACCEPT_ANY);
        }
    }
}
