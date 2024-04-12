package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.eclipse.jgit.internal.transport.ssh.OpenSshConfigFile;
import org.eclipse.jgit.transport.SshConstants;

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
                userKnownHostsFileFlag = String.format(" -o UserKnownHostsFile=%s", escapePath(tempKnownHosts));
            } else {
                // escaping needed in case job name contains spaces
                userKnownHostsFileFlag = String.format(
                        " -o UserKnownHostsFile=\\\"\"\"%s\\\"\"\"",escapePath(tempKnownHosts));
            }
            return "-o StrictHostKeyChecking=yes " + userKnownHostsFileFlag;
        };
    }

    private static String escapePath(Path path) {
        if (File.pathSeparatorChar== ';') // check whether on Windows or not without sending Functions over remoting
        {
            return path.toAbsolutePath().toString();
        }
        return path.toAbsolutePath().toString().replace(" ", "\\ ");
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {

        // FIXME check this
        //        KnownHosts knownHosts;
        //        try {
        //            knownHosts = approvedHostKeys != null ? new KnownHosts(approvedHostKeys.toCharArray()) : new
        // KnownHosts();
        //        } catch (IOException e) {
        //            LOGGER.log(Level.WARNING, e, () -> "Could not load known hosts.");
        //            knownHosts = new KnownHosts();
        //        }
        return new ManuallyProvidedKeyJGitHostKeyVerifier(listener, approvedHostKeys);
    }

    public static class ManuallyProvidedKeyJGitHostKeyVerifier extends AbstractJGitHostKeyVerifier {

        private final String approvedHostKeys;

        public ManuallyProvidedKeyJGitHostKeyVerifier(TaskListener listener, String approvedHostKeys) {
            super(listener);
            this.approvedHostKeys = approvedHostKeys;
        }

        @Override
        public OpenSshConfigFile.HostEntry customizeHostEntry(OpenSshConfigFile.HostEntry hostEntry) {
            try {
                Path tempKnownHosts = Files.createTempFile("known_hosts", "");
                Files.write(tempKnownHosts, (approvedHostKeys + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                hostEntry.setValue(SshConstants.USER_KNOWN_HOSTS_FILE, escapePath(tempKnownHosts));
                return hostEntry;
            } catch (IOException e) {
                getTaskListener().error("cannot write temporary know_hosts file: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
    }
}
