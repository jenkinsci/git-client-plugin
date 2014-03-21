package org.jenkinsci.plugins.gitclient;

public abstract class InitCommand implements GitCommand {

    public String workspace;

    public InitCommand workspace(String workspace) {
        this.workspace = workspace;
        return this;
    }
}
