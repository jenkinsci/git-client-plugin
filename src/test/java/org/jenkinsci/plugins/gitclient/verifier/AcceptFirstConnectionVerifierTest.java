package org.jenkinsci.plugins.gitclient.verifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.io.FileMatchers.anExistingFile;
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
import org.jenkinsci.plugins.gitclient.JGitAPIImpl;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AcceptFirstConnectionVerifierTest {

    private static final String FILE_CONTENT = "|1|4MiAohNAs5mYhPnYkpnOUWXmMTA=|iKR8xF3kCEdmSch/QtdXfdjWMCo="
            + " ssh-ed25519"
            + " AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";

    private static final String KEY_ecdsa_sha2_nistp256 =
            "|1|owDOW+8aankl2aFSPKPIXsIf31E=|lGZ9BEWUfa9HoQteyYE5wIqHJdo=,|1|eGv/ezgtZ9YMw7OHcykKKOvAINk=|3lpkF7XiveRl/D7XvTOMc3ra2kU="
                    + " ecdsa-sha2-nistp256"
                    + " AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=";

    @Rule
    public TemporaryFolder testFolder =
            TemporaryFolder.builder().assureDeletion().build();

    private final KnownHostsTestUtil knownHostsTestUtil = new KnownHostsTestUtil(testFolder);

    @BeforeClass
    public static void setHostKeyAlgo() {
        System.setProperty(JGitAPIImpl.SSH_CONFIG_PATH, new File("src/test/resources/ssh_config").getAbsolutePath());
    }

    @AfterClass
    public static void unsetHostKeyAlgo() {
        System.clearProperty(JGitAPIImpl.SSH_CONFIG_PATH);
    }

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
                            // Should have first connection and create a file
                            assertThat(file, is(anExistingFile()));
                            try {
                                assertThat(
                                        Files.readAllLines(file.toPath()),
                                        hasItem(containsString(KEY_ecdsa_sha2_nistp256.substring(
                                                KEY_ecdsa_sha2_nistp256.indexOf(" ")))));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return true;
                        })
                .close();
    }

    @Test
    public void testVerifyServerHostKeyWhenSecondConnectionWithEqualKeys() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        // String hostKeyEntry =
        //        "|1|WIo7bO1jHBJNeDU+fr2jilINo7I=|la2mWYq2yebKmyoL1acdWfRYr2w= ssh-ed25519
        // AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";
        // |1|I9eFW1PcZ6UvKPt6iHmYwTXTo54=|PyasyFX5Az4w9co6JTn7rHkeFII= is github.com:22
        // String hostKeyEntry =
        //        "|1|I9eFW1PcZ6UvKPt6iHmYwTXTo54=|PyasyFX5Az4w9co6JTn7rHkeFII= ssh-ed25519
        // AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";
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
                            // Should have first connection and create a file
                            assertThat(mockedKnownHosts, is(anExistingFile()));
                            try {
                                // Should connect and do not add new line because keys are equal
                                assertThat(mockedKnownHosts, is(anExistingFile()));
                                List<String> keys = Files.readAllLines(mockedKnownHosts.toPath());
                                assertThat(keys.size(), is(1));
                                assertThat(keys, is(Collections.singletonList(hostKeyEntry)));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return true;
                        })
                .close();
    }

    @Test
    @Ignore("cannot verify a non hash host while we store hash host")
    public void testVerifyServerHostKeyWhenHostnameWithoutPort() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        String hostKeyEntry =
                "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";
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
                            // Should have first connection and create a file
                            assertThat(mockedKnownHosts, is(anExistingFile()));
                            try {
                                assertThat(mockedKnownHosts, is(anExistingFile()));
                                assertThat(
                                        Files.readAllLines(mockedKnownHosts.toPath()),
                                        is(Collections.singletonList(hostKeyEntry)));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return true;
                        })
                .close();
    }

    @Test
    public void testVerifyServerHostKeyWhenSecondConnectionWhenNotDefaultAlgorithm() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        String fileContent = "github.com,140.82.121.4"
                + " ecdsa-sha2-nistp256"
                + " AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=";
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
                            // Should have first connection and create a file
                            assertThat(mockedKnownHosts, is(anExistingFile()));
                            try {
                                assertThat(mockedKnownHosts, is(anExistingFile()));
                                assertThat(
                                        Files.readAllLines(mockedKnownHosts.toPath()),
                                        is(Collections.singletonList(fileContent)));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return true;
                        })
                .close();
    }

    @Test
    @Ignore("FIXME not sure what is the test here")
    public void testVerifyServerHostKeyWhenSecondConnectionWithNonEqualKeys() throws Exception {
        String fileContent = "|1|f7esvmtaiBk+EMHjPzWbRYRpBPY=|T7Qe4QAksYPZPwYEx5QxQykSjfc=" // github.com:22
                + " ssh-ed25519"
                + " AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9OOOO";
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
                            // Should have first connection and create a file
                            assertThat(mockedKnownHosts, is(anExistingFile()));
                            try {
                                assertThat(mockedKnownHosts, is(anExistingFile()));
                                assertThat(
                                        Files.readAllLines(mockedKnownHosts.toPath()),
                                        is(Collections.singletonList(fileContent)));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return true;
                        })
                .close();

        AbstractJGitHostKeyVerifier verifier = acceptFirstConnectionVerifier.forJGit(TaskListener.NULL);
        //        JGitConnection jGitConnection = new JGitConnection("github.com", 22);

        // FIXME ol
        //        Exception exception = assertThrows(IOException.class, () -> {
        //            jGitConnection.connect(verifier);
        //        });
        //        assertThat(exception.getMessage(), containsString("There was a problem while connecting to
        // github.com:22"));

    }

    @Test
    public void testVerifyServerHostKeyWhenConnectionWithAnotherHost() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        String bitbucketFileContent = "|1|HnmPCP38pBhCY0NUtBXSraOg9pM=|L6YZ9asEeb2xplTDEThGOxRq7ZY="
                + " ssh-rsa"
                + " AAAAB3NzaC1yc2EAAAABIwAAAQEAubiN81eDcafrgMeLzaFPsw2kNvEcqTKl/VqLat/MaB33pZy0y3rJZtnqwR2qOOvbwKZYKiEO1O6VqNEBxKvJJelCq0dTXWT5pbO2gDXC6h6QDXCaHo6pOHGPUy+YBaGQRGuSusMEASYiWunYN0vCAI8QaXnWMXNMdFP3jHAJH0eDsoiGnLPBlBp4TNm6rYI74nMzgz3B9IikW4WVK+dc8KZJZWYjAuORU3jc1c/NPskD2ASinf8v3xnfXeukU0sJ5N6m5E8VLjObPEO+mN2t/FZTMZLiFqPWc/ALSqnMnnhwrNi2rbfg/rd/IpL8Le3pSBne8+seeFVBoGqzHM9yXw==";

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
                            // Should have first connection and create a file
                            assertThat(fakeKnownHosts, is(anExistingFile()));
                            try {
                                List<String> actual = Files.readAllLines(fakeKnownHosts.toPath());
                                assertThat(actual, hasItem(bitbucketFileContent));
                                assertThat(
                                        actual,
                                        hasItem(containsString(KEY_ecdsa_sha2_nistp256.substring(
                                                KEY_ecdsa_sha2_nistp256.indexOf(" ")))));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return true;
                        })
                .close();
    }

    @Test
    public void testVerifyServerHostKeyWhenHostnamePortProvided() throws Exception {
        if (isKubernetesCI()) {
            return; // Test fails with connection timeout on ci.jenkins.io kubernetes agents
        }
        String fileContent = "|1|6uMj3M7sLgZpn54vQbGqgPNTCVM=|OkV9Lu9REJZR5QCVrITAIY34I1M=" // github.com:59666
                + " ssh-ed25519"
                + " AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";
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
                            // Should have first connection and create a file
                            assertThat(mockedKnownHosts, is(anExistingFile()));
                            try {
                                List<String> actual = Files.readAllLines(mockedKnownHosts.toPath());
                                assertThat(actual, hasItem(fileContent));
                                assertThat(
                                        actual,
                                        hasItem(containsString(FILE_CONTENT.substring(FILE_CONTENT.indexOf(" ")))));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return true;
                        })
                .close();
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
