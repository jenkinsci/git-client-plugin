package org.jenkinsci.plugins.gitclient.verifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.jenkinsci.plugins.gitclient.verifier.KnownHostsTestUtil.nonGitHubHost;
import static org.jenkinsci.plugins.gitclient.verifier.KnownHostsTestUtil.runKnownHostsTests;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManuallyProvidedKeyVerifierTest {

    // Create a temporary folder and assert folder deletion at end of tests
    @TempDir
    private File testFolder;

    private String hostKey;

    @BeforeEach
    void assignVerifier() { // For github.com
        hostKey =
                "|1|7qEjynZk0IodegnbgoPEhWtdgA8=|bGs7a1ktbGWwPuZqqTbAazUAULM= ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=";
    }

    @Test
    void connectWhenHostKeyProvidedForOtherHostNameThenShouldFail() throws Exception {
        assumeTrue(runKnownHostsTests());
        HostKeyVerifierFactory verifier = new ManuallyProvidedKeyVerifier(hostKey);

        KnownHostsTestUtil.connectToHost(
                        nonGitHubHost(),
                        22,
                        new File(testFolder + "/path/to/file/random"),
                        verifier.forJGit(StreamBuildListener.fromStdout()),
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
        ManuallyProvidedKeyVerifier verifier = new ManuallyProvidedKeyVerifier(hostKey);
        ManuallyProvidedKeyVerifier.ManuallyProvidedKeyJGitHostKeyVerifier jGitHostKeyVerifier =
                (ManuallyProvidedKeyVerifier.ManuallyProvidedKeyJGitHostKeyVerifier)
                        verifier.forJGit(StreamBuildListener.fromStdout());
        Path tempKnownHosts = Files.createTempFile("known_hosts", "");
        Files.writeString(tempKnownHosts, hostKey + System.lineSeparator());
        KnownHostsTestUtil.connectToHost(
                        "github.com", 22, tempKnownHosts.toFile(), jGitHostKeyVerifier, "ecdsa-sha2-nistp256", s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            // Should not fail because hostkey for 'github.com:22' was provided
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();
    }

    @Test
    void connectWhenWrongHostKeyProvidedThenShouldFail() throws Exception {
        assumeTrue(runKnownHostsTests());
        String key = "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9OOOO";
        HostKeyVerifierFactory verifier = new ManuallyProvidedKeyVerifier(key);

        ManuallyProvidedKeyVerifier.ManuallyProvidedKeyJGitHostKeyVerifier jGitHostKeyVerifier =
                (ManuallyProvidedKeyVerifier.ManuallyProvidedKeyJGitHostKeyVerifier)
                        verifier.forJGit(StreamBuildListener.fromStdout());
        Path tempKnownHosts = Files.createTempFile("known_hosts", "");
        Files.writeString(tempKnownHosts, key + System.lineSeparator());
        KnownHostsTestUtil.connectToHost(
                        "github.com", 22, tempKnownHosts.toFile(), jGitHostKeyVerifier, "ssh-ed25519", s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(false));
                            return true;
                        })
                .close();
    }

    @Test
    void connectWhenHostKeyProvidedWithPortThenShouldNotFail() throws Exception {
        assumeTrue(runKnownHostsTests());
        String key =
                "github.com:22 ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=";
        HostKeyVerifierFactory verifier = new ManuallyProvidedKeyVerifier(key);

        ManuallyProvidedKeyVerifier.ManuallyProvidedKeyJGitHostKeyVerifier jGitHostKeyVerifier =
                (ManuallyProvidedKeyVerifier.ManuallyProvidedKeyJGitHostKeyVerifier)
                        verifier.forJGit(StreamBuildListener.fromStdout());
        Path tempKnownHosts = Files.createTempFile("known_hosts", "");
        Files.writeString(tempKnownHosts, key + System.lineSeparator());
        KnownHostsTestUtil.connectToHost(
                        "github.com", 22, tempKnownHosts.toFile(), jGitHostKeyVerifier, "ecdsa-sha2-nistp256", s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();
    }

    @Test
    void testGetVerifyHostKeyOption() throws Exception {
        if (isWindows()) {
            return; // Skip test without generating a Maven surefire warning
        }
        Path tempFile = File.createTempFile("junit", null, testFolder).toPath();
        String actual = new ManuallyProvidedKeyVerifier(hostKey)
                .forCliGit(TaskListener.NULL)
                .getVerifyHostKeyOption(tempFile);
        assertThat(
                actual,
                is("-o StrictHostKeyChecking=yes  -o UserKnownHostsFile=\\\"\"\"" + tempFile.toAbsolutePath()
                        + "\\\"\"\""));
        assertThat(Files.readAllLines(tempFile), is(Collections.singletonList(hostKey)));
    }

    @Test
    void testGetVerifyHostKeyOptionOnWindows() throws Exception {
        if (!isWindows()) {
            return; // Skip test without generating a Maven surefire warning
        }
        Path tempFile = File.createTempFile("junit", null, testFolder).toPath();
        String actual = new ManuallyProvidedKeyVerifier(hostKey)
                .forCliGit(TaskListener.NULL)
                .getVerifyHostKeyOption(tempFile);
        assertThat(actual, is("-o StrictHostKeyChecking=yes  -o UserKnownHostsFile=" + tempFile.toAbsolutePath()));
        assertThat(Files.readAllLines(tempFile), is(Collections.singletonList(hostKey)));
    }

    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
