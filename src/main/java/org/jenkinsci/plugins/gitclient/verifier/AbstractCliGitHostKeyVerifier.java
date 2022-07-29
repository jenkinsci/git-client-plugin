package org.jenkinsci.plugins.gitclient.verifier;

import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

import java.io.IOException;
import java.nio.file.Path;

public interface AbstractCliGitHostKeyVerifier extends SerializableOnlyOverRemoting {

    /**
     * Specifies Git command-line options that control the logic of this verifier.
     * @param tempKnownHosts a temporary file that has already been created and may be used.
     * @param url a server url
     * @return the command-line options
     * @throws IOException on input or output error
     */
    String getVerifyHostKeyOption(Path tempKnownHosts, URIish url) throws IOException;

}
