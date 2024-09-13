package org.jenkinsci.plugins.gitclient.verifier;

import static org.eclipse.jgit.transport.SshSessionFactory.getLocalUserName;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.util.ReflectionUtils;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.ConfigFileHostEntryResolver;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSessionCreator;
import org.apache.sshd.common.compression.BuiltinCompressions;
import org.apache.sshd.common.config.keys.OpenSshCertificate;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.eclipse.jgit.internal.transport.sshd.JGitClientSession;
import org.eclipse.jgit.internal.transport.sshd.JGitServerKeyVerifier;
import org.eclipse.jgit.internal.transport.sshd.JGitSshClient;
import org.eclipse.jgit.transport.sshd.IdentityPasswordProvider;
import org.junit.rules.TemporaryFolder;

public class KnownHostsTestUtil {

    private final TemporaryFolder testFolder;

    public KnownHostsTestUtil(TemporaryFolder testFolder) {
        this.testFolder = testFolder;
    }

    public File createFakeSSHDir(String dir) throws IOException {
        // Create a fake directory for use with a known_hosts file
        return testFolder.newFolder(dir);
    }

    public File createFakeKnownHosts(String dir, String name) throws IOException {
        // Create fake known hosts file
        File fakeSSHDir = createFakeSSHDir(dir);
        return new File(fakeSSHDir, name);
    }

    public File createFakeKnownHosts(String dir, String name, String fileContent) throws IOException {
        File fakeKnownHosts = createFakeKnownHosts(dir, name);
        byte[] fakeKnownHostsBytes = fileContent.getBytes(StandardCharsets.UTF_8);
        Files.write(fakeKnownHosts.toPath(), fakeKnownHostsBytes);
        return fakeKnownHosts;
    }

    public File createFakeKnownHosts(String fileContent) throws IOException {
        File fakeKnownHosts = createFakeKnownHosts("fake.ssh", "known_hosts_fake");
        byte[] fakeKnownHostsBytes = fileContent.getBytes(StandardCharsets.UTF_8);
        Files.write(fakeKnownHosts.toPath(), fakeKnownHostsBytes);
        return fakeKnownHosts;
    }

    public List<String> getKnownHostsContent(File file) throws IOException {
        return Files.readAllLines(file.toPath());
    }

    protected static JGitClientSession connectToHost(
            String host,
            int port,
            File knownHostsFile,
            AbstractJGitHostKeyVerifier verifier,
            Predicate<JGitClientSession> asserts)
            throws IOException {

        try (SshClient client = ClientBuilder.builder()
                .factory(JGitSshClient::new)
                .hostConfigEntryResolver(new ConfigFileHostEntryResolver(Path.of("src/test/resources/ssh_config")))
                .serverKeyVerifier(new JGitServerKeyVerifier(verifier.getServerKeyDatabase()))
                .compressionFactories(new ArrayList<>(BuiltinCompressions.VALUES))
                .build()) {
            client.start();
            // just to avoid NPE
            JGitSshClient jgitClient = (JGitSshClient) client;
            jgitClient.setKeyPasswordProviderFactory(() -> new IdentityPasswordProvider(null));

            ConnectFuture connectFuture = client.connect(getLocalUserName(), host, port);
            JGitClientSession s = (JGitClientSession)
                    connectFuture.verify(Duration.ofMillis(3330000)).getClientSession();
            // make a simple call to force keys exchange
            Method method =
                    ReflectionUtils.findMethod(s.getClass(), "getServices"); // sendKexInit //"doKexNegotiation");
            assert method != null;
            method.setAccessible(true);
            Object response = ReflectionUtils.invokeMethod(method, s);
            assertThat(asserts.test(s), is(true));
            return s;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected static boolean checkKeys(JGitClientSession s) {
        ServerKeyVerifier serverKeyVerifier =
                Objects.requireNonNull(s.getServerKeyVerifier(), "No server key verifier");
        IoSession networkSession = s.getIoSession();
        SocketAddress remoteAddress = networkSession.getRemoteAddress();
        PublicKey serverKey = Objects.requireNonNull(s.getServerKey(), "No server key to verify");
        SshdSocketAddress targetServerAddress = s.getAttribute(ClientSessionCreator.TARGET_SERVER);
        if (targetServerAddress != null) {
            remoteAddress = targetServerAddress.toInetSocketAddress();
        }

        boolean verified = false;
        if (serverKey instanceof OpenSshCertificate certificate) {
            // check if we trust the CA
            verified = serverKeyVerifier.verifyServerKey(s, remoteAddress, certificate.getCaPubKey());

            if (!verified) {
                // fallback to actual public host key
                serverKey = certificate.getCertPubKey();
            }
        }

        if (!verified) {
            verified = serverKeyVerifier.verifyServerKey(s, remoteAddress, serverKey);
        }

        return verified;
    }

    /* Return true if running on a Kubernetes pod on ci.jenkins.io */
    public static boolean isKubernetesCI() {
        return false;
        //        String kubernetesPort = System.getenv("KUBERNETES_PORT");
        //        String buildURL = System.getenv("BUILD_URL");
        //        return kubernetesPort != null
        //                && !kubernetesPort.isEmpty()
        //                && buildURL != null
        //                && buildURL.startsWith("https://ci.jenkins.io/");
    }
}
