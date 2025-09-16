package org.jenkinsci.plugins.gitclient.verifier;

import static org.eclipse.jgit.transport.SshSessionFactory.getLocalUserName;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.util.ReflectionUtils;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
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

public class KnownHostsTestUtil {

    private final File testFolder;

    public KnownHostsTestUtil(File testFolder) {
        this.testFolder = testFolder;
    }

    public File createFakeSSHDir(String dir) throws Exception {
        // Create a fake directory for use with a known_hosts file
        return newFolder(testFolder, dir + "-" + System.nanoTime());
    }

    public File createFakeKnownHosts(String dir, String name) throws Exception {
        // Create fake known hosts file
        File fakeSSHDir = createFakeSSHDir(dir);
        return new File(fakeSSHDir, name);
    }

    public File createFakeKnownHosts(String dir, String name, String fileContent) throws Exception {
        File fakeKnownHosts = createFakeKnownHosts(dir, name);
        Files.writeString(fakeKnownHosts.toPath(), fileContent);
        return fakeKnownHosts;
    }

    public File createFakeKnownHosts(String fileContent) throws Exception {
        File fakeKnownHosts = createFakeKnownHosts("fake.ssh", "known_hosts_fake");
        Files.writeString(fakeKnownHosts.toPath(), fileContent);
        return fakeKnownHosts;
    }

    protected static JGitClientSession connectToHost(
            String host,
            int port,
            File knownHostsFile,
            AbstractJGitHostKeyVerifier verifier,
            String algorithm,
            Predicate<JGitClientSession> asserts) {

        try (SshClient client = ClientBuilder.builder()
                .factory(JGitSshClient::new)
                .hostConfigEntryResolver(
                        new ConfigFileHostEntryResolver(Path.of("src/test/resources/ssh_config-" + algorithm)))
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

    // Several different git providers with ssh access, use one randomly
    private static final String[] nonGitHubHosts = {
        // bitbucket.org blocks requests from ci.jenkins.io agents
        // "bitbucket.org",
        "git.assembla.com", "gitea.com", "gitlab.com", "vs-ssh.visualstudio.com", "ssh.dev.azure.com"
    };

    /* Return hostname of a non-GitHub ssh provider */
    public static String nonGitHubHost() {
        return nonGitHubHosts[ThreadLocalRandom.current().nextInt(nonGitHubHosts.length)];
    }

    private static final String JENKINS_URL =
            System.getenv("JENKINS_URL") != null ? System.getenv("JENKINS_URL") : "http://localhost:8080/";

    /* Return true if known hosts tests should be run in this context */
    public static boolean runKnownHostsTests() {
        /* Run the problematic known hosts tests on all locations except ci.jenkins.io */
        /* Do not run the problematic known hosts tests on ci.jenkins.io, they are unreliable */
        return !JENKINS_URL.contains("ci.jenkins.io");
    }

    private static File newFolder(File root, String... subDirs) throws Exception {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + result);
        }
        return result;
    }
}
