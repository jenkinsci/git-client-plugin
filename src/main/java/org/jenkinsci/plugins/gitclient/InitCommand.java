package org.jenkinsci.plugins.gitclient;

public abstract class InitCommand implements GitCommand {

    public String workspace;
    public boolean bare;

    public InitCommand workspace(String workspace) {
        this.workspace = workspace;
        return this;
    }

    public InitCommand bare(boolean bare) {
        this.bare = bare;
        return this;
    }
}
