package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.plugins.credentials.common.PasswordCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernameCredentials;
import hudson.model.TaskListener;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

/**
 * SmartCredentialsProvider class.
 *
 * @author stephenc
 */
public class SmartCredentialsProvider extends CredentialsProvider {

    public final TaskListener listener;

    private StandardCredentials defaultCredentials;

    private Map<String, StandardCredentials> specificCredentials = new HashMap<>();

    /**
     * Constructor for SmartCredentialsProvider.
     *
     * @param listener a {@link hudson.model.TaskListener} object.
     */
    public SmartCredentialsProvider(TaskListener listener) {
        this.listener = listener;
    }

    /**
     * Remove all credentials from the client.
     *
     * @since 1.2.0
     */
    public synchronized void clearCredentials() {
        defaultCredentials = null;
        specificCredentials.clear();
    }

    /**
     * Adds credentials to be used against a specific url.
     *
     * @param url         the url for the credentials to be used against.
     * @param credentials the credentials to use.
     * @since 1.2.0
     */
    public synchronized void addCredentials(String url, StandardCredentials credentials) {
        specificCredentials.put(url, credentials);
    }

    /**
     * Adds credentials to be used when there are not url specific credentials defined.
     *
     * @param credentials the credentials to use.
     * @see #addCredentials(String, com.cloudbees.plugins.credentials.common.StandardCredentials)
     * @since 1.2.0
     */
    public synchronized void addDefaultCredentials(StandardCredentials credentials) {
        defaultCredentials = credentials;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isInteractive() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean supports(CredentialItem... credentialItems) {
        items:
        for (CredentialItem item : credentialItems) {
            if (supports(defaultCredentials, item)) {
                continue;
            }
            for (StandardCredentials c : specificCredentials.values()) {
                if (supports(c, item)) {
                    continue items;
                }
            }
            return false;
        }
        return true;
    }

    private boolean supports(StandardCredentials c, CredentialItem i) {
        if (c == null) {
            return false;
        }
        if (i instanceof StandardUsernameCredentialsCredentialItem) {
            return c instanceof StandardUsernameCredentials;
        }
        if (i instanceof CredentialItem.Username) {
            return c instanceof UsernameCredentials;
        }
        if (i instanceof CredentialItem.Password) {
            return c instanceof PasswordCredentials;
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean get(URIish uri, CredentialItem... credentialItems) throws UnsupportedCredentialItem {
        StandardCredentials c = specificCredentials.get(uri == null ? null : uri.toString());
        if (c == null) {
            c = defaultCredentials;
        }
        if (c == null) {
            return false;
        }
        for (CredentialItem i : credentialItems) {
            if (i instanceof StandardUsernameCredentialsCredentialItem && c instanceof StandardUsernameCredentials) {
                ((StandardUsernameCredentialsCredentialItem) i).setValue((StandardUsernameCredentials) c);
                continue;
            }
            if (i instanceof CredentialItem.Username && c instanceof UsernameCredentials) {
                ((CredentialItem.Username) i).setValue(((UsernameCredentials) c).getUsername());
                continue;
            }
            if (i instanceof CredentialItem.Password && c instanceof PasswordCredentials) {
                ((CredentialItem.Password) i)
                        .setValue(((PasswordCredentials) c)
                                .getPassword()
                                .getPlainText()
                                .toCharArray());
                continue;
            }
            if (i instanceof CredentialItem.StringType) {
                if (i.getPromptText().equals("Password: ") && c instanceof PasswordCredentials) {
                    ((CredentialItem.StringType) i)
                            .setValue(((PasswordCredentials) c).getPassword().getPlainText());
                    continue;
                }
            }
            throw new UnsupportedCredentialItem(uri, i.getClass().getName() + ":" + i.getPromptText());
        }
        return true;
    }
}
