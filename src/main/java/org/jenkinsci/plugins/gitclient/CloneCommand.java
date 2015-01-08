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

    CloneCommand timeout(Integer timeout);

    /**
     * When we just need to clone repository without populating the workspace (for instance when sparse checkouts are used).
     * This parameter does not do anything, a checkout will never be performed.
     */
    @Deprecated
    CloneCommand noCheckout();

    CloneCommand tags(boolean tags);

    CloneCommand refspecs(List<RefSpec> refspecs);
}
