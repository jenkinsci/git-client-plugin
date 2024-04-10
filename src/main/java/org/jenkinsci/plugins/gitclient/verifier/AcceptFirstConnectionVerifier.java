package org.jenkinsci.plugins.gitclient.verifier;

import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;

public class AcceptFirstConnectionVerifier extends HostKeyVerifierFactory {

    private static final Logger LOGGER = Logger.getLogger(AcceptFirstConnectionVerifier.class.getName());

    @Override
    public AbstractCliGitHostKeyVerifier forCliGit(TaskListener listener) {
        return tempKnownHosts -> {
            listener.getLogger()
                    .println("Verifying host key using known hosts file, will automatically accept unseen keys");
            return "-o StrictHostKeyChecking=accept-new -o HashKnownHosts=yes";
        };
    }

    @Override
    public AbstractJGitHostKeyVerifier forJGit(TaskListener listener) {
        KnownHostsServerKeyVerifier knownHosts = new KnownHostsServerKeyVerifier(
                (clientSession, socketAddress, publicKey) -> false,
                getKnownHostsFile().toPath());

        return new AcceptFirstConnectionJGitHostKeyVerifier(listener, knownHosts);
    }

    public class AcceptFirstConnectionJGitHostKeyVerifier extends AbstractJGitHostKeyVerifier {

        private final TaskListener listener;

        public AcceptFirstConnectionJGitHostKeyVerifier(TaskListener listener, ServerKeyVerifier serverKeyVerifier) {
            super(serverKeyVerifier);
            this.listener = listener;
        }

        @Override
        public boolean verifyServerHostKey(
                TaskListener taskListener,
                ServerKeyVerifier serverKeyVerifier,
                String hostname,
                int port,
                String serverHostKeyAlgorithm,
                byte[] serverHostKey)
                throws IOException {

            listener.getLogger()
                    .printf(
                            "Verifying host key for %s using %s %n",
                            hostname, getKnownHostsFile().toPath());
            File knownHostsFile = getKnownHostsFile();
            Path path = Paths.get(knownHostsFile.getAbsolutePath());
            String hostnamePort = hostname + ":" + port;
            boolean isValid = false;

            if (!isValid) {
                LOGGER.log(
                        Level.FINER,
                        "Host key for {0} was not accepted on accept first verifier known hosts file {1}",
                        new Object[] {hostnamePort, path.toString()});
                listener.getLogger().printf("Host key for host %s was not accepted.%n", hostnamePort);
            }

            return isValid;
        }
        //
        //        private void writeToFile(
        //                File knownHostsFile, String hostnamePort, String serverHostKeyAlgorithm, byte[] serverHostKey)
        //                throws IOException {
        //            listener.getLogger().println("Adding " + hostnamePort + " to " + knownHostsFile.toPath());
        //            LOGGER.log(
        //                    Level.FINEST,
        //                    "Adding {0} to known hosts {1} in accept first verifier with host key {2} {3}",
        //                    new Object[] {
        //                        hostnamePort,
        //                        knownHostsFile.toPath().toString(),
        //                        serverHostKeyAlgorithm,
        //                        Base64.getEncoder().encodeToString(serverHostKey)
        //                    });
        //            KnownHosts.addHostkeyToFile(
        //                    knownHostsFile,
        //                    new String[] {KnownHosts.createHashedHostname(hostnamePort)},
        //                    serverHostKeyAlgorithm,
        //                    serverHostKey);
        //            getKnownHosts()
        //                    .addHostkey(
        //                            new String[] {KnownHosts.createHashedHostname(hostnamePort)},
        //                            serverHostKeyAlgorithm,
        //                            serverHostKey);
        //        }
    }
}
