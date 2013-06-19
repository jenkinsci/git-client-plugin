package org.jenkinsci.plugins.gitclient;

/**
 * @author Kohsuke Kawaguchi
 */
public interface CloneCommand extends GitCommand {
    CloneCommand url(String url);

    /**
     * Name of the remote, such as 'origin' (which is the default.)
     */
    CloneCommand repositoryName(String name);

    CloneCommand shallow();

    CloneCommand reference(String reference);
}
