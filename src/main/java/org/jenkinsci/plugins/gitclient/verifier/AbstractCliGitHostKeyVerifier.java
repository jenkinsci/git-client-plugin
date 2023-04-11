package org.jenkinsci.plugins.gitclient.verifier;

import java.io.IOException;
import java.nio.file.Path;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

public interface AbstractCliGitHostKeyVerifier extends SerializableOnlyOverRemoting {

    /**
     * Specifies Git command-line options that control the logic of this verifier.
     * @param tempKnownHosts a temporary file that has already been created and may be used.
     * @return the command-line options
     * @throws IOException on input or output error
     */
    String getVerifyHostKeyOption(Path tempKnownHosts) throws IOException;
}
