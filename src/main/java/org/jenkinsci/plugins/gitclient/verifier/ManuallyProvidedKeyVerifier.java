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
            String path = escapePath(tempKnownHosts);
            LOGGER.log(Level.FINEST, "-o StrictHostKeyChecking=yes -o UserKnownHostsFile={0}", path);
            return "-o StrictHostKeyChecking=yes -o UserKnownHostsFile=%s".formatted(path);
        };
    }

    private static String escapePath(Path path) {
        String p = path.toAbsolutePath().toString();
        // check whether on Windows or not without sending Functions over remoting
        if (File.pathSeparatorChar == ';') {
            // no escaping for windows because created temp file can't contain spaces
            return p;
        }
        if (p.contains("'")) {
            p = p.replace("'", "\\'");
        }
        // OpenSSH needs arguments that contain a space to be surrounded with double quotes
        return p.contains(" ") ? "'\"" + p + "\"'" : "'" + p + "'";
    }

    @NonNull
    @Override
    public File getKnownHostsFile() {
        try {
            Path tempKnownHosts = Files.createTempFile("known_hosts", "");
            Files.write(tempKnownHosts, (approvedHostKeys + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            LOGGER.log(Level.FINEST, "Known hosts written to {0}", tempKnownHosts);
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
        protected ServerKeyDatabase.Configuration getServerKeyDatabaseConfiguration() {
            return new AbstractJGitHostKeyVerifier.DefaultConfiguration(
                    this.getHostKeyVerifierFactory(),
                    () -> ServerKeyDatabase.Configuration.StrictHostKeyChecking.REQUIRE_MATCH);
        }
    }
}
