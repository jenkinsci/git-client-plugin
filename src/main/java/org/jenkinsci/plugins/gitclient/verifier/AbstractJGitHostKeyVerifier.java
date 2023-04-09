package org.jenkinsci.plugins.gitclient.verifier;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.KnownHosts;
import com.trilead.ssh2.ServerHostKeyVerifier;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

public abstract class AbstractJGitHostKeyVerifier implements ServerHostKeyVerifier, SerializableOnlyOverRemoting {

    private static final Logger LOGGER = Logger.getLogger(AbstractJGitHostKeyVerifier.class.getName());

    protected final transient KnownHosts knownHosts;

    protected AbstractJGitHostKeyVerifier(KnownHosts knownHosts) {
        this.knownHosts = knownHosts;
    }

    public abstract String[] getServerHostKeyAlgorithms(Connection connection) throws IOException;

    /**
     * Defines host key algorithms which is used for a Connection while establishing an encrypted TCP/IP connection to a SSH-2 server.
     * @param connection
     * @return array of algorithms for a connection
     * @throws IOException
     */
    String[] getPreferredServerHostkeyAlgorithmOrder(Connection connection) {
        String[] preferredServerHostkeyAlgorithmOrder =
                knownHosts.getPreferredServerHostkeyAlgorithmOrder(connection.getHostname());
        if (preferredServerHostkeyAlgorithmOrder == null) {
            return knownHosts.getPreferredServerHostkeyAlgorithmOrder(
                    connection.getHostname() + ":" + connection.getPort());
        }
        return preferredServerHostkeyAlgorithmOrder;
    }

    boolean verifyServerHostKey(
            TaskListener taskListener,
            KnownHosts knownHosts,
            String hostname,
            int port,
            String serverHostKeyAlgorithm,
            byte[] serverHostKey)
            throws IOException {
        String hostPort = hostname + ":" + port;
        int resultHost = knownHosts.verifyHostkey(hostname, serverHostKeyAlgorithm, serverHostKey);
        int resultHostPort = knownHosts.verifyHostkey(hostPort, serverHostKeyAlgorithm, serverHostKey);
        boolean isValid = KnownHosts.HOSTKEY_IS_OK == resultHost || KnownHosts.HOSTKEY_IS_OK == resultHostPort;

        if (!isValid) {
            LOGGER.log(Level.WARNING, "Host key {0} was not accepted.", hostPort);
            taskListener.getLogger().printf("Host key for host %s was not accepted.%n", hostPort);
        }

        return isValid;
    }

    KnownHosts getKnownHosts() {
        return knownHosts;
    }
}
