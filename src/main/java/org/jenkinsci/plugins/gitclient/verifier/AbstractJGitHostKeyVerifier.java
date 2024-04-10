package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

public abstract class AbstractJGitHostKeyVerifier implements SerializableOnlyOverRemoting {

    private static final Logger LOGGER = Logger.getLogger(AbstractJGitHostKeyVerifier.class.getName());

    private final transient ServerKeyVerifier serverKeyVerifier;

    protected AbstractJGitHostKeyVerifier(ServerKeyVerifier serverKeyVerifier) {
        this.serverKeyVerifier = serverKeyVerifier;
    }

    boolean verifyServerHostKey(
            TaskListener taskListener,
            ServerKeyVerifier serverKeyVerifier,
            String hostname,
            int port,
            String serverHostKeyAlgorithm,
            byte[] serverHostKey)
            throws IOException {
        String hostPort = hostname + ":" + port;
        try (SshClient sshClient = SshClient.setUpDefaultClient()) {
            ClientSession clientSession = sshClient.connect(hostPort).getClientSession();
            boolean isValid = serverKeyVerifier.verifyServerKey(clientSession, clientSession.getRemoteAddress(), null);
            if (!isValid) {
                LOGGER.log(Level.WARNING, "Host key {0} was not accepted.", hostPort);
                taskListener.getLogger().printf("Host key for host %s was not accepted.%n", hostPort);
            }
            return isValid;
        }
    }

    ServerKeyVerifier getServerKeyVerifier() {
        return serverKeyVerifier;
    }
}
