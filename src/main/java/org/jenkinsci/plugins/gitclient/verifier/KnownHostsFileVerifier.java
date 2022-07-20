package org.jenkinsci.plugins.gitclient.verifier;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.KnownHosts;
import hudson.model.TaskListener;

import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

public class KnownHostsFileVerifier extends HostKeyVerifierFactory {

    private static final Logger LOGGER = Logger.getLogger(KnownHostsFileVerifier.class.getName());

    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> {
            listener.getLogger().println("Verifying host key using known hosts file");
            return "-o StrictHostKeyChecking=yes";
        };
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {
        KnownHosts knownHosts;
        try {
            knownHosts = Files.exists(getKnownHostsFile().toPath()) ? new KnownHosts(getKnownHostsFile()) : new KnownHosts();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not load known hosts.");
            knownHosts = new KnownHosts();
        }
        return new KnownHostsFileJGitHostKeyVerifier(listener, knownHosts);
    }

    public class KnownHostsFileJGitHostKeyVerifier extends AbstractJGitHostKeyVerifier {

        private final TaskListener listener;

        public KnownHostsFileJGitHostKeyVerifier(TaskListener listener, KnownHosts knownHosts) {
            super(knownHosts);
            this.listener = listener;
        }

        @Override
        public String[] getServerHostKeyAlgorithms(Connection connection) throws IOException {
            return getPreferredServerHostkeyAlgorithmOrder(connection);
        }

        @Override
        public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
            listener.getLogger().printf("Verifying host key for %s using %s %n", hostname, getKnownHostsFile().toPath());
            return verifyServerHostKey(listener, getKnownHosts(), hostname, port, serverHostKeyAlgorithm, serverHostKey);
        }
    }

}
