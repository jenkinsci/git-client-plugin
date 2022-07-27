package org.jenkinsci.plugins.gitclient.verifier;

import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

import java.io.File;
import java.io.IOException;

public interface AbstractCliGitHostKeyVerifier extends SerializableOnlyOverRemoting {

    /**
     * Specifies Git command-line options that control the logic of this verifier.
     * @param tempKnownHosts a temporary file that has already been created and may be used.
     * @return the command-line options
     * @throws IOException
     */
    String getVerifyHostKeyOption(File tempKnownHosts) throws IOException;

}
