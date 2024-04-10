package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;

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
            LOGGER.log(
                    Level.FINEST,
                    "Verifying manually-configured host keys entry in {0} with host keys {1}",
                    new Object[] {tempKnownHosts.toAbsolutePath(), approvedHostKeys});
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

        // FIXME check this
        //        KnownHosts knownHosts;
        //        try {
        //            knownHosts = approvedHostKeys != null ? new KnownHosts(approvedHostKeys.toCharArray()) : new
        // KnownHosts();
        //        } catch (IOException e) {
        //            LOGGER.log(Level.WARNING, e, () -> "Could not load known hosts.");
        //            knownHosts = new KnownHosts();
        //        }
        return new ManuallyProvidedKeyJGitHostKeyVerifier(listener, (clientSession, socketAddress, publicKey) -> false);
    }

    public static class ManuallyProvidedKeyJGitHostKeyVerifier extends AbstractJGitHostKeyVerifier {

        private final TaskListener listener;

        public ManuallyProvidedKeyJGitHostKeyVerifier(TaskListener listener, ServerKeyVerifier serverKeyVerifier) {
            super(serverKeyVerifier);
            this.listener = listener;
        }

        @Override
        public boolean verifyServerHostKey(
                TaskListener taskListener,
                ServerKeyVerifier serverKeyVerifier,
                String hostname,
                int port,
                String serverHostKeyAlgorithm,
                byte[] serverHostKey)
                throws IOException {
            listener.getLogger()
                    .printf("Verifying host key for %s using manually-configured host key entries %n", hostname);
            LOGGER.log(Level.FINEST, "Verifying host {0}:{1} with manually-configured host key {2} {3}", new Object[] {
                hostname, port, serverHostKeyAlgorithm, Base64.getEncoder().encodeToString(serverHostKey)
            });
            return super.verifyServerHostKey(
                    listener, serverKeyVerifier, hostname, port, serverHostKeyAlgorithm, serverHostKey);
        }
    }
}
