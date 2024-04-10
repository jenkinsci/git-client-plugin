package org.jenkinsci.plugins.gitclient.verifier;

import hudson.console.HyperlinkNote;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;

public class KnownHostsFileVerifier extends HostKeyVerifierFactory {

    private static final Logger LOGGER = Logger.getLogger(KnownHostsFileVerifier.class.getName());

    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> {
            listener.getLogger().println("Verifying host key using known hosts file");
            if (Files.notExists(new File(SshHostKeyVerificationStrategy.KNOWN_HOSTS_DEFAULT).toPath())) {
                logHint(listener);
            } else {
                LOGGER.log(Level.FINEST, "Verifying host key using known hosts file {0}", new Object[] {
                    SshHostKeyVerificationStrategy.KNOWN_HOSTS_DEFAULT
                });
            }
            return "-o StrictHostKeyChecking=yes";
        };
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {
        // FIXME create file?
        //        KnownHosts knownHosts;
        //        try {
        //            if (Files.exists(getKnownHostsFile().toPath())) {
        //                knownHosts = new KnownHosts(getKnownHostsFile());
        //            } else {
        //                logHint(listener);
        //                knownHosts = new KnownHosts();
        //            }
        //        } catch (IOException e) {
        //            LOGGER.log(Level.WARNING, e, () -> "Could not load known hosts.");
        //            knownHosts = new KnownHosts();
        //        }
        return new KnownHostsFileJGitHostKeyVerifier(listener, (clientSession, socketAddress, publicKey) -> false);
    }

    public class KnownHostsFileJGitHostKeyVerifier extends AbstractJGitHostKeyVerifier {

        private final TaskListener listener;

        public KnownHostsFileJGitHostKeyVerifier(TaskListener listener, ServerKeyVerifier serverKeyVerifier) {
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
                    .printf(
                            "Verifying host key for %s using %s %n",
                            hostname, getKnownHostsFile().toPath());
            LOGGER.log(Level.FINEST, "Verifying {0}:{1} in known hosts file {2} with host key {3} {4}", new Object[] {
                hostname,
                port,
                SshHostKeyVerificationStrategy.KNOWN_HOSTS_DEFAULT,
                serverHostKeyAlgorithm,
                Base64.getEncoder().encodeToString(serverHostKey)
            });
            return super.verifyServerHostKey(
                    taskListener, serverKeyVerifier, hostname, port, serverHostKeyAlgorithm, serverHostKey);
        }
    }

    private void logHint(TaskListener listener) {
        listener.getLogger()
                .println(HyperlinkNote.encodeTo(
                        "https://plugins.jenkins.io/git-client/#plugin-content-ssh-host-key-verification",
                        "You're using 'Known hosts file' strategy to verify ssh host keys,"
                                + " but your known_hosts file does not exist, please go to "
                                + "'Manage Jenkins' -> 'Security' -> 'Git Host Key Verification Configuration' "
                                + "and configure host key verification."));
        LOGGER.log(
                Level.FINEST,
                "Known hosts file {0} not found, but verifying host keys with known hosts file",
                new Object[] {SshHostKeyVerificationStrategy.KNOWN_HOSTS_DEFAULT});
    }
}
