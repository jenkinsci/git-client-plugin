package org.jenkinsci.plugins.gitclient.verifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.jenkinsci.plugins.gitclient.verifier.KnownHostsTestUtil.isKubernetesCI;

import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import org.awaitility.Awaitility;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ManuallyProvidedKeyVerifierTest {

    // Create a temporary folder and assert folder deletion at end of tests
    @Rule
    public TemporaryFolder testFolder =
            TemporaryFolder.builder().assureDeletion().build();

    private String hostKey;

    @Before
    public void assignVerifier() {
        hostKey =
                "|1|7qEjynZk0IodegnbgoPEhWtdgA8=|bGs7a1ktbGWwPuZqqTbAazUAULM= ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=";
    }

    @Test
    public void connectWhenHostKeyProvidedForOtherHostNameThenShouldFail() throws Exception {
        HostKeyVerifierFactory verifier = new ManuallyProvidedKeyVerifier(hostKey);

        KnownHostsTestUtil.connectToHost(
                        "bitbucket.org",
                        22,
                        new File(testFolder.getRoot() + "/path/to/file/random"),
                        verifier.forJGit(StreamBuildListener.fromStdout()),
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(false));
                            return true;
                        })
                .close();
    }

    @Test
    public void connectWhenHostKeyProvidedThenShouldNotFail() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        ManuallyProvidedKeyVerifier verifier = new ManuallyProvidedKeyVerifier(hostKey);
        ManuallyProvidedKeyVerifier.ManuallyProvidedKeyJGitHostKeyVerifier jGitHostKeyVerifier =
                (ManuallyProvidedKeyVerifier.ManuallyProvidedKeyJGitHostKeyVerifier)
                        verifier.forJGit(StreamBuildListener.fromStdout());
        Path tempKnownHosts = Files.createTempFile("known_hosts", "");
        Files.write(tempKnownHosts, (hostKey + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        KnownHostsTestUtil.connectToHost("github.com", 22, tempKnownHosts.toFile(), jGitHostKeyVerifier, s -> {
                    assertThat(s.isOpen(), is(true));
                    Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                    // Should not fail because hostkey for 'github.com:22' was provided
                    assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                    return true;
                })
                .close();
    }

    @Test
    public void connectWhenWrongHostKeyProvidedThenShouldFail() throws Exception {
        String key = "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9OOOO";
        HostKeyVerifierFactory verifier = new ManuallyProvidedKeyVerifier(key);

        ManuallyProvidedKeyVerifier.ManuallyProvidedKeyJGitHostKeyVerifier jGitHostKeyVerifier =
                (ManuallyProvidedKeyVerifier.ManuallyProvidedKeyJGitHostKeyVerifier)
                        verifier.forJGit(StreamBuildListener.fromStdout());
        Path tempKnownHosts = Files.createTempFile("known_hosts", "");
        Files.write(tempKnownHosts, (key + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        KnownHostsTestUtil.connectToHost("github.com", 22, tempKnownHosts.toFile(), jGitHostKeyVerifier, s -> {
                    assertThat(s.isOpen(), is(true));
                    Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                    assertThat(KnownHostsTestUtil.checkKeys(s), is(false));
                    return true;
                })
                .close();
    }

    @Test
    public void connectWhenHostKeyProvidedWithPortThenShouldNotFail() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        String key =
                "github.com:22 ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=";
        HostKeyVerifierFactory verifier = new ManuallyProvidedKeyVerifier(key);

        ManuallyProvidedKeyVerifier.ManuallyProvidedKeyJGitHostKeyVerifier jGitHostKeyVerifier =
                (ManuallyProvidedKeyVerifier.ManuallyProvidedKeyJGitHostKeyVerifier)
                        verifier.forJGit(StreamBuildListener.fromStdout());
        Path tempKnownHosts = Files.createTempFile("known_hosts", "");
        Files.write(tempKnownHosts, (key + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        KnownHostsTestUtil.connectToHost("github.com", 22, tempKnownHosts.toFile(), jGitHostKeyVerifier, s -> {
                    assertThat(s.isOpen(), is(true));
                    Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                    assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                    return true;
                })
                .close();
    }

    @Test
    public void testGetVerifyHostKeyOption() throws IOException {
        if (isWindows()) {
            return; // Skip test without generating a Maven surefire warning
        }
        Path tempFile = testFolder.newFile().toPath();
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
    public void testGetVerifyHostKeyOptionOnWindows() throws IOException {
        if (!isWindows()) {
            return; // Skip test without generating a Maven surefire warning
        }
        Path tempFile = testFolder.newFile().toPath();
        String actual = new ManuallyProvidedKeyVerifier(hostKey)
                .forCliGit(TaskListener.NULL)
                .getVerifyHostKeyOption(tempFile);
        assertThat(actual, is("-o StrictHostKeyChecking=yes  -o UserKnownHostsFile=" + tempFile.toAbsolutePath() + ""));
        assertThat(Files.readAllLines(tempFile), is(Collections.singletonList(hostKey)));
    }

    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
