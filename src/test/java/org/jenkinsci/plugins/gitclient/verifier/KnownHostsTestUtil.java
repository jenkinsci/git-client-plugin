package org.jenkinsci.plugins.gitclient.verifier;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import hudson.util.ReflectionUtils;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSessionCreator;
import org.apache.sshd.common.compression.BuiltinCompressions;
import org.apache.sshd.common.config.keys.OpenSshCertificate;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.internal.transport.ssh.OpenSshConfigFile;
import org.eclipse.jgit.internal.transport.sshd.JGitClientSession;
import org.eclipse.jgit.internal.transport.sshd.JGitServerKeyVerifier;
import org.eclipse.jgit.internal.transport.sshd.JGitSshClient;
import org.eclipse.jgit.internal.transport.sshd.JGitSshConfig;
import org.eclipse.jgit.internal.transport.sshd.OpenSshServerKeyDatabase;
import org.eclipse.jgit.transport.SshConfigStore;
import org.eclipse.jgit.transport.sshd.IdentityPasswordProvider;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.junit.rules.TemporaryFolder;

import static org.eclipse.jgit.transport.SshSessionFactory.getLocalUserName;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

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

    protected static JGitClientSession connectToHost(String host, int port, File knownHostsFile, AbstractJGitHostKeyVerifier verifier, Predicate<JGitClientSession> asserts) throws IOException {

        HostConfigEntryResolver configFile = new JGitSshConfig(createSshConfigStore(knownHostsFile, verifier));
        try (SshClient client = ClientBuilder.builder()
                .factory(JGitSshClient::new)
                //.filePasswordProvider((sessionContext, namedResource, i) -> "foo")
                .hostConfigEntryResolver(configFile)
                .serverKeyVerifier(new JGitServerKeyVerifier(getServerKeyDatabase(knownHostsFile)))
                .compressionFactories(new ArrayList<>(BuiltinCompressions.VALUES))
                .build()){
            client.start();
            // just to avoid NPE
            JGitSshClient jgitClient = (JGitSshClient) client;
            jgitClient.setKeyPasswordProviderFactory(() -> new IdentityPasswordProvider(null));

            ConnectFuture connectFuture = client.connect(getLocalUserName(), host, port);
            JGitClientSession s = (JGitClientSession) connectFuture.verify(Duration.ofMillis(30000)).getClientSession();
            // make a simple call to force keys exchange
            Method method = ReflectionUtils.findMethod(s.getClass(),"getServices"); // sendKexInit //"doKexNegotiation");
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
        ServerKeyVerifier serverKeyVerifier = Objects.requireNonNull(s.getServerKeyVerifier(), "No server key verifier");
        IoSession networkSession = s.getIoSession();
        SocketAddress remoteAddress = networkSession.getRemoteAddress();
        PublicKey serverKey = Objects.requireNonNull(s.getServerKey(), "No server key to verify");
        SshdSocketAddress targetServerAddress = s.getAttribute(ClientSessionCreator.TARGET_SERVER);
        if (targetServerAddress != null) {
            remoteAddress = targetServerAddress.toInetSocketAddress();
        }

        boolean verified = false;
        if (serverKey instanceof OpenSshCertificate) {
            // check if we trust the CA
            verified = serverKeyVerifier.verifyServerKey(s, remoteAddress, ((OpenSshCertificate) serverKey).getCaPubKey());

            if (!verified) {
                // fallback to actual public host key
                serverKey = ((OpenSshCertificate) serverKey).getCertPubKey();
            }
        }

        if (!verified) {
            verified = serverKeyVerifier.verifyServerKey(s, remoteAddress, serverKey);
        }

        return verified;
    }

    protected static SshConfigStore createSshConfigStore(@NonNull File knownHost, @NonNull AbstractJGitHostKeyVerifier verifier) {
        return new OpenSshConfigFile(knownHost.getParentFile(), new File(knownHost.getParentFile(), "fakeconfig"), getLocalUserName()) {
            @Override
            public HostEntry lookup(String hostName, int port, String userName) {
                HostEntry hostEntry = super.lookup(hostName, port, userName);
                return verifier.customizeHostEntry(hostEntry);
            }
        };
    }

    protected static ServerKeyDatabase getServerKeyDatabase(File knownHostFile) {
        return new OpenSshServerKeyDatabase(true, Collections.singletonList(knownHostFile.toPath()));
    }


}
