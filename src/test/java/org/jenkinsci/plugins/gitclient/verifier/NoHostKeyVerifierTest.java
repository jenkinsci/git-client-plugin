package org.jenkinsci.plugins.gitclient.verifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.jenkinsci.plugins.gitclient.verifier.KnownHostsTestUtil.runKnownHostsTests;

import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class NoHostKeyVerifierTest {

    private NoHostKeyVerifier verifier;

    @Before
    public void assignVerifier() {
        verifier = new NoHostKeyVerifier();
    }

    @Test
    public void verifyServerHostKey() throws IOException {
        Assume.assumeTrue(runKnownHostsTests());
        NoHostKeyVerifier acceptFirstConnectionVerifier = new NoHostKeyVerifier();

        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        null,
                        acceptFirstConnectionVerifier.forJGit(StreamBuildListener.fromStdout()),
                        "ssh-ed25519" /* Indiferent for the test */,
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            // Should not fail because verifyServerHostKey always true
                            return true;
                        })
                .close();
    }

    @Test
    public void testVerifyHostKeyOption() throws IOException {
        assertThat(
                verifier.forCliGit(TaskListener.NULL).getVerifyHostKeyOption(Path.of("")),
                is("-o StrictHostKeyChecking=no"));
    }
}
