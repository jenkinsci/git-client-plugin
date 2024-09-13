package org.jenkinsci.plugins.gitclient.verifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.jenkinsci.plugins.gitclient.verifier.KnownHostsTestUtil.isKubernetesCI;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AcceptFirstConnectionVerifierTest {

    private static final String FILE_CONTENT =
            """
            |1|4MiAohNAs5mYhPnYkpnOUWXmMTA=|iKR8xF3kCEdmSch/QtdXfdjWMCo=\
             ssh-ed25519\
             AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl\
            """;

    private static final String KEY_ecdsa_sha2_nistp256 =
            """
            |1|owDOW+8aankl2aFSPKPIXsIf31E=|lGZ9BEWUfa9HoQteyYE5wIqHJdo=,|1|eGv/ezgtZ9YMw7OHcykKKOvAINk=|3lpkF7XiveRl/D7XvTOMc3ra2kU=\
             ecdsa-sha2-nistp256\
             AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=\
            """;

    @Rule
    public TemporaryFolder testFolder =
            TemporaryFolder.builder().assureDeletion().build();

    private final KnownHostsTestUtil knownHostsTestUtil = new KnownHostsTestUtil(testFolder);

    @Test
    public void testVerifyHostKeyOption() throws IOException {
        assertThat(
                new AcceptFirstConnectionVerifier().forCliGit(TaskListener.NULL).getVerifyHostKeyOption(null),
                is("-o StrictHostKeyChecking=accept-new -o HashKnownHosts=yes"));
    }

    @Test
    public void testVerifyServerHostKeyWhenFirstConnection() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        File file = new File(testFolder.getRoot() + "path/to/file");
        AcceptFirstConnectionVerifier acceptFirstConnectionVerifier = spy(new AcceptFirstConnectionVerifier());
        when(acceptFirstConnectionVerifier.getKnownHostsFile()).thenReturn(file);

        KnownHostsTestUtil.connectToHost(
                        "github.com",
                        22,
                        file,
                        acceptFirstConnectionVerifier.forJGit(StreamBuildListener.fromStdout()),
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();
        assertThat(file, is(anExistingFile()));
        assertThat(
                Files.readAllLines(file.toPath()),
                hasItem(containsString(KEY_ecdsa_sha2_nistp256.substring(KEY_ecdsa_sha2_nistp256.indexOf(" ")))));
    }

    @Test
    public void testVerifyServerHostKeyWhenSecondConnectionWithEqualKeys() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
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
    public void testVerifyServerHostKeyWhenHostnameWithoutPort() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
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
    public void testVerifyServerHostKeyWhenSecondConnectionWhenNotDefaultAlgorithm() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        String fileContent =
                """
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
    @Ignore("FIXME not sure what is the test here")
    public void testVerifyServerHostKeyWhenSecondConnectionWithNonEqualKeys() throws Exception {
        String fileContent =
                """
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
    public void testVerifyServerHostKeyWhenConnectionWithAnotherHost() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        String bitbucketFileContent =
                """
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
                        s -> {
                            assertThat(s.isOpen(), is(true));
                            Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> s.getServerKey() != null);
                            assertThat(KnownHostsTestUtil.checkKeys(s), is(true));
                            return true;
                        })
                .close();
        List<String> actual = Files.readAllLines(fakeKnownHosts.toPath());
        assertThat(actual, hasItem(bitbucketFileContent));
        assertThat(
                actual,
                hasItem(containsString(KEY_ecdsa_sha2_nistp256.substring(KEY_ecdsa_sha2_nistp256.indexOf(" ")))));
    }

    @Test
    public void testVerifyServerHostKeyWhenHostnamePortProvided() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        String fileContent =
                """
                |1|6uMj3M7sLgZpn54vQbGqgPNTCVM=|OkV9Lu9REJZR5QCVrITAIY34I1M=\
                 ssh-ed25519\
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
