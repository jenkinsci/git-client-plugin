package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import java.io.File;
import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

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
