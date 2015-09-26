package org.jenkinsci.plugins.gitclient;

import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import java.util.List;

/**
 * FetchCommand interface.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public interface FetchCommand extends GitCommand {

    /**
     * from.
     *
     * @param remote a {@link org.eclipse.jgit.transport.URIish} object.
     * @param refspecs a {@link java.util.List} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.FetchCommand} object.
     */
    FetchCommand from(URIish remote, List<RefSpec> refspecs);

    /**
     * prune.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.FetchCommand} object.
     */
    FetchCommand prune();

    /**
     * shallow.
     *
     * @param shallow a boolean.
     * @return a {@link org.jenkinsci.plugins.gitclient.FetchCommand} object.
     */
    FetchCommand shallow(boolean shallow);
    
    /**
     * timeout.
     *
     * @param timeout a {@link java.lang.Integer} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.FetchCommand} object.
     */
    FetchCommand timeout(Integer timeout);

    FetchCommand tags(boolean tags);

    /**
     * When shallow cloning, allow for a depth to be set in cases where you need more than the immediate last commit.
     * Has no effect if shallow is set to false (default)
     *
     * @param depth number of revisions to be included in shallow clone
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    FetchCommand depth(Integer depth);
}
