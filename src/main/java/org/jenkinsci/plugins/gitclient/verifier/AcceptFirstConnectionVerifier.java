package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
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
                    Files.createDirectories(knowHostPath.getParent());
                    Files.createFile(knowHostPath);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            // accept new
            hostEntry.setValue(SshConstants.STRICT_HOST_KEY_CHECKING, "accept-new");
            hostEntry.setValue(SshConstants.HASH_KNOWN_HOSTS, SshConstants.YES);

            return hostEntry;
        }
    }
}
