package org.jenkinsci.plugins.gitclient.verifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.jenkinsci.plugins.gitclient.trilead.JGitConnection;
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

    private AbstractJGitHostKeyVerifier verifier;
    private String hostKey;

    @Before
    public void assignVerifier() {
        hostKey = "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";
    }

    @Test
    public void connectWhenHostKeyProvidedForOtherHostNameThenShouldFail() {
        verifier = new ManuallyProvidedKeyVerifier(hostKey).forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("bitbucket.org", 22);

        // Should fail because hostkey for 'bitbucket.org:22' is not manually provided
        Exception exception = assertThrows(IOException.class, () -> {
            jGitConnection.connect(verifier);
        });
        assertThat(exception.getMessage(), containsString("There was a problem while connecting to bitbucket.org:22"));
    }

    @Test
    public void connectWhenHostKeyProvidedThenShouldNotFail() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        verifier = new ManuallyProvidedKeyVerifier(hostKey).forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        // Should not fail because hostkey for 'github.com:22' was provided
        jGitConnection.connect(verifier);
    }

    @Test
    public void connectWhenWrongHostKeyProvidedThenShouldFail() {
        verifier = new ManuallyProvidedKeyVerifier(
                        "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9OOOO")
                .forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        Exception exception = assertThrows(IOException.class, () -> {
            jGitConnection.connect(verifier);
        });
        assertThat(exception.getMessage(), containsString("There was a problem while connecting to github.com:22"));
    }

    @Test
    public void connectWhenHostKeyProvidedWithPortThenShouldNotFail() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        verifier = new ManuallyProvidedKeyVerifier(
                        "github.com:22 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl")
                .forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        // Should not fail because hostkey for 'github.com:22' was provided
        jGitConnection.connect(verifier);
    }

    @Test
    public void connectWhenProvidedHostnameWithPortHashedShouldNotFail() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        // |1|L95XQhkJWMDrDLdtkT1oH7hj2ec=|A2ocjuIDw2x+SOhTnRU3IGjqai0= is github.com:22
        verifier = new ManuallyProvidedKeyVerifier(
                        "|1|L95XQhkJWMDrDLdtkT1oH7hj2ec=|A2ocjuIDw2x+SOhTnRU3IGjqai0= ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=")
                .forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        // Should not fail because hostkey for 'github.com:22' was provided
        jGitConnection.connect(verifier);
    }

    @Test
    public void connectWhenProvidedHostnameWithoutPortHashedShouldNotFail() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        // |1|Sps9q6AJcYKtFor8T+uOUSdidVc=|liZf9T3FN9jJG2NPwUXK9b/YB+g= is github.com
        verifier = new ManuallyProvidedKeyVerifier(
                        "|1|Sps9q6AJcYKtFor8T+uOUSdidVc=|liZf9T3FN9jJG2NPwUXK9b/YB+g= ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=")
                .forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        // Should not fail because hostkey for 'github.com' was provided
        jGitConnection.connect(verifier);
    }

    @Test
    public void connectWhenHostKeyProvidedThenShouldFail() {
        verifier = new ManuallyProvidedKeyVerifier(
                        "github.com:33 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl")
                .forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        Exception exception = assertThrows(IOException.class, () -> {
            jGitConnection.connect(verifier);
        });
        assertThat(exception.getMessage(), containsString("There was a problem while connecting to github.com:22"));
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
