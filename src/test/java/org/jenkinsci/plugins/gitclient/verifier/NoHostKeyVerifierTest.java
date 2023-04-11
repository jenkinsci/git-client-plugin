package org.jenkinsci.plugins.gitclient.verifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.model.TaskListener;
import java.io.IOException;
import java.nio.file.Paths;
import org.jenkinsci.plugins.gitclient.trilead.JGitConnection;
import org.junit.Before;
import org.junit.Test;

public class NoHostKeyVerifierTest {

    private NoHostKeyVerifier verifier;

    @Before
    public void assignVerifier() {
        verifier = new NoHostKeyVerifier();
    }

    @Test
    public void testVerifyServerHostKey() throws IOException {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);
        // Should not fail because verifyServerHostKey always true
        jGitConnection.connect(verifier.forJGit(TaskListener.NULL));
    }

    @Test
    public void testVerifyHostKeyOption() throws IOException {
        assertThat(
                verifier.forCliGit(TaskListener.NULL).getVerifyHostKeyOption(Paths.get("")),
                is("-o StrictHostKeyChecking=no"));
    }

    /* Return true if running on a Kubernetes pod on ci.jenkins.io */
    private boolean isKubernetesCI() {
        String kubernetesPort = System.getenv("KUBERNETES_PORT");
        String buildURL = System.getenv("BUILD_URL");
        return kubernetesPort != null
                && !kubernetesPort.isEmpty()
                && buildURL != null
                && buildURL.startsWith("https://ci.jenkins.io/");
    }
}
