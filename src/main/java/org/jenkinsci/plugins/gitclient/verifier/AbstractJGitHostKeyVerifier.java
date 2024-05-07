package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.eclipse.jgit.internal.transport.ssh.OpenSshConfigFile;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

public abstract class AbstractJGitHostKeyVerifier implements SerializableOnlyOverRemoting {

    private TaskListener taskListener;

    public static final String HOST_KEY_ALGORITHM_PROPERTY_KEY =
            AbstractJGitHostKeyVerifier.class + ".hostKeyAlgorithms";

    protected AbstractJGitHostKeyVerifier(TaskListener taskListener) {
        this.taskListener = taskListener;
    }

    public TaskListener getTaskListener() {
        return taskListener;
    }

    public abstract OpenSshConfigFile.HostEntry customizeHostEntry(OpenSshConfigFile.HostEntry hostEntry);

    public abstract ServerKeyVerifier getServerKeyVerifier();

    /**
     * to set key algorithm for host.
     * @return the key algorithms for host key (default empty)
     */
    public String getHostKeyAlgorithms() {
        return System.getProperty(HOST_KEY_ALGORITHM_PROPERTY_KEY, "");
    }
}
