package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPassword;
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

    /**
     * If username/password is given, use it for HTTP auth.
     */
    @Override
    public boolean supports(CredentialItem... items) {
        // TODO: define a separate Credentials type for HTTP username/password?
        if (!(cred instanceof SSHUserPassword))
            return false;

        for (CredentialItem i : items) {
            if (i instanceof CredentialItem.Username)
                continue;

            else if (i instanceof CredentialItem.Password)
                continue;

            else
                return false;
        }
        return true;
    }

    /**
     * If username/password is given, use it for HTTP auth.
     */
    @Override
    public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
        if (!(cred instanceof SSHUserPassword))
            return false;

        for (CredentialItem i : items) {
            if (i instanceof CredentialItem.Username) {
                ((CredentialItem.Username) i).setValue(((SSHUserPassword) cred).getUsername());
                continue;
            }
            if (i instanceof CredentialItem.Password) {
                ((CredentialItem.Password) i).setValue(
                        ((SSHUserPassword) cred).getPassword().getPlainText().toCharArray());
                continue;
            }
            if (i instanceof CredentialItem.StringType) {
                if (i.getPromptText().equals("Password: ")) {
                    ((CredentialItem.StringType) i).setValue(
                            ((SSHUserPassword) cred).getPassword().getPlainText());
                    continue;
                }
            }
            throw new UnsupportedCredentialItem(uri, i.getClass().getName()
                    + ":" + i.getPromptText());
        }
        return true;
    }
}
