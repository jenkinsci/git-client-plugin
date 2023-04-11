package org.jenkinsci.plugins.gitclient.verifier;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.KnownHosts;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ManuallyProvidedKeyVerifier extends HostKeyVerifierFactory {

    private static final Logger LOGGER = Logger.getLogger(ManuallyProvidedKeyVerifier.class.getName());

    private final String approvedHostKeys;

    public ManuallyProvidedKeyVerifier(String approvedHostKeys) {
        this.approvedHostKeys = approvedHostKeys;
    }

    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> {
            Files.write(tempKnownHosts, (approvedHostKeys + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            listener.getLogger().println("Verifying host key using manually-configured host key entries");
            String userKnownHostsFileFlag;
            if (File.pathSeparatorChar
                    == ';') { // check whether on Windows or not without sending Functions over remoting
                // no escaping for windows because created temp file can't contain spaces
                userKnownHostsFileFlag = String.format(" -o UserKnownHostsFile=%s", tempKnownHosts.toAbsolutePath());
            } else {
                // escaping needed in case job name contains spaces
                userKnownHostsFileFlag = String.format(
                        " -o UserKnownHostsFile=\\\"\"\"%s\\\"\"\"",
                        tempKnownHosts.toAbsolutePath().toString().replace(" ", "\\ "));
            }
            return "-o StrictHostKeyChecking=yes " + userKnownHostsFileFlag;
        };
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {
        KnownHosts knownHosts;
        try {
            knownHosts = approvedHostKeys != null ? new KnownHosts(approvedHostKeys.toCharArray()) : new KnownHosts();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not load known hosts.");
            knownHosts = new KnownHosts();
        }
        return new ManuallyProvidedKeyJGitHostKeyVerifier(listener, knownHosts);
    }

    public static class ManuallyProvidedKeyJGitHostKeyVerifier extends AbstractJGitHostKeyVerifier {

        private final TaskListener listener;

        public ManuallyProvidedKeyJGitHostKeyVerifier(TaskListener listener, KnownHosts knownHosts) {
            super(knownHosts);
            this.listener = listener;
        }

        @Override
        public String[] getServerHostKeyAlgorithms(Connection connection) throws IOException {
            return getPreferredServerHostkeyAlgorithmOrder(connection);
        }

        @Override
        public boolean verifyServerHostKey(
                String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
            listener.getLogger()
                    .printf("Verifying host key for %s using manually-configured host key entries %n", hostname);
            return verifyServerHostKey(
                    listener, getKnownHosts(), hostname, port, serverHostKeyAlgorithm, serverHostKey);
        }
    }
}
