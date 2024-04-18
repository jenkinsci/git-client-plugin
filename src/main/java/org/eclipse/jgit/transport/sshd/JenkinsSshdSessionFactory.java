package org.eclipse.jgit.transport.sshd;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.UserAuthFactory;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.compression.BuiltinCompressions;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.signature.Signature;
import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.transport.sshd.GssApiWithMicAuthFactory;
import org.eclipse.jgit.internal.transport.sshd.JGitPublicKeyAuthFactory;
import org.eclipse.jgit.internal.transport.sshd.JGitSshClient;
import org.eclipse.jgit.internal.transport.sshd.JGitSshConfig;
import org.eclipse.jgit.internal.transport.sshd.JGitUserInteraction;
import org.eclipse.jgit.internal.transport.sshd.PasswordProviderWrapper;
import org.eclipse.jgit.internal.transport.sshd.SshdText;
import org.eclipse.jgit.internal.transport.sshd.agent.JGitSshAgentFactory;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshConstants;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.sshd.agent.ConnectorFactory;
import org.eclipse.jgit.util.FS;
import org.jenkinsci.plugins.gitclient.jgit.SmartCredentialsProvider;
import org.jenkinsci.plugins.gitclient.verifier.SshHostKeyVerificationStrategy;

public class JenkinsSshdSessionFactory extends SshdSessionFactory {

    private final ServerKeyVerifier serverKeyVerifier;
    private final SmartCredentialsProvider credentialsProvider;
    private final Map<Tuple, HostConfigEntryResolver> defaultHostConfigEntryResolver = new ConcurrentHashMap<>();
    private final AtomicBoolean closing = new AtomicBoolean();

    private final Set<SshdSession> sessions = new HashSet<>();

    public JenkinsSshdSessionFactory(
            ServerKeyVerifier serverKeyVerifier, SmartCredentialsProvider credentialsProvider) {
        super();
        this.serverKeyVerifier = serverKeyVerifier;
        this.credentialsProvider = credentialsProvider;
    }

    @Override
    public SshdSession getSession(URIish uri, CredentialsProvider credentialsProvider, FS fs, int tms)
            throws TransportException {
        SshdSession session = null;
        try {
            session = new SshdSession(uri, () -> {
                File home = getHomeDirectory();
                if (home == null) {
                    // Always use the detected filesystem for the user home!
                    // It makes no sense to have different "user home"
                    // directories depending on what file system a repository
                    // is.
                    home = FS.DETECTED.userHome();
                }
                File sshDir = getSshDirectory();
                if (sshDir == null) {
                    sshDir = new File(home, SshConstants.SSH_DIR);
                }
                HostConfigEntryResolver configFile = getHostConfigEntryResolver(home, sshDir);
                KeyIdentityProvider defaultKeysProvider = toKeyIdentityProvider(getDefaultKeys(sshDir));
                Supplier<KeyPasswordProvider> keyPasswordProvider =
                        () -> createKeyPasswordProvider(credentialsProvider);
                ServerKeyVerifier serverKeyVerifier = new KnownHostsServerKeyVerifier(
                        JenkinsSshdSessionFactory.this.serverKeyVerifier,
                        SshHostKeyVerificationStrategy.JGIT_KNOWN_HOSTS_FILE.toPath());
                SshClient client = ClientBuilder.builder()
                        .factory(JGitSshClient::new)
                        .filePasswordProvider(new PasswordProviderWrapper(keyPasswordProvider))
                        .hostConfigEntryResolver(configFile)
                        .serverKeyVerifier(serverKeyVerifier)
                        .signatureFactories(getSignatureFactories())
                        .compressionFactories(new ArrayList<>(BuiltinCompressions.VALUES))
                        .build();
                client.setUserInteraction(new JGitUserInteraction(credentialsProvider));
                client.setUserAuthFactories(getUserAuthFactories());
                client.setKeyIdentityProvider(defaultKeysProvider);
                ConnectorFactory connectors = getConnectorFactory();
                if (connectors != null) {
                    client.setAgentFactory(new JGitSshAgentFactory(connectors, home));
                }
                // JGit-specific things:
                JGitSshClient jgitClient = (JGitSshClient) client;
                jgitClient.setKeyCache(getKeyCache());
                jgitClient.setCredentialsProvider(credentialsProvider);
                // FIXME Jenkins proxies?
                jgitClient.setProxyDatabase(new DefaultProxyDataFactory());
                jgitClient.setKeyPasswordProviderFactory(keyPasswordProvider);
                String defaultAuths = getDefaultPreferredAuthentications();
                if (defaultAuths != null) {
                    jgitClient.setAttribute(JGitSshClient.PREFERRED_AUTHENTICATIONS, defaultAuths);
                }
                if (home != null) {
                    try {
                        jgitClient.setAttribute(
                                JGitSshClient.HOME_DIRECTORY,
                                home.getAbsoluteFile().toPath());
                    } catch (SecurityException | InvalidPathException e) {
                        // Ignore
                    }
                }
                // Other things?
                return client;
            });
            session.addCloseListener(s -> unregister(s));
            register(session);
            session.connect(Duration.ofMillis(tms));
            return session;
        } catch (Exception e) {
            unregister(session);
            if (e instanceof TransportException) {
                throw (TransportException) e;
            }
            throw new TransportException(uri, e.getMessage(), e);
        }
    }

    @NonNull
    private HostConfigEntryResolver getHostConfigEntryResolver(@NonNull File homeDir, @NonNull File sshDir) {
        return defaultHostConfigEntryResolver.computeIfAbsent(
                new Tuple(new Object[] {homeDir, sshDir}),
                t -> new JGitSshConfig(createSshConfigStore(homeDir, getSshConfig(sshDir), getLocalUserName())));
    }

    /** A simple general map key. */
    private static final class Tuple {
        private Object[] objects;

        public Tuple(Object[] objects) {
            this.objects = objects;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj != null && obj.getClass() == JenkinsSshdSessionFactory.Tuple.class) {
                JenkinsSshdSessionFactory.Tuple other = (JenkinsSshdSessionFactory.Tuple) obj;
                return Arrays.equals(objects, other.objects);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(objects);
        }
    }

    private KeyIdentityProvider toKeyIdentityProvider(Iterable<KeyPair> keys) {
        if (keys instanceof KeyIdentityProvider) {
            return (KeyIdentityProvider) keys;
        }
        return (session) -> keys;
    }

    private void register(SshdSession newSession) throws IOException {
        if (newSession == null) {
            return;
        }
        if (closing.get()) {
            throw new IOException(SshdText.get().sshClosingDown);
        }
        synchronized (this) {
            sessions.add(newSession);
        }
    }

    private void unregister(SshdSession oldSession) {
        boolean cleanKeys = false;
        synchronized (this) {
            sessions.remove(oldSession);
            cleanKeys = closing.get() && sessions.isEmpty();
        }
        if (cleanKeys) {
            KeyCache cache = getKeyCache();
            if (cache != null) {
                cache.close();
            }
        }
    }

    /**
     * Apache MINA sshd 2.6.0 has removed DSA, DSA_CERT and RSA_CERT. We have to
     * set it up explicitly to still allow users to connect with DSA keys.
     *
     * @return a list of supported signature factories
     */
    @SuppressWarnings("deprecation")
    private static List<NamedFactory<Signature>> getSignatureFactories() {
        // @formatter:off
        // FIXME Check FIPS?
        return Arrays.asList(
                BuiltinSignatures.nistp256_cert,
                BuiltinSignatures.nistp384_cert,
                BuiltinSignatures.nistp521_cert,
                BuiltinSignatures.ed25519_cert,
                BuiltinSignatures.rsaSHA512_cert,
                BuiltinSignatures.rsaSHA256_cert,
                BuiltinSignatures.rsa_cert,
                BuiltinSignatures.nistp256,
                BuiltinSignatures.nistp384,
                BuiltinSignatures.nistp521,
                BuiltinSignatures.ed25519,
                BuiltinSignatures.sk_ecdsa_sha2_nistp256,
                BuiltinSignatures.sk_ssh_ed25519,
                BuiltinSignatures.rsaSHA512,
                BuiltinSignatures.rsaSHA256,
                BuiltinSignatures.rsa,
                BuiltinSignatures.dsa_cert,
                BuiltinSignatures.dsa);
        // @formatter:on
    }

    /**
     * Gets the user authentication mechanisms (or rather, factories for them).
     * By default this returns gssapi-with-mic, public-key, password, and
     * keyboard-interactive, in that order. The order is only significant if the
     * ssh config does <em>not</em> set {@code PreferredAuthentications}; if it
     * is set, the order defined there will be taken.
     *
     * @return the non-empty list of factories.
     */
    @NonNull
    private List<UserAuthFactory> getUserAuthFactories() {
        // About the order of password and keyboard-interactive, see upstream
        // bug https://issues.apache.org/jira/projects/SSHD/issues/SSHD-866 .
        // Password auth doesn't have this problem.
        return Collections.unmodifiableList(Arrays.asList(
                GssApiWithMicAuthFactory.INSTANCE,
                JGitPublicKeyAuthFactory.FACTORY,
                UserAuthPasswordFactory.INSTANCE,
                UserAuthKeyboardInteractiveFactory.INSTANCE));
    }
}
