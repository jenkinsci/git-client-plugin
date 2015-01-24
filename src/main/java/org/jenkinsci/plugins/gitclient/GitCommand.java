package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.GitException;

/**
 * Base type for the builder style command object for various git commands.
 *
 * @author Kohsuke Kawaguchi
 */
public interface GitCommand {
    /**
     * Executes the command.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void execute() throws GitException, InterruptedException;
}
