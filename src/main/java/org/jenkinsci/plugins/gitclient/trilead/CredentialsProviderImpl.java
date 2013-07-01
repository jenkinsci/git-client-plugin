package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.plugins.credentials.Credentials;
import hudson.model.TaskListener;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

/**
 * Provides the credential to authenticate Git connection.
 *
 * <p>
 * For HTTP transport we work through {@link CredentialsProvider}, and for {@link TrileadSessionFactory}
 * it specifically downcasts {@link CredentialsProvider} to this class.
 *
 * @author Kohsuke Kawaguchi
 */
public class CredentialsProviderImpl extends CredentialsProvider {
    public final TaskListener listener;
    /**
     * Credential that should be used.
     */
    public final Credentials cred;

    public CredentialsProviderImpl(TaskListener listener, Credentials cred) {
        this.listener = listener;
        this.cred = cred;
    }

    @Override
    public boolean isInteractive() {
        return false;
    }

    // TODO: provide username/password
    @Override
    public boolean supports(CredentialItem... items) {
        return false;
    }

    @Override
    public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
        return false;
    }

//    public SSHUser getCredentials() {
//        try {
//            // only ever want from the system
//            // lookup every time so that we always have the latest
//            for (SSHUser u: com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(SSHUser.class, Jenkins.getInstance(), ACL.SYSTEM)) {
//                if (StringUtils.equals(u.getId(), credentialsId)) {
//                    return u;
//                }
//            }
//        } catch (Throwable t) {
//            // ignore
//        }
//
//        // return something in the hope that ~/.ssh/id* saves the day
//        return new BasicSSHUserPassword(
//                CredentialsScope.SYSTEM, null, System.getProperty("user.name"), null, null);
//    }
}
