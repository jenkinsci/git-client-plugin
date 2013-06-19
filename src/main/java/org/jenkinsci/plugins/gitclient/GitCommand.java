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
     */
    void execute() throws GitException, InterruptedException;
}
