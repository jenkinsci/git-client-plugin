package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.trilead.ssh2.Connection;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RemoteSession;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.jenkinsci.plugins.gitclient.verifier.AcceptFirstConnectionVerifier;
import org.jenkinsci.plugins.gitclient.verifier.HostKeyVerifierFactory;

/**
 * Makes JGit uses Trilead for connectivity.
 *
 * @author Kohsuke Kawaguchi
 */
public class TrileadSessionFactory extends SshSessionFactory {

    private static final ReentrantLock JGIT_ACCEPT_FIRST_LOCK = new ReentrantLock();

    private final HostKeyVerifierFactory hostKeyVerifierFactory;
    private final TaskListener listener;

    public TrileadSessionFactory(HostKeyVerifierFactory hostKeyVerifierFactory, TaskListener listener) {
        this.hostKeyVerifierFactory = hostKeyVerifierFactory;
        this.listener = listener;
    }

    /** {@inheritDoc} */
    @Override
    public RemoteSession getSession(URIish uri, CredentialsProvider credentialsProvider, FS fs, int tms)
            throws TransportException {
        try {
            int p = uri.getPort();
            if (p < 0) {
                p = 22;
            }
            JGitConnection con = new JGitConnection(uri.getHost(), p);
            con.setTCPNoDelay(true);
            if (hostKeyVerifierFactory instanceof AcceptFirstConnectionVerifier) {
                // Accept First connection behavior need to be synchronized, because it's the only verifier
                // which could change (populate) known hosts dynamically, in other words AcceptFirstConnectionVerifier
                // should be able to see and read if any known hosts was added during parallel connection.
                JGIT_ACCEPT_FIRST_LOCK.lock();
                try {
                    con.connect(hostKeyVerifierFactory.forJGit(listener));
                } finally {
                    JGIT_ACCEPT_FIRST_LOCK.unlock();
                }
            } else {
                con.connect(hostKeyVerifierFactory.forJGit(listener));
            }

            boolean authenticated;
            if (credentialsProvider instanceof SmartCredentialsProvider) {
                final SmartCredentialsProvider smart = (SmartCredentialsProvider) credentialsProvider;
                StandardUsernameCredentialsCredentialItem item =
                        new StandardUsernameCredentialsCredentialItem("Credentials for " + uri, false);
                authenticated = smart.supports(item)
                        && smart.get(uri, item)
                        && SSHAuthenticator.newInstance(con, item.getValue(), uri.getUser())
                                .authenticate(smart.listener);
            } else if (credentialsProvider instanceof CredentialsProviderImpl) {
                CredentialsProviderImpl sshcp = (CredentialsProviderImpl) credentialsProvider;

                authenticated = SSHAuthenticator.newInstance(con, sshcp.cred).authenticate(sshcp.listener);
            } else {
                authenticated = false;
            }
            if (!authenticated && con.isAuthenticationComplete()) {
                throw new TransportException("Authentication failure");
            }

            return wrap(con);
        } catch (UnsupportedCredentialItem | IOException | InterruptedException e) {
            throw new TransportException(uri, "Failed to connect", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String getType() {
        return "Jenkins credentials Trilead ssh session factory";
    }

    /**
     * wrap.
     *
     * @param con a {@link com.trilead.ssh2.Connection} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.trilead.TrileadSession} object.
     */
    protected TrileadSession wrap(Connection con) {
        return new TrileadSession(con);
    }
}
