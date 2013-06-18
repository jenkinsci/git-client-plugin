package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.GitException;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class CloneCommand {
    public abstract CloneCommand url(String url);

    /**
     * Name of the remote, such as 'origin' (which is the default.)
     */
    public abstract CloneCommand repositoryName(String name);

    public abstract CloneCommand shallow();

    public abstract CloneCommand reference(String reference);

    /**
     * Executes the command.
     */
    public abstract void execute() throws GitException, InterruptedException;
}
