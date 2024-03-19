package org.jenkinsci.plugins.gitclient.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.TaskListener;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import jenkins.util.SystemProperties;
import org.eclipse.jgit.internal.transport.sshd.OpenSshServerKeyDatabase;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

public abstract class AbstractJGitHostKeyVerifier implements SerializableOnlyOverRemoting {

    private TaskListener taskListener;

    private final HostKeyVerifierFactory hostKeyVerifierFactory;

    protected AbstractJGitHostKeyVerifier(TaskListener taskListener, HostKeyVerifierFactory hostKeyVerifierFactory) {
        this.taskListener = taskListener;
        this.hostKeyVerifierFactory = hostKeyVerifierFactory;
    }

    public TaskListener getTaskListener() {
        return taskListener;
    }

    public HostKeyVerifierFactory getHostKeyVerifierFactory() {
        return hostKeyVerifierFactory;
    }

    protected abstract ServerKeyDatabase.Configuration getServerKeyDatabaseConfiguration();

    public ServerKeyDatabase getServerKeyDatabase() {
        ServerKeyDatabase.Configuration configuration = getServerKeyDatabaseConfiguration();
        return new JenkinsServerKeyDatabase(
                askAboutKnowHostFile(),
                Collections.singletonList(
                        hostKeyVerifierFactory.getKnownHostsFile().toPath()),
                configuration);
    }

    protected static class JenkinsServerKeyDatabase extends OpenSshServerKeyDatabase {
        private final ServerKeyDatabase.Configuration configuration;

        public JenkinsServerKeyDatabase(
                boolean askAboutNewFile, List<Path> defaultFiles, ServerKeyDatabase.Configuration configuration) {
            super(askAboutNewFile, defaultFiles);
            this.configuration = configuration;
        }

        @Override
        public List<PublicKey> lookup(String connectAddress, InetSocketAddress remoteAddress, Configuration config) {
            return super.lookup(connectAddress, remoteAddress, this.configuration);
        }

        @Override
        public boolean accept(
                String connectAddress,
                InetSocketAddress remoteAddress,
                PublicKey serverKey,
                Configuration config,
                CredentialsProvider provider) {
            return super.accept(connectAddress, remoteAddress, serverKey, this.configuration, provider);
        }
    }

    /**
     * <p>
     * see {@link OpenSshServerKeyDatabase} if {@code true} the implementation of ${@link CredentialsProvider} will be
     * search with a for {@link org.eclipse.jgit.transport.CredentialItem.YesNoType}
     * and our implementation {@link org.jenkinsci.plugins.gitclient.jgit.SmartCredentialsProvider} never returns that
     * </p>
     * <p>
     *     used only here for the Accept first which always create a file without asking anything
     * </p>
     * @return something in the range {@code true} or {@code false}, per default {@code true} to avoid automatic creation
     * of know hosts file.
     */
    protected boolean askAboutKnowHostFile() {
        return true;
    }

    protected static class DefaultConfiguration implements ServerKeyDatabase.Configuration {

        private final HostKeyVerifierFactory hostKeyVerifierFactory;

        private final Supplier<StrictHostKeyChecking> supplier;

        protected DefaultConfiguration(
                @NonNull HostKeyVerifierFactory hostKeyVerifierFactory,
                @NonNull Supplier<StrictHostKeyChecking> supplier) {
            this.hostKeyVerifierFactory = hostKeyVerifierFactory;
            this.supplier = supplier;
        }

        @Override
        public List<String> getUserKnownHostsFiles() {
            return List.of(hostKeyVerifierFactory.getKnownHostsFile().getAbsolutePath());
        }

        @Override
        public List<String> getGlobalKnownHostsFiles() {
            return Collections.emptyList();
        }

        @Override
        public boolean getHashKnownHosts() {
            // configurable?
            return true;
        }

        @Override
        public String getUsername() {
            return SystemProperties.getString("user.name");
        }

        @Override
        public StrictHostKeyChecking getStrictHostKeyChecking() {
            return supplier.get();
        }
    }
}
