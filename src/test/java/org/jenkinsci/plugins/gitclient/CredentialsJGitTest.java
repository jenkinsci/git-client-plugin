package org.jenkinsci.plugins.gitclient;

import java.io.File;
import java.io.IOException;
import org.junit.Before;

public class CredentialsJGitTest extends CredentialsTest {

    public CredentialsJGitTest(String gitRepoUrl, String username, File privateKey) {
        super(gitRepoUrl, username, privateKey);
        gitImpl = "jgit";
    }

    @Before
    @Override
    public void setUp() throws IOException, InterruptedException {
        super.setUp();
        clearExpectedLogSubstring();

        /* FetchWithCredentials does not log expected message */
        // addExpectedLogSubstring("remote: Counting objects");
    }

}
