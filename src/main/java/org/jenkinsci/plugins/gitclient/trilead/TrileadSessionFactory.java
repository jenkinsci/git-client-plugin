package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.trilead.ssh2.Connection;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RemoteSession;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;

import java.io.IOException;

/**
 * Makes JGit uses Trilead for connectivity.
 *
 * @author Kohsuke Kawaguchi
 */
public class TrileadSessionFactory extends SshSessionFactory {
    /** {@inheritDoc} */
    @Override
    public RemoteSession getSession(URIish uri, CredentialsProvider credentialsProvider, FS fs, int tms) throws TransportException {
        try {
            int p = uri.getPort();
            if (p<0)    p = 22;
            Connection con = new Connection(uri.getHost(), p);
            con.setTCPNoDelay(true);
            con.connect();  // TODO: host key check

            boolean authenticated;
            if (credentialsProvider instanceof SmartCredentialsProvider) {
                final SmartCredentialsProvider smart = (SmartCredentialsProvider) credentialsProvider;
                StandardUsernameCredentialsCredentialItem
                        item = new StandardUsernameCredentialsCredentialItem("Credentials for " + uri, false);
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
            if (!authenticated && con.isAuthenticationComplete())
                throw new TransportException("Authentication failure");

            return wrap(con);
        } catch (UnsupportedCredentialItem | IOException | InterruptedException e) {
            throw new TransportException(uri,"Failed to connect",e);
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
