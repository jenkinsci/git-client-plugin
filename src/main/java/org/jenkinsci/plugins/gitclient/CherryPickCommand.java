package org.jenkinsci.plugins.gitclient;

import org.eclipse.jgit.lib.ObjectId;

import java.util.List;

/**
 * RebaseCommand interface.
 */
public interface CherryPickCommand extends GitCommand {

    /**
     * Commits
     *
     * @param commits a {@link java.util.List} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.CherryPickCommand} object.
     */
    CherryPickCommand commits(List<ObjectId> commits);

    /**
     * Range or sha1 commits
     *
     * @param reference a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     */
    CherryPickCommand setReference(String reference);
}
