package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import org.jenkinsci.plugins.gitclient.trilead.JGitConnection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class AcceptFirstConnectionVerifierTest {

    private static final String FILE_CONTENT = "|1|4MiAohNAs5mYhPnYkpnOUWXmMTA=|iKR8xF3kCEdmSch/QtdXfdjWMCo="
            + " ssh-ed25519"
            + " AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";

    @Rule
    public TemporaryFolder testFolder = TemporaryFolder.builder().assureDeletion().build();
    private final KnownHostsTestUtil knownHostsTestUtil = new KnownHostsTestUtil(testFolder);

    @Test
    public void testVerifyHostKeyOption() throws IOException {
        assertThat(new AcceptFirstConnectionVerifier().forCliGit(TaskListener.NULL).getVerifyHostKeyOption(null), is("-o StrictHostKeyChecking=accept-new -o HashKnownHosts=yes"));
    }

    @Test
    public void testVerifyServerHostKeyWhenFirstConnection() {
        File file = new File(testFolder.getRoot() + "path/to/file");
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(file);
        AbstractJGitHostKeyVerifier verifier = acceptFirstConnectionVerifier.forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        try {
            jGitConnection.connect(verifier);
            assertTrue(file.exists());
            assertThat(Files.readAllLines(file.toPath()), hasItem(containsString(FILE_CONTENT.substring(FILE_CONTENT.indexOf(" ")))));
        } catch (IOException e) {
            fail("Should not fail because first connection and create a file");
        }
    }

    @Test
    public void testVerifyServerHostKeyWhenSecondConnectionWithEqualKeys() throws Exception {
        // |1|I9eFW1PcZ6UvKPt6iHmYwTXTo54=|PyasyFX5Az4w9co6JTn7rHkeFII= is github.com:22
        String hostKeyEntry = "|1|I9eFW1PcZ6UvKPt6iHmYwTXTo54=|PyasyFX5Az4w9co6JTn7rHkeFII= ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";
        File mockedKnownHosts = knownHostsTestUtil.createFakeKnownHosts(hostKeyEntry);
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(mockedKnownHosts);
        AbstractJGitHostKeyVerifier verifier = acceptFirstConnectionVerifier.forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        try {
            jGitConnection.connect(verifier);
            assertThat(Files.readAllLines(mockedKnownHosts.toPath()), is(Collections.singletonList(hostKeyEntry)));
        } catch (IOException e) {
            fail("Should connect and do not add new line because keys are equal");
        }
    }

    @Test
    public void testVerifyServerHostKeyWhenHostnameWithoutPort() throws Exception {
        String hostKeyEntry = "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";
        File mockedKnownHosts = knownHostsTestUtil.createFakeKnownHosts(hostKeyEntry);
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(mockedKnownHosts);
        AbstractJGitHostKeyVerifier verifier = acceptFirstConnectionVerifier.forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        try {
            jGitConnection.connect(verifier);
            assertThat(Files.readAllLines(mockedKnownHosts.toPath()), is(Collections.singletonList(hostKeyEntry)));
        } catch (IOException e) {
            fail("Should connect and do not add new line because keys are equal");
        }
    }

    @Test
    public void testVerifyServerHostKeyWhenSecondConnectionWhenNotDefaultAlgorithm() throws Exception {
        String fileContent = "github.com,140.82.121.4"
                + " ecdsa-sha2-nistp256"
                + " AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=";
        File mockedKnownHosts = knownHostsTestUtil.createFakeKnownHosts(fileContent);
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(mockedKnownHosts);
        AbstractJGitHostKeyVerifier verifier = acceptFirstConnectionVerifier.forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        try {
            jGitConnection.connect(verifier);
            assertThat(Files.readAllLines(mockedKnownHosts.toPath()), is(Collections.singletonList(fileContent)));
        } catch (IOException e) {
            fail("Should connect and do not add new line because keys are equal");
        }
    }

    @Test
    public void testVerifyServerHostKeyWhenSecondConnectionWithNonEqualKeys() throws Exception {
        String fileContent = "|1|f7esvmtaiBk+EMHjPzWbRYRpBPY=|T7Qe4QAksYPZPwYEx5QxQykSjfc=" //github.com:22
                + " ssh-ed25519"
                + " AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9OOOO";
        File mockedKnownHosts = knownHostsTestUtil.createFakeKnownHosts(fileContent); // file was created during first connection
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(mockedKnownHosts);
        AbstractJGitHostKeyVerifier verifier = acceptFirstConnectionVerifier.forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        try {
            jGitConnection.connect(verifier);
            fail("Should fail because hostkey is broken for 'github.com:22'");
        } catch (IOException e) {
            assertThat(e.getMessage(), is("There was a problem while connecting to github.com:22"));
        }
    }

    @Test
    public void testVerifyServerHostKeyWhenConnectionWithAnotherHost() throws Exception {
        String bitbucketFileContent = "|1|HnmPCP38pBhCY0NUtBXSraOg9pM=|L6YZ9asEeb2xplTDEThGOxRq7ZY="
                + " ssh-rsa"
                + " AAAAB3NzaC1yc2EAAAABIwAAAQEAubiN81eDcafrgMeLzaFPsw2kNvEcqTKl/VqLat/MaB33pZy0y3rJZtnqwR2qOOvbwKZYKiEO1O6VqNEBxKvJJelCq0dTXWT5pbO2gDXC6h6QDXCaHo6pOHGPUy+YBaGQRGuSusMEASYiWunYN0vCAI8QaXnWMXNMdFP3jHAJH0eDsoiGnLPBlBp4TNm6rYI74nMzgz3B9IikW4WVK+dc8KZJZWYjAuORU3jc1c/NPskD2ASinf8v3xnfXeukU0sJ5N6m5E8VLjObPEO+mN2t/FZTMZLiFqPWc/ALSqnMnnhwrNi2rbfg/rd/IpL8Le3pSBne8+seeFVBoGqzHM9yXw==";

        File fakeKnownHosts = knownHostsTestUtil.createFakeKnownHosts(bitbucketFileContent);
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(fakeKnownHosts);
        AbstractJGitHostKeyVerifier verifier = acceptFirstConnectionVerifier.forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        try {
            jGitConnection.connect(verifier);
            List<String> actual = Files.readAllLines(fakeKnownHosts.toPath());
            assertThat(actual, hasItem(bitbucketFileContent));
            assertThat(actual, hasItem(containsString(FILE_CONTENT.substring(FILE_CONTENT.indexOf(" ")))));
        } catch (IOException e) {
            fail("Should connect and add new line because a new key");
        }
    }

    @Test
    public void testVerifyServerHostKeyWhenHostnamePortProvided() throws Exception {
        String fileContent = "|1|6uMj3M7sLgZpn54vQbGqgPNTCVM=|OkV9Lu9REJZR5QCVrITAIY34I1M=" //github.com:59666
                + " ssh-ed25519"
                + " AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";
        File mockedKnownHosts = knownHostsTestUtil.createFakeKnownHosts(fileContent);
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(mockedKnownHosts);
        AbstractJGitHostKeyVerifier verifier = acceptFirstConnectionVerifier.forJGit(TaskListener.NULL);
        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        try {
            jGitConnection.connect(verifier);
            List<String> actual = Files.readAllLines(mockedKnownHosts.toPath());
            assertThat(actual, hasItem(fileContent));
            assertThat(actual, hasItem(containsString(FILE_CONTENT.substring(FILE_CONTENT.indexOf(" ")))));
        } catch (IOException e) {
            fail("Should connect and add new line because a new key");
        }
    }

}
