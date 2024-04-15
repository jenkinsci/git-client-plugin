package org.jenkinsci.plugins.gitclient.verifier;

import hudson.console.HyperlinkNote;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jgit.internal.transport.ssh.OpenSshConfigFile;
import org.eclipse.jgit.transport.SshConstants;

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
                Files.createDirectories(knowHostPath.getParent());
                Files.createFile(knowHostPath);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, e, () -> "Could not load known hosts.");
            }
        }
        return new KnownHostsFileJGitHostKeyVerifier(listener);
    }

    public static class KnownHostsFileJGitHostKeyVerifier extends AbstractJGitHostKeyVerifier {

        public KnownHostsFileJGitHostKeyVerifier(TaskListener listener) {
            super(listener);
        }

        @Override
        public OpenSshConfigFile.HostEntry customizeHostEntry(OpenSshConfigFile.HostEntry hostEntry) {
            hostEntry.setValue(SshConstants.STRICT_HOST_KEY_CHECKING, SshConstants.YES);
            return hostEntry;
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
