package org.jenkinsci.plugins.gitclient;

import java.util.List;

import org.eclipse.jgit.transport.RefSpec;

/**
 * Command to clone a repository. This command behaves differently from CLI clone command, it never actually checks out
 * into the workspace.
 *
 * @author Kohsuke Kawaguchi
 */
public interface CloneCommand extends GitCommand {
    /**
     * url.
     *
     * @param url a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    CloneCommand url(String url);

    /**
     * Name of the remote, such as 'origin' (which is the default.)
     *
     * @param name a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    CloneCommand repositoryName(String name);

    /**
     * shallow.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    CloneCommand shallow();

    /**
     * When the repository to clone is on the local machine, instead of using hard links, automatically setup
     * .git/objects/info/alternates to share the objects with the source repository
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    CloneCommand shared();

    /**
     * reference.
     *
     * @param reference a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    CloneCommand reference(String reference);

    /**
     * timeout.
     *
     * @param timeout a {@link java.lang.Integer} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    CloneCommand timeout(Integer timeout);

    /**
     * When we just need to clone repository without populating the workspace (for instance when sparse checkouts are used).
     * This parameter does not do anything, a checkout will never be performed.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    @Deprecated
    CloneCommand noCheckout();

    CloneCommand tags(boolean tags);

    CloneCommand refspecs(List<RefSpec> refspecs);

    /**
     * When shallow cloning, allow for a depth to be set in cases where you need more than the immediate last commit.
     * Has no effect if shallow is set to false (default)
     *
     * @param depth number of revisions to be included in shallow clone
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    CloneCommand depth(Integer depth);
}
