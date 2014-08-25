package org.jenkinsci.plugins.gitclient;

import java.io.File;

public class CredentialsJGitTest extends CredentialsTest {

    public CredentialsJGitTest(String gitRepoUrl, String username, File privateKey) {
        super(gitRepoUrl, username, privateKey);
        gitImpl = "jgit";
    }

}
