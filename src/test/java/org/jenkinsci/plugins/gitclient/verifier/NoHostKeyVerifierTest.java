package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import org.jenkinsci.plugins.gitclient.trilead.JGitConnection;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;

public class NoHostKeyVerifierTest {

    private NoHostKeyVerifier verifier;

    @Before
    public void assignVerifier() {
        verifier = new NoHostKeyVerifier();
    }

    @Test
    public void testVerifyServerHostKey() {
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);
        try {
            jGitConnection.connect(verifier.forJGit(TaskListener.NULL));
        } catch (IOException e) {
            fail("Should not fail because verifyServerHostKey always true");
        }
    }

    @Test
    public void testVerifyHostKeyOption() throws IOException {
        assertThat(verifier.forCliGit(TaskListener.NULL).getVerifyHostKeyOption(Paths.get("")), is("-o StrictHostKeyChecking=no"));
    }
}
