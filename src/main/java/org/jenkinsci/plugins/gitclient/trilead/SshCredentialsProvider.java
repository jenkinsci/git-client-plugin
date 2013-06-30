package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUser;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPassword;
import com.cloudbees.plugins.credentials.CredentialsScope;
import hudson.model.TaskListener;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

/**
 * @author Kohsuke Kawaguchi
 */
public class SshCredentialsProvider extends CredentialsProvider {
    public final TaskListener listener;
    public final SSHUser cred;

    public SshCredentialsProvider(TaskListener listener, SSHUser cred) {
        this.listener = listener;
        this.cred = cred;
    }

    @Override
    public boolean isInteractive() {
        return false;
    }

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
