package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import java.util.logging.Logger;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;

public class AcceptFirstConnectionVerifier extends HostKeyVerifierFactory {

    private static final Logger LOGGER = Logger.getLogger(AcceptFirstConnectionVerifier.class.getName());

    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> {
            listener.getLogger()
                    .println("Verifying host key using known hosts file, will automatically accept unseen keys");
            return "-o StrictHostKeyChecking=accept-new -o HashKnownHosts=yes";
        };
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {
        return new AcceptFirstConnectionJGitHostKeyVerifier(listener, this);
    }

    public static class AcceptFirstConnectionJGitHostKeyVerifier extends AbstractJGitHostKeyVerifier {

        public AcceptFirstConnectionJGitHostKeyVerifier(
                TaskListener listener, HostKeyVerifierFactory hostKeyVerifierFactory) {
            super(listener, hostKeyVerifierFactory);
        }

        @Override
        protected boolean askAboutKnowHostFile() {
            return false;
        }

        @Override
        public ServerKeyDatabase.Configuration getServerKeyDatabaseConfiguration() {
            return new AbstractJGitHostKeyVerifier.DefaultConfiguration(
                    this.getHostKeyVerifierFactory(),
                    () -> ServerKeyDatabase.Configuration.StrictHostKeyChecking.ACCEPT_NEW);
        }
    }
}
