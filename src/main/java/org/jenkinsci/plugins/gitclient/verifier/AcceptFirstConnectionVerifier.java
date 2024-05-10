package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.eclipse.jgit.internal.transport.ssh.OpenSshConfigFile;
import org.eclipse.jgit.transport.SshConstants;

public class AcceptFirstConnectionVerifier extends HostKeyVerifierFactory {

    private static final Logger LOGGER = Logger.getLogger(AcceptFirstConnectionVerifier.class.getName());

    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> {
            listener.getLogger()
                    .println("Verifying host key using known hosts file, will automatically accept unseen keys");
            return "-o StrictHostKeyChecking=accept-new -o HashKnownHosts=yes";
        };
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {
        return new AcceptFirstConnectionJGitHostKeyVerifier(listener);
    }

    public class AcceptFirstConnectionJGitHostKeyVerifier extends AbstractJGitHostKeyVerifier {

        public AcceptFirstConnectionJGitHostKeyVerifier(TaskListener listener) {
            super(listener);
        }

        @Override
        public OpenSshConfigFile.HostEntry customizeHostEntry(OpenSshConfigFile.HostEntry hostEntry) {
            Path knowHostPath = getKnownHostsFile().toPath();
            // know_hosts
            if (Files.notExists(knowHostPath)) {
                try {
                    Path parent = knowHostPath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                        Files.createFile(knowHostPath);
                    } else {
                        throw new IllegalArgumentException("knowHostPath parent cannot be null");
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            // accept new
            hostEntry.setValue(SshConstants.STRICT_HOST_KEY_CHECKING, "accept-new");
            hostEntry.setValue(SshConstants.HASH_KNOWN_HOSTS, SshConstants.YES);
            if (getHostKeyAlgorithms() != null && !getHostKeyAlgorithms().isEmpty()) {
                hostEntry.setValue(SshConstants.HOST_KEY_ALGORITHMS, getHostKeyAlgorithms());
            }
            return hostEntry;
        }

        @Override
        public ServerKeyVerifier getServerKeyVerifier() {
            return new DefaultKnownHostsServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE, false);
        }
    }
}
