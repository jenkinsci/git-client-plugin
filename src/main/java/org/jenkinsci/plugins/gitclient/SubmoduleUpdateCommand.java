package org.jenkinsci.plugins.gitclient;

public abstract class SubmoduleUpdateCommand implements GitCommand {
    protected boolean recursive      = false;
    protected boolean remoteTracking = false;
    protected String  ref            = null;

    public SubmoduleUpdateCommand recursive(boolean recursive) {
        this.recursive = recursive;
        return this;
    }

    public SubmoduleUpdateCommand remoteTracking(boolean remoteTracking) {
        this.remoteTracking = remoteTracking;
        return this;
    }

    public SubmoduleUpdateCommand ref(String ref) {
        this.ref = ref;
        return this;
    }
}
