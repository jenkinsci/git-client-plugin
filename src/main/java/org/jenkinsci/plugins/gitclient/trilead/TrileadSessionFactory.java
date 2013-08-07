package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.trilead.ssh2.Connection;
import hudson.AbortException;
import org.eclipse.jgit.errors.TransportException;
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
    @Override
    public RemoteSession getSession(URIish uri, CredentialsProvider credentialsProvider, FS fs, int tms) throws TransportException {
        try {
            int p = uri.getPort();
            if (p<0)    p = 22;
            Connection con = new Connection(uri.getHost(), p);
            con.setTCPNoDelay(true);
            con.connect();  // TODO: host key check

            CredentialsProviderImpl sshcp = (CredentialsProviderImpl)credentialsProvider;

            Credentials c = sshcp.cred;
            if (!(c instanceof StandardUsernameCredentials))
                throw new AbortException("Expecting username-based credentials but got "+c+" instead");

            if (!SSHAuthenticator.newInstance(con, (StandardUsernameCredentials) c).authenticate(sshcp.listener) && con.isAuthenticationComplete())
                throw new TransportException("Authentication failure");

            return wrap(con);
        } catch (IOException e) {
            throw new TransportException(uri,"Failed to connect",e);
        } catch (InterruptedException e) {
            throw new TransportException(uri,"Failed to connect",e);
        }
    }

    protected TrileadSession wrap(Connection con) {
        return new TrileadSession(con);
    }
}
