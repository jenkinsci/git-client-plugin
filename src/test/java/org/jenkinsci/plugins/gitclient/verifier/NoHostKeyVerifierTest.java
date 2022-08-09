package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import org.jenkinsci.plugins.gitclient.trilead.JGitConnection;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class NoHostKeyVerifierTest {

    private NoHostKeyVerifier verifier;

    @Before
    public void assignVerifier() {
        verifier = new NoHostKeyVerifier();
    }

    @Test
    public void testVerifyServerHostKey() throws IOException {
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);
        // Should not fail because verifyServerHostKey always true
        try {
            jGitConnection.connect(verifier.forJGit(TaskListener.NULL));
        } finally {
            jGitConnection.close();
        }
    }

    @Test
    public void testVerifyHostKeyOption() throws IOException {
        assertThat(verifier.forCliGit(TaskListener.NULL).getVerifyHostKeyOption(Paths.get("")), is("-o StrictHostKeyChecking=no"));
    }
}
