package org.jenkinsci.plugins.gitclient;

public enum GitMergeStrategy {
    DEFAULT, RESOLVE, RECURSIVE, OCTOPUS, OURS, SUBTREE;

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
