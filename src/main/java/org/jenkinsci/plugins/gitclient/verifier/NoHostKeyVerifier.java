package org.jenkinsci.plugins.gitclient.verifier;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.KnownHosts;
import hudson.model.TaskListener;

public class NoHostKeyVerifier extends HostKeyVerifierFactory {

    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> "-o StrictHostKeyChecking=no";
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {
        return new AbstractJGitHostKeyVerifier(new KnownHosts()) {

            @Override
            public String[] getServerHostKeyAlgorithms(Connection connection) {
                return new String[0];
            }

            @Override
            public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) {
                return true;
            }
        };
    }

}
