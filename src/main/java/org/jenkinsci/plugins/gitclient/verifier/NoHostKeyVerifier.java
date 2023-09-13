package org.jenkinsci.plugins.gitclient.verifier;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.KnownHosts;
import hudson.model.TaskListener;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NoHostKeyVerifier extends HostKeyVerifierFactory {

    private static final Logger LOGGER = Logger.getLogger(NoHostKeyVerifier.class.getName());

    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> "-o StrictHostKeyChecking=no";
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {
        return new AbstractJGitHostKeyVerifier(new KnownHosts()) {

            @Override
            public String[] getServerHostKeyAlgorithms(Connection connection) {
                return new String[0];
            }

            @Override
            public boolean verifyServerHostKey(
                    String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) {
                LOGGER.log(
                        Level.FINEST,
                        "No host key verifier, host {0}:{1} not verified with host key {2} {3}",
                        new Object[] {
                            hostname,
                            port,
                            serverHostKeyAlgorithm,
                            Base64.getEncoder().encodeToString(serverHostKey)
                        });
                return true;
            }
        };
    }
}
