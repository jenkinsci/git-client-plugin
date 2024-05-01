package org.jenkinsci.plugins.gitclient.verifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KnownHostsFileVerifierTest {

    private static final String FILE_CONTENT = "|1|x2OaBkti6peaNPX1ftYHvWscOqk=|dYFtgxb3j9bwB8gHGMBnV7tTzJ8="
            + " ssh-ecdsa-sha2-nistp256"
            + " AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=";
    //            "|1|MMHhyJWbis6eLbmW7/vVMgWL01M=|OT564q9RmLIALJ94imtE4PaCewU="
    //            + " ssh-ed25519"
    //            + " AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";

    // Create a temporary folder and assert folder deletion at end of tests
    @Rule
    public TemporaryFolder testFolder =
            TemporaryFolder.builder().assureDeletion().build();

    private File fakeKnownHosts;

    private final KnownHostsTestUtil knownHostsTestUtil = new KnownHostsTestUtil(testFolder);

    @Before
    public void assignVerifiers() throws IOException {
        fakeKnownHosts = knownHostsTestUtil.createFakeKnownHosts(FILE_CONTENT);
    }

    @Test
    public void connectWhenHostKeyNotInKnownHostsFileForOtherHostNameThenShouldFail() throws Exception {
        fakeKnownHosts = knownHostsTestUtil.createFakeKnownHosts("fake2.ssh", "known_hosts_fake2", FILE_CONTENT);
        KnownHostsFileVerifier knownHostsFileVerifier = spy(new KnownHostsFileVerifier());
        when(knownHostsFileVerifier.getKnownHostsFile()).thenReturn(fakeKnownHosts);

        KnownHostsTestUtil.connectToHost(
                        "bitbucket.org",
                        22,
                        fakeKnownHosts,
                        knownHostsFileVerifier.forJGit(StreamBuildListener.fromStdout()),
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(false));
                            return true;
                        })
                .close();
    }

    @Test
    public void connectWhenHostKeyProvidedThenShouldNotFail() throws IOException {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        KnownHostsFileVerifier knownHostsFileVerifier = spy(new KnownHostsFileVerifier());
        when(knownHostsFileVerifier.getKnownHostsFile()).thenReturn(fakeKnownHosts);

        // Should not fail because hostkey for 'github.com:22' is in known_hosts
        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        fakeKnownHosts,
                        knownHostsFileVerifier.forJGit(StreamBuildListener.fromStdout()),
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();
    }

    @Test
    public void connectWhenHostKeyInKnownHostsFileWithNotDefaultAlgorithmThenShouldNotFail() throws IOException {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
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
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();

        //        JGitConnection jGitConnection = new JGitConnection("github.com", 22);
        // Should not fail because hostkey for 'github.com:22' is in known_hosts with algorithm 'ecdsa-sha2-nistp256
        // FIXME ol
        //        jGitConnection.connect(verifier);
    }

    @Test
    public void testVerifyHostKeyOptionWithDefaultFile() throws Exception {
        KnownHostsFileVerifier verifier = new KnownHostsFileVerifier();
        assertThat(
                verifier.forCliGit(TaskListener.NULL).getVerifyHostKeyOption(null), is("-o StrictHostKeyChecking=yes"));
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
