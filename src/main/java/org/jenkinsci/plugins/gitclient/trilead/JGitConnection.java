package org.jenkinsci.plugins.gitclient.trilead;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.ConnectionInfo;
import com.trilead.ssh2.ServerHostKeyVerifier;
import java.io.IOException;
import org.jenkinsci.plugins.gitclient.verifier.AbstractJGitHostKeyVerifier;

public class JGitConnection extends Connection {

    public JGitConnection(String hostname, int port) {
        super(hostname, port);
    }

    @Override
    public ConnectionInfo connect(ServerHostKeyVerifier verifier) throws IOException {
        if (verifier instanceof AbstractJGitHostKeyVerifier) {
            String[] serverHostKeyAlgorithms =
                    ((AbstractJGitHostKeyVerifier) verifier).getServerHostKeyAlgorithms(this);
            if (serverHostKeyAlgorithms != null && serverHostKeyAlgorithms.length > 0) {
                setServerHostKeyAlgorithms(serverHostKeyAlgorithms);
            }
        }
        return super.connect(verifier);
    }
}
