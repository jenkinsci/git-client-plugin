package org.jenkinsci.plugins.gitclient.verifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.jenkinsci.plugins.gitclient.verifier.KnownHostsTestUtil.nonGitHubHost;
import static org.jenkinsci.plugins.gitclient.verifier.KnownHostsTestUtil.runKnownHostsTests;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import java.io.File;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnownHostsFileVerifierTest {

    private static final String FILE_CONTENT =
            "github.com ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=";

    // Create a temporary folder and assert folder deletion at end of tests
    @TempDir
    private File testFolder;

    private File fakeKnownHosts;

    private final KnownHostsTestUtil knownHostsTestUtil = new KnownHostsTestUtil(testFolder);

    @BeforeEach
    void assignVerifiers() throws Exception {
        fakeKnownHosts = knownHostsTestUtil.createFakeKnownHosts(FILE_CONTENT);
    }

    @Test
    void connectWhenHostKeyNotInKnownHostsFileForOtherHostNameThenShouldFail() throws Exception {
        assumeTrue(runKnownHostsTests());
        fakeKnownHosts = knownHostsTestUtil.createFakeKnownHosts("fake2.ssh", "known_hosts_fake2", FILE_CONTENT);
        KnownHostsFileVerifier knownHostsFileVerifier = spy(new KnownHostsFileVerifier());
        when(knownHostsFileVerifier.getKnownHostsFile()).thenReturn(fakeKnownHosts);

        KnownHostsTestUtil.connectToHost(
                        nonGitHubHost(),
                        22,
                        fakeKnownHosts,
                        knownHostsFileVerifier.forJGit(StreamBuildListener.fromStdout()),
                        "ecdsa-sha2-nistp256",
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(false));
                            return true;
                        })
                .close();
    }

    @Test
    void connectWhenHostKeyProvidedThenShouldNotFail() throws Exception {
        assumeTrue(runKnownHostsTests());
        KnownHostsFileVerifier knownHostsFileVerifier = spy(new KnownHostsFileVerifier());
        when(knownHostsFileVerifier.getKnownHostsFile()).thenReturn(fakeKnownHosts);

        // Should not fail because hostkey for 'github.com:22' is in known_hosts
        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        fakeKnownHosts,
                        knownHostsFileVerifier.forJGit(StreamBuildListener.fromStdout()),
                        "ecdsa-sha2-nistp256",
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();
    }

    @Test
    void connectWhenHostKeyInKnownHostsFileWithNotDefaultAlgorithmThenShouldNotFail() throws Exception {
        assumeTrue(runKnownHostsTests());
        fakeKnownHosts = knownHostsTestUtil.createFakeKnownHosts(
                "fake2.ssh",
                "known_hosts_fake2",
                "github.com ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=");
        KnownHostsFileVerifier knownHostsFileVerifier = spy(new KnownHostsFileVerifier());
        when(knownHostsFileVerifier.getKnownHostsFile()).thenReturn(fakeKnownHosts);

        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        fakeKnownHosts,
                        knownHostsFileVerifier.forJGit(StreamBuildListener.fromStdout()),
                        "ecdsa-sha2-nistp256",
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();
    }

    @Test
    void testVerifyHostKeyOptionWithDefaultFile() throws Exception {
        KnownHostsFileVerifier verifier = new KnownHostsFileVerifier();
        assertThat(
                verifier.forCliGit(TaskListener.NULL).getVerifyHostKeyOption(null), is("-o StrictHostKeyChecking=yes"));
    }
}
