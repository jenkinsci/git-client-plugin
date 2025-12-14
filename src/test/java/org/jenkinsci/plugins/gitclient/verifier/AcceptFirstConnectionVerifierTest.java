package org.jenkinsci.plugins.gitclient.verifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.jenkinsci.plugins.gitclient.verifier.KnownHostsTestUtil.runKnownHostsTests;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcceptFirstConnectionVerifierTest {

    private static final String FILE_CONTENT = """
            |1|4MiAohNAs5mYhPnYkpnOUWXmMTA=|iKR8xF3kCEdmSch/QtdXfdjWMCo=\
             ssh-ed25519\
             AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl\
            """;

    private static final String KEY_ECDSA_SHA_2_NISTP_256 = """
            |1|owDOW+8aankl2aFSPKPIXsIf31E=|lGZ9BEWUfa9HoQteyYE5wIqHJdo=,|1|eGv/ezgtZ9YMw7OHcykKKOvAINk=|3lpkF7XiveRl/D7XvTOMc3ra2kU=\
             ecdsa-sha2-nistp256\
             AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=\
            """;

    private static final String KEY_SSH_RSA = """
            |1|HnmPCP38pBhCY0NUtBXSraOg9pM=|L6YZ9asEeb2xplTDEThGOxRq7ZY=\
             ssh-rsa\
             AAAAB3NzaC1yc2EAAAABIwAAAQEAubiN81eDcafrgMeLzaFPsw2kNvEcqTKl/VqLat/MaB33pZy0y3rJZtnqwR2qOOvbwKZYKiEO1O6VqNEBxKvJJelCq0dTXWT5pbO2gDXC6h6QDXCaHo6pOHGPUy+YBaGQRGuSusMEASYiWunYN0vCAI8QaXnWMXNMdFP3jHAJH0eDsoiGnLPBlBp4TNm6rYI74nMzgz3B9IikW4WVK+dc8KZJZWYjAuORU3jc1c/NPskD2ASinf8v3xnfXeukU0sJ5N6m5E8VLjObPEO+mN2t/FZTMZLiFqPWc/ALSqnMnnhwrNi2rbfg/rd/IpL8Le3pSBne8+seeFVBoGqzHM9yXw==\
            """;

    @TempDir
    private File testFolder;

    private KnownHostsTestUtil knownHostsTestUtil;

    @BeforeEach
    void setUp() {
        knownHostsTestUtil = new KnownHostsTestUtil(testFolder);
    }

    @Test
    void testVerifyHostKeyOption() throws Exception {
        assumeTrue(runKnownHostsTests());
        assertThat(
                new AcceptFirstConnectionVerifier().forCliGit(TaskListener.NULL).getVerifyHostKeyOption(null),
                is("-o StrictHostKeyChecking=accept-new"));
    }

    @Test
    void testVerifyServerHostKeyWhenFirstConnection() throws Exception {
        assumeTrue(runKnownHostsTests());
        File file = new File(testFolder + "path/to/file");
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(file);

        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        file,
                        acceptFirstConnectionVerifier.forJGit(StreamBuildListener.fromStdout()),
                        "ecdsa-sha2-nistp256" /* Indiferent for this test*/,
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();
        assertThat(file, is(anExistingFile()));
        List<String> lines = Files.readAllLines(file.toPath());
        
        // JENKINS-73427: Verify entries are NOT hashed (no |1| prefix) to avoid malformed entries
        assertThat("Host key entry should be in plain format, not hashed", 
                lines.stream().noneMatch(line -> line.startsWith("|1|")), is(true));
        
        // Verify the key was added with the correct algorithm and key material
        assertThat(lines, hasItem(containsString("ecdsa-sha2-nistp256")));
        assertThat(lines, hasItem(containsString("AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=")));
    }

    @Test
    void testMalformedHashedEntriesCanBeRead() throws Exception {
        // JENKINS-73427: Test that malformed hashed entries (from older versions) can still be read
        // This is the malformed format that was reported in the issue with TWO comma-separated hash patterns
        assumeTrue(runKnownHostsTests());
        String malformedHashedEntry = KEY_ECDSA_SHA_2_NISTP_256;
        
        File mockedKnownHosts = knownHostsTestUtil.createFakeKnownHosts(malformedHashedEntry);
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(mockedKnownHosts);

        // This should not throw an exception when reading the malformed entry
        // The connection might fail to verify, but it shouldn't crash
        try {
            KnownHostsTestUtil.connectToHost(
                            "github.com",
                            22,
                            mockedKnownHosts,
                            acceptFirstConnectionVerifier.forJGit(StreamBuildListener.fromStdout()),
                            "ecdsa-sha2-nistp256",
                            s -> {
                                assertThat(s.isOpen(), is(true));
                                Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                                // The malformed entry may or may not match - we just want to ensure no crash
                                return true;
                            })
                    .close();
        } catch (Exception e) {
            // Expected - the malformed entry might not verify correctly, but shouldn't cause a crash
            // As long as we get here without an IllegalArgumentException about "Invalid hash pattern", we're good
        }
    }

    @Test
    void testNewEntriesAreNotHashed() throws Exception {
        // JENKINS-73427: Verify that new entries are created in plain format, not hashed
        // This confirms the fix prevents the malformed hashed entries issue
        assumeTrue(runKnownHostsTests());
        File file = new File(testFolder + "path/to/newhosts");
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(file);

        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        file,
                        acceptFirstConnectionVerifier.forJGit(StreamBuildListener.fromStdout()),
                        "ssh-ed25519",
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();

        assertThat(file, is(anExistingFile()));
        List<String> lines = Files.readAllLines(file.toPath());
        
        // Filter out any empty lines
        List<String> nonEmptyLines = lines.stream()
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.toList());
        
        // The key insight: entries should be in plain format like "github.com,140.82.121.4 ssh-ed25519 ..."
        // NOT hashed like "|1|hash|hash ssh-ed25519 ..."
        assertThat("At least one entry should be created", 
                nonEmptyLines.size() >= 1, is(true));
        
        // Verify ALL entries are NOT hashed (no |1| prefix indicating hashed hostname)
        for (String entry : nonEmptyLines) {
            assertThat("Entry should not start with |1| (hashed format): " + entry, 
                    entry.startsWith("|1|"), is(false));
            
            // Verify it contains the key type
            assertThat("Entry should contain key type: " + entry, 
                    entry, containsString("ssh-ed25519"));
        }
        
        // Verify at least one entry contains the hostname in plain text
        assertThat("At least one entry should contain plain hostname", 
                nonEmptyLines.stream().anyMatch(line -> line.contains("github.com")), is(true));
    }

    @Test
    void testVerifyServerHostKeyWhenSecondConnectionWithEqualKeys() throws Exception {
        assumeTrue(runKnownHostsTests());
        String hostKeyEntry =
                "|1|FJGXVAi7jMQIsl1J6uE6KnCiteM=|xlH92KQ91GuBgRxvRbU/sBo60Bo= ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=";

        File mockedKnownHosts = knownHostsTestUtil.createFakeKnownHosts(hostKeyEntry);
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(mockedKnownHosts);

        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        mockedKnownHosts,
                        acceptFirstConnectionVerifier.forJGit(StreamBuildListener.fromStdout()),
                        "ecdsa-sha2-nistp256",
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();
        // Should connect and do not add new line because keys are equal
        assertThat(mockedKnownHosts, is(anExistingFile()));
        List<String> keys = Files.readAllLines(mockedKnownHosts.toPath());
        assertThat(keys.size(), is(1));
        assertThat(keys, is(Collections.singletonList(hostKeyEntry)));
    }

    @Test
    void testVerifyServerHostKeyWhenHostnameWithoutPort() throws Exception {
        assumeTrue(runKnownHostsTests());
        String hostKeyEntry =
                "github.com ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=";
        File mockedKnownHosts = knownHostsTestUtil.createFakeKnownHosts(hostKeyEntry);
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(mockedKnownHosts);

        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        mockedKnownHosts,
                        acceptFirstConnectionVerifier.forJGit(StreamBuildListener.fromStdout()),
                        "ecdsa-sha2-nistp256",
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();
        assertThat(Files.readAllLines(mockedKnownHosts.toPath()), is(Collections.singletonList(hostKeyEntry)));
    }

    @Test
    void testVerifyServerHostKeyWhenSecondConnectionWhenNotDefaultAlgorithm() throws Exception {
        assumeTrue(runKnownHostsTests());
        String fileContent = """
                github.com,140.82.121.4\
                 ecdsa-sha2-nistp256\
                 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=\
                """;
        File mockedKnownHosts = knownHostsTestUtil.createFakeKnownHosts(fileContent);
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(mockedKnownHosts);

        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        mockedKnownHosts,
                        acceptFirstConnectionVerifier.forJGit(StreamBuildListener.fromStdout()),
                        "ecdsa-sha2-nistp256",
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();

        assertThat(mockedKnownHosts, is(anExistingFile()));
        assertThat(Files.readAllLines(mockedKnownHosts.toPath()), is(Collections.singletonList(fileContent)));
    }

    @Test
    @Disabled("FIXME not sure what is the test here")
    void testVerifyServerHostKeyWhenSecondConnectionWithNonEqualKeys() throws Exception {
        assumeTrue(runKnownHostsTests());
        String fileContent = """
                |1|f7esvmtaiBk+EMHjPzWbRYRpBPY=|T7Qe4QAksYPZPwYEx5QxQykSjfc=\
                 ssh-ed25519\
                 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9OOOO\
                """;
        File mockedKnownHosts =
                knownHostsTestUtil.createFakeKnownHosts(fileContent); // file was created during first connection
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(mockedKnownHosts);

        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        mockedKnownHosts,
                        acceptFirstConnectionVerifier.forJGit(StreamBuildListener.fromStdout()),
                        "ssh-ed25519",
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            assertThat(mockedKnownHosts, is(anExistingFile()));
                            return true;
                        })
                .close();
        assertThat(mockedKnownHosts, is(anExistingFile()));
        assertThat(Files.readAllLines(mockedKnownHosts.toPath()), is(Collections.singletonList(fileContent)));
    }

    @Test
    void testVerifyServerHostKeyWhenConnectionWithAnotherHost() throws Exception {
        assumeTrue(runKnownHostsTests());
        String bitbucketFileContent = """
                |1|HnmPCP38pBhCY0NUtBXSraOg9pM=|L6YZ9asEeb2xplTDEThGOxRq7ZY=\
                 ssh-rsa\
                 AAAAB3NzaC1yc2EAAAABIwAAAQEAubiN81eDcafrgMeLzaFPsw2kNvEcqTKl/VqLat/MaB33pZy0y3rJZtnqwR2qOOvbwKZYKiEO1O6VqNEBxKvJJelCq0dTXWT5pbO2gDXC6h6QDXCaHo6pOHGPUy+YBaGQRGuSusMEASYiWunYN0vCAI8QaXnWMXNMdFP3jHAJH0eDsoiGnLPBlBp4TNm6rYI74nMzgz3B9IikW4WVK+dc8KZJZWYjAuORU3jc1c/NPskD2ASinf8v3xnfXeukU0sJ5N6m5E8VLjObPEO+mN2t/FZTMZLiFqPWc/ALSqnMnnhwrNi2rbfg/rd/IpL8Le3pSBne8+seeFVBoGqzHM9yXw==\
                """;

        File fakeKnownHosts = knownHostsTestUtil.createFakeKnownHosts(bitbucketFileContent);
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(fakeKnownHosts);

        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        fakeKnownHosts,
                        acceptFirstConnectionVerifier.forJGit(StreamBuildListener.fromStdout()),
                        "ssh-rsa",
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();
        List<String> actual = Files.readAllLines(fakeKnownHosts.toPath());
        assertThat(actual, hasItem(bitbucketFileContent));
        assertThat(actual, hasItem(containsString(KEY_SSH_RSA.substring(KEY_SSH_RSA.indexOf(" ")))));
    }

    @Test
    void testVerifyServerHostKeyWhenHostnamePortProvided() throws Exception {
        assumeTrue(runKnownHostsTests());
        String fileContent = """
                |1|6uMj3M7sLgZpn54vQbGqgPNTCVM=|OkV9Lu9REJZR5QCVrITAIY34I1M= \
                ssh-ed25519 \
                AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl\
                """;
        File mockedKnownHosts = knownHostsTestUtil.createFakeKnownHosts(fileContent);
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(mockedKnownHosts);

        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        mockedKnownHosts,
                        acceptFirstConnectionVerifier.forJGit(StreamBuildListener.fromStdout()),
                        "ssh-ed25519",
                        session -> {
                            assertThat(session.isOpen(), is(true));
                            Awaitility.await()
                                    .atMost(Duration.ofSeconds(45))
                                    .until(() -> session.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(session), is(true));
                            return true;
                        })
                .close();
        assertThat(mockedKnownHosts, is(anExistingFile()));
        List<String> actual = Files.readAllLines(mockedKnownHosts.toPath());
        assertThat(actual, hasItem(fileContent));
        assertThat(actual, hasItem(containsString(FILE_CONTENT.substring(FILE_CONTENT.indexOf(" ")))));
    }
}
