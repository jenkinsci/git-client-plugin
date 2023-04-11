package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.TaskListener;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

/**
 * Provides the credential to authenticate Git connection.
 *
 * <p>
 * For HTTP transport we work through {@link org.eclipse.jgit.transport.CredentialsProvider},
 * in which case this must be supplied with a {@link com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials}.
 * For SSH transport, {@link org.jenkinsci.plugins.gitclient.trilead.TrileadSessionFactory}
 * downcasts {@link org.eclipse.jgit.transport.CredentialsProvider} to this class.
 *
 * @author Kohsuke Kawaguchi
 */
public class CredentialsProviderImpl extends CredentialsProvider {
    public final TaskListener listener;
    /**
     * Credential that should be used.
     */
    public final StandardUsernameCredentials cred;

    /**
     * Constructor for CredentialsProviderImpl.
     *
     * @param listener a {@link hudson.model.TaskListener} object.
     * @param cred a {@link com.cloudbees.plugins.credentials.common.StandardUsernameCredentials} object.
     */
    public CredentialsProviderImpl(TaskListener listener, StandardUsernameCredentials cred) {
        this.listener = listener;
        this.cred = cred;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isInteractive() {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * If username/password is given, use it for HTTP auth.
     */
    @Override
    public boolean supports(CredentialItem... items) {
        if (!(cred instanceof StandardUsernamePasswordCredentials)) {
            return false;
        }

        for (CredentialItem i : items) {
            if (i instanceof CredentialItem.Username) {
                continue;
            } else if (i instanceof CredentialItem.Password) {
                continue;
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * If username/password is given, use it for HTTP auth.
     */
    @Override
    public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
        if (!(cred instanceof StandardUsernamePasswordCredentials)) {
            return false;
        }
        StandardUsernamePasswordCredentials _cred = (StandardUsernamePasswordCredentials) cred;

        for (CredentialItem i : items) {
            if (i instanceof CredentialItem.Username) {
                ((CredentialItem.Username) i).setValue(_cred.getUsername());
                continue;
            }
            if (i instanceof CredentialItem.Password) {
                ((CredentialItem.Password) i)
                        .setValue(_cred.getPassword().getPlainText().toCharArray());
                continue;
            }
            if (i instanceof CredentialItem.StringType) {
                if (i.getPromptText().equals("Password: ")) {
                    ((CredentialItem.StringType) i).setValue(_cred.getPassword().getPlainText());
                    continue;
                }
            }
            throw new UnsupportedCredentialItem(uri, i.getClass().getName() + ":" + i.getPromptText());
        }
        return true;
    }
}
