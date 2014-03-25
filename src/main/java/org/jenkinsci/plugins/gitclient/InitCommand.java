package org.jenkinsci.plugins.gitclient;

public interface InitCommand extends GitCommand {

    InitCommand workspace(String workspace);

    InitCommand bare(boolean bare);
}
