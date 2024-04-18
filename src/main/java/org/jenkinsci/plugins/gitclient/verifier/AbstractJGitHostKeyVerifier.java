package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.eclipse.jgit.internal.transport.ssh.OpenSshConfigFile;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

public abstract class AbstractJGitHostKeyVerifier implements SerializableOnlyOverRemoting {

    private TaskListener taskListener;

    protected AbstractJGitHostKeyVerifier(TaskListener taskListener) {
        this.taskListener = taskListener;
    }

    public TaskListener getTaskListener() {
        return taskListener;
    }

    public abstract OpenSshConfigFile.HostEntry customizeHostEntry(OpenSshConfigFile.HostEntry hostEntry);

    public abstract ServerKeyVerifier getServerKeyVerifier();
}
