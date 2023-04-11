package org.jenkinsci.plugins.gitclient.verifier;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.KnownHosts;
import hudson.model.TaskListener;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AcceptFirstConnectionVerifier extends HostKeyVerifierFactory {

    private static final Logger LOGGER = Logger.getLogger(AcceptFirstConnectionVerifier.class.getName());

    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> {
            listener.getLogger().println("Verifying host key using known hosts file, will automatically accept unseen keys");
            return "-o StrictHostKeyChecking=accept-new -o HashKnownHosts=yes";
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
        return new AcceptFirstConnectionJGitHostKeyVerifier(listener, knownHosts);
    }

    public class AcceptFirstConnectionJGitHostKeyVerifier extends AbstractJGitHostKeyVerifier {

        private final TaskListener listener;

        public AcceptFirstConnectionJGitHostKeyVerifier(TaskListener listener, KnownHosts knownHosts) {
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
            File knownHostsFile = getKnownHostsFile();
            Path path = Paths.get(knownHostsFile.getAbsolutePath());
            String hostnamePort = hostname + ":" + port;
            boolean isValid = false;
            if (Files.notExists(path)) {
                Files.createDirectories(knownHostsFile.getParentFile().toPath());
                Files.createFile(path);
                listener.getLogger().println("Creating new known hosts file " + path);
                writeToFile(knownHostsFile, hostnamePort, serverHostKeyAlgorithm, serverHostKey);
                isValid = true;
            } else {
                KnownHosts knownHosts = getKnownHosts();
                int hostPortResult = knownHosts.verifyHostkey(hostnamePort, serverHostKeyAlgorithm, serverHostKey);
                if (KnownHosts.HOSTKEY_IS_OK == hostPortResult || KnownHosts.HOSTKEY_IS_OK == knownHosts.verifyHostkey(hostname, serverHostKeyAlgorithm, serverHostKey)) {
                    isValid = true;
                } else if (KnownHosts.HOSTKEY_IS_NEW == hostPortResult) {
                    writeToFile(knownHostsFile, hostnamePort, serverHostKeyAlgorithm, serverHostKey);
                    isValid = true;
                }
            }

            if (!isValid) {
                LOGGER.log(Level.WARNING, "Host key {0} was not accepted.", hostnamePort);
                listener.getLogger().printf("Host key for host %s was not accepted.%n", hostnamePort);
            }

            return isValid;

        }

        private void writeToFile(File knownHostsFile, String hostnamePort, String serverHostKeyAlgorithm, byte[] serverHostKey) throws IOException {
            listener.getLogger().println("Adding " + hostnamePort + " to " + knownHostsFile.toPath());
            KnownHosts.addHostkeyToFile(knownHostsFile, new String[]{KnownHosts.createHashedHostname(hostnamePort)}, serverHostKeyAlgorithm, serverHostKey);
            getKnownHosts().addHostkey(new String[]{KnownHosts.createHashedHostname(hostnamePort)}, serverHostKeyAlgorithm, serverHostKey);
        }
    }

}
