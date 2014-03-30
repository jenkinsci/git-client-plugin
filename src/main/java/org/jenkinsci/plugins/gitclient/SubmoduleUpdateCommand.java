package org.jenkinsci.plugins.gitclient;

public abstract class SubmoduleUpdateCommand implements GitCommand {
    protected boolean recursive      = false;
    protected boolean remoteTracking = false;
    protected String  ref            = null;

    /**
     * If set true, submodule update will be recursive.  Default is
     * non-recursive.
     * @param recursive if true, will recursively update submodules (requires git>=1.6.5)
     */
    public SubmoduleUpdateCommand recursive(boolean recursive) {
        this.recursive = recursive;
        return this;
    }

    /**
     * If set true and if the git version supports it, update the
     * submodules to the tip of the branch rather than to a specific
     * SHA1.  Refer to git documentation for details.  First available
     * in command line git 1.8.2.  Default is to update to a specific
     * SHA1 (compatible with previous versions of git)
     * @param remoteTracking if true, will update the submodule to the tip of the branch requested (requires git>=1.8.2)
     */
    public SubmoduleUpdateCommand remoteTracking(boolean remoteTracking) {
        this.remoteTracking = remoteTracking;
        return this;
    }

    public SubmoduleUpdateCommand ref(String ref) {
        this.ref = ref;
        return this;
    }
}
