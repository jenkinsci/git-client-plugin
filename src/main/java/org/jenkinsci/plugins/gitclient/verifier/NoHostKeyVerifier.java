package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import java.io.IOException;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;

public class NoHostKeyVerifier extends HostKeyVerifierFactory {

    private static final Logger LOGGER = Logger.getLogger(NoHostKeyVerifier.class.getName());

    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> "-o StrictHostKeyChecking=no";
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {
        return new AbstractJGitHostKeyVerifier((clientSession, socketAddress, publicKey) -> true) {

            @Override
            boolean verifyServerHostKey(
                    TaskListener taskListener,
                    ServerKeyVerifier serverKeyVerifier,
                    String hostname,
                    int port,
                    String serverHostKeyAlgorithm,
                    byte[] serverHostKey)
                    throws IOException {
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
