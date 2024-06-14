package org.jenkinsci.plugins.gitclient.verifier;

import hudson.console.HyperlinkNote;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;

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
        Path knowHostPath = getKnownHostsFile().toPath();
        if (Files.notExists(knowHostPath)) {
            try {
                logHint(listener);
                Path parent = knowHostPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                    Files.createFile(knowHostPath);
                } else {
                    throw new IllegalArgumentException("knowHostPath parent cannot be null");
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, e, () -> "Could not load known hosts.");
            }
        }
        return new KnownHostsFileJGitHostKeyVerifier(listener, this);
    }

    public static class KnownHostsFileJGitHostKeyVerifier extends AbstractJGitHostKeyVerifier {

        public KnownHostsFileJGitHostKeyVerifier(TaskListener listener, HostKeyVerifierFactory hostKeyVerifierFactory) {
            super(listener, hostKeyVerifierFactory);
        }

        @Override
        public ServerKeyDatabase.Configuration getServerKeyDatabaseConfiguration() {
            return new AbstractJGitHostKeyVerifier.DefaultConfiguration(
                    this.getHostKeyVerifierFactory(),
                    () -> ServerKeyDatabase.Configuration.StrictHostKeyChecking.REQUIRE_MATCH);
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
