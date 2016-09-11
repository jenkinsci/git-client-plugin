package org.jenkinsci.plugins.gitclient;

/**
 * SubmoduleUpdateCommand interface.
 */
public interface SubmoduleUpdateCommand extends GitCommand {
    /**
     * If set true, submodule update will be recursive.  Default is
     * non-recursive.
     *
     * @param recursive if true, will recursively update submodules (requires git&gt;=1.6.5)
     * @return a {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand} object.
     */
    SubmoduleUpdateCommand recursive(boolean recursive);

    /**
     * If set true, submodule update will be forced even if the commit is
     * alraedy cecked out.  Default is non-forced.
     *
     * @param force if true, will force update of submodules (requires git&gt;=1.6.5)
     * @return a {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand} object.
     */
    SubmoduleUpdateCommand force(boolean force);

    /**
     * If set true and if the git version supports it, update the
     * submodules to the tip of the branch rather than to a specific
     * SHA1.  Refer to git documentation for details.  First available
     * in command line git 1.8.2.  Default is to update to a specific
     * SHA1 (compatible with previous versions of git)
     *
     * @param remoteTracking if true, will update the submodule to the tip of the branch requested (requires git&gt;=1.8.2)
     * @return a {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand} object.
     */
    SubmoduleUpdateCommand remoteTracking(boolean remoteTracking);

    /**
     * ref.
     *
     * @param ref a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand} object.
     */
    SubmoduleUpdateCommand ref(String ref);

    /**
     * useBranch.
     *
     * @param submodule a {@link java.lang.String} object.
     * @param branchname a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand} object.
     */
    SubmoduleUpdateCommand useBranch(String submodule, String branchname);

    /**
     * timeout.
     *
     * @param timeout a {@link java.lang.Integer} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand} object.
     */
    SubmoduleUpdateCommand timeout(Integer timeout);
}
