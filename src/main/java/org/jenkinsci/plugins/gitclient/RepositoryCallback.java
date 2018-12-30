package org.jenkinsci.plugins.gitclient;

import hudson.FilePath.FileCallable;
import hudson.remoting.VirtualChannel;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.io.Serializable;

/**
 * Code that gets executed on the machine where the working directory is local
 * and {@link org.eclipse.jgit.lib.Repository} object is accessible.
 *
 * If necessary, the closure will be serialized and sent to remote.
 *
 * @see FileCallable
 * @author Kohsuke Kawaguchi
 */
public interface RepositoryCallback<T> extends Serializable {
    /**
     * Performs the computational task on the node where the data is located.
     *
     * <p>
     * All the exceptions are forwarded to the caller.
     *
     * @param repo
     *      Entry point to the git database. Caller is responsible for closing the repository.
     * @param channel
     *      The "back pointer" of the {@link hudson.remoting.Channel} that represents the communication
     *      with the node from where the code was sent.
     * @return a T object.
     * @throws java.io.IOException if any IO failure
     * @throws java.lang.InterruptedException if interrupted.
     */
    T invoke(Repository repo, VirtualChannel channel) throws IOException, InterruptedException;
}
