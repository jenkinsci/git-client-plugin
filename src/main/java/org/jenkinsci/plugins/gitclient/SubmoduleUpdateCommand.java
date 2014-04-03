package org.jenkinsci.plugins.gitclient;

public interface SubmoduleUpdateCommand extends GitCommand {
    /**
     * If set true, submodule update will be recursive.  Default is
     * non-recursive.
     * @param recursive if true, will recursively update submodules (requires git>=1.6.5)
     */
    SubmoduleUpdateCommand recursive(boolean recursive);

    /**
     * If set true and if the git version supports it, update the
     * submodules to the tip of the branch rather than to a specific
     * SHA1.  Refer to git documentation for details.  First available
     * in command line git 1.8.2.  Default is to update to a specific
     * SHA1 (compatible with previous versions of git)
     * @param remoteTracking if true, will update the submodule to the tip of the branch requested (requires git>=1.8.2)
     */
    SubmoduleUpdateCommand remoteTracking(boolean remoteTracking);

    SubmoduleUpdateCommand ref(String ref);
}
