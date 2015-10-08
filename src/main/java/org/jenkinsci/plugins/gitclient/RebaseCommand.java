package org.jenkinsci.plugins.gitclient;

/**
 * RebaseCommand interface.
 */
public interface RebaseCommand extends GitCommand {

    /**
     * setUpstream.
     *
     * @param upstream a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.RebaseCommand} object.
     */
    RebaseCommand setUpstream(String upstream);
}
