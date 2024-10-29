package org.jenkinsci.plugins.gitclient.jgit;

import com.cloudbees.plugins.credentials.common.PasswordCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernameCredentials;
import hudson.model.TaskListener;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang.StringUtils;
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

    private final ConcurrentMap<String, StandardCredentials> specificCredentials = new ConcurrentHashMap<>();
    private static final Logger LOGGER = Logger.getLogger(SmartCredentialsProvider.class.getName());

    /**
     * Constructor for SmartCredentialsProvider.
     *
     * @param listener a {@link TaskListener} object.
     */
    public SmartCredentialsProvider(TaskListener listener) {
        this.listener = listener;
    }

    /**
     * Remove all credentials from the client.
     *
     * @since 1.2.0
     */
    public void clearCredentials() {
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
    public void addCredentials(String url, StandardCredentials credentials) {
        specificCredentials.put(normalizeURI(url), credentials);
    }

    public Map<String, StandardCredentials> getCredentials() {
        Map<String, StandardCredentials> credentialsMap = new HashMap<>(specificCredentials);
        credentialsMap.put("", defaultCredentials);
        return credentialsMap;
    }

    /**
     * Adds credentials to be used when there are not url specific credentials defined.
     *
     * @param credentials the credentials to use.
     * @see #addCredentials(String, StandardCredentials)
     * @since 1.2.0
     */
    public synchronized void addDefaultCredentials(StandardCredentials credentials) {
        defaultCredentials = credentials;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isInteractive() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean supports(CredentialItem... credentialItems) {
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
    public boolean get(URIish uri, CredentialItem... credentialItems) throws UnsupportedCredentialItem {
        StandardCredentials c = uri == null ? null : specificCredentials.get(normalizeURI(uri.toString()));
        if (c == null) {
            c = defaultCredentials;
        }
        if (c == null) {
            Optional<Map.Entry<String, StandardCredentials>> optionalStringStandardCredentialsMapEntry =
                    specificCredentials.entrySet().stream()
                            .filter(stringStandardCredentialsEntry -> {
                                try {
                                    URI repoUri = new URI(stringStandardCredentialsEntry.getKey());
                                    return (uri.getScheme() != null
                                                    && uri.getScheme().equals(repoUri.getScheme()))
                                            && uri.getHost().equals(repoUri.getHost())
                                            && uri.getPort() == repoUri.getPort();
                                } catch (URISyntaxException e) {
                                    // ignore
                                    return false;
                                }
                            })
                            .findAny();
            c = optionalStringStandardCredentialsMapEntry
                    .map(Map.Entry::getValue)
                    .orElse(null);
        }

        if (c == null) {
            if (uri != null) {
                LOGGER.log(Level.FINE, () -> "No credentials provided for " + uri);
            }
            return false;
        }
        for (CredentialItem i : credentialItems) {
            if (i instanceof StandardUsernameCredentialsCredentialItem item
                    && c instanceof StandardUsernameCredentials credentials) {
                item.setValue(credentials);
                continue;
            }
            if (i instanceof CredentialItem.Username username && c instanceof UsernameCredentials credentials) {
                username.setValue(credentials.getUsername());
                continue;
            }
            if (i instanceof CredentialItem.Password password && c instanceof PasswordCredentials credentials) {
                password.setValue(credentials.getPassword().getPlainText().toCharArray());
                continue;
            }
            if (i instanceof CredentialItem.StringType t) {
                if (i.getPromptText().equals("Password: ") && c instanceof PasswordCredentials credentials) {
                    t.setValue(credentials.getPassword().getPlainText());
                    continue;
                }
            }
            throw new UnsupportedCredentialItem(uri, i.getClass().getName() + ":" + i.getPromptText());
        }
        return true;
    }

    private String normalizeURI(String uri) {
        return StringUtils.removeEnd(StringUtils.removeEnd(uri, "/"), ".git");
    }
}
