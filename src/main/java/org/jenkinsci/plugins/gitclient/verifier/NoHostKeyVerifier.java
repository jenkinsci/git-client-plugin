package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import java.util.logging.Logger;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.eclipse.jgit.internal.transport.ssh.OpenSshConfigFile;
import org.eclipse.jgit.transport.SshConstants;

public class NoHostKeyVerifier extends HostKeyVerifierFactory {

    private static final Logger LOGGER = Logger.getLogger(NoHostKeyVerifier.class.getName());

    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> "-o StrictHostKeyChecking=no";
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {
        return new AbstractJGitHostKeyVerifier(listener) {
            @Override
            public OpenSshConfigFile.HostEntry customizeHostEntry(OpenSshConfigFile.HostEntry hostEntry) {
                hostEntry.setValue(SshConstants.STRICT_HOST_KEY_CHECKING, SshConstants.NO);
                return hostEntry;
            }

            @Override
            public ServerKeyVerifier getServerKeyVerifier() {
                return AcceptAllServerKeyVerifier.INSTANCE;
            }
        };
    }
}
