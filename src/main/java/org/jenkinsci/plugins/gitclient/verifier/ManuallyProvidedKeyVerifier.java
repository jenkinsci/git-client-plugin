package org.jenkinsci.plugins.gitclient.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;

public class ManuallyProvidedKeyVerifier extends HostKeyVerifierFactory {

    private static final Logger LOGGER = Logger.getLogger(ManuallyProvidedKeyVerifier.class.getName());

    private final String approvedHostKeys;

    public ManuallyProvidedKeyVerifier(String approvedHostKeys) {
        this.approvedHostKeys = approvedHostKeys;
    }

    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> {
            Files.write(tempKnownHosts, (approvedHostKeys + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            listener.getLogger().println("Verifying host key using manually-configured host key entries");
            LOGGER.log(
                    Level.FINEST,
                    "Verifying manually-configured host keys entry in {0} with host keys {1}",
                    new Object[] {tempKnownHosts.toAbsolutePath(), approvedHostKeys});
            String userKnownHostsFileFlag;
            if (File.pathSeparatorChar
                    == ';') { // check whether on Windows or not without sending Functions over remoting
                // no escaping for windows because created temp file can't contain spaces
                userKnownHostsFileFlag = " -o UserKnownHostsFile=%s".formatted(escapePath(tempKnownHosts));
            } else {
                // escaping needed in case job name contains spaces
                userKnownHostsFileFlag =
                        " -o UserKnownHostsFile=\\\"\"\"%s\\\"\"\"".formatted(escapePath(tempKnownHosts));
            }
            return "-o StrictHostKeyChecking=yes " + userKnownHostsFileFlag;
        };
    }

    private static String escapePath(Path path) {
        if (File.pathSeparatorChar == ';') // check whether on Windows or not without sending Functions over remoting
        {
            return path.toAbsolutePath().toString();
        }
        return path.toAbsolutePath().toString().replace(" ", "\\ ");
    }

    @NonNull
    @Override
    public File getKnownHostsFile() {
        try {
            Path tempKnownHosts = Files.createTempFile("known_hosts", "");
            Files.write(tempKnownHosts, (approvedHostKeys + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            return tempKnownHosts.toFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {
        return new ManuallyProvidedKeyJGitHostKeyVerifier(listener, this);
    }

    public static class ManuallyProvidedKeyJGitHostKeyVerifier extends AbstractJGitHostKeyVerifier {

        private File knownHostsFile;

        public ManuallyProvidedKeyJGitHostKeyVerifier(
                TaskListener listener, HostKeyVerifierFactory hostKeyVerifierFactory) {
            super(listener, hostKeyVerifierFactory);
        }

        @Override
        public ServerKeyDatabase.Configuration getServerKeyDatabaseConfiguration() {
            return new AbstractJGitHostKeyVerifier.DefaultConfiguration(
                    this.getHostKeyVerifierFactory(),
                    () -> ServerKeyDatabase.Configuration.StrictHostKeyChecking.REQUIRE_MATCH);
        }
    }
}
