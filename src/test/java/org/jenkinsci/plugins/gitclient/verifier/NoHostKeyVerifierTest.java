package org.jenkinsci.plugins.gitclient.verifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.jenkinsci.plugins.gitclient.verifier.KnownHostsTestUtil.runKnownHostsTests;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import java.nio.file.Path;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoHostKeyVerifierTest {

    private NoHostKeyVerifier verifier;

    @BeforeEach
    void assignVerifier() {
        verifier = new NoHostKeyVerifier();
    }

    @Test
    void verifyServerHostKey() throws Exception {
        assumeTrue(runKnownHostsTests());
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
    void testVerifyHostKeyOption() throws Exception {
        assertThat(
                verifier.forCliGit(TaskListener.NULL).getVerifyHostKeyOption(Path.of("")),
                is("-o StrictHostKeyChecking=no"));
    }
}
