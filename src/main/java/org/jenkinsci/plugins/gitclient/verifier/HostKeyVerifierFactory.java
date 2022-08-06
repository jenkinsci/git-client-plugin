package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

import java.io.File;

public abstract class HostKeyVerifierFactory implements SerializableOnlyOverRemoting {

    /**
     * @return Implementation of verifier for Command line git
     */
    public abstract AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener);

    /**
     * @return Implementation of verifier for JGit
     */
    public abstract AbstractJGitHostKeyVerifier forJGit(TaskListener listener);

    File getKnownHostsFile() {
        return SshHostKeyVerificationStrategy.JGIT_KNOWN_HOSTS_FILE;
    }

}
