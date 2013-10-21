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

    /**
     * When the repository to clone is on the local machine, instead of using hard links, automatically setup
     * .git/objects/info/alternates to share the objects with the source repository
     */
    CloneCommand shared();

    CloneCommand reference(String reference);

    /**
     * When we just need to clone repository without populating the workspace (for instance when sparse checkouts are used)
     */
    CloneCommand noCheckout();
}
