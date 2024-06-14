package org.jenkinsci.plugins.gitclient.jgit;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import org.eclipse.jgit.transport.CredentialItem;

/**
 * Represents credentials suitable for use over SSH.
 *
 * @author Stephen Connolly
 */
public class StandardUsernameCredentialsCredentialItem extends CredentialItem {
    private StandardUsernameCredentials value;

    /**
     * Initialize a prompt for a single {@link com.cloudbees.plugins.credentials.common.StandardCredentials} item.
     *
     * @param promptText prompt to display to the user alongside of the input field.
     *                   Should be sufficient text to indicate what to supply for this
     *                   item.
     * @param maskValue  true if the value should be masked from displaying during
     *                   input. This should be true for passwords and other secrets,
     *                   false for names and other public data.
     */
    public StandardUsernameCredentialsCredentialItem(String promptText, boolean maskValue) {
        super(promptText, maskValue);
    }

    /** {@inheritDoc} */
    @Override
    public void clear() {
        value = null;
    }

    /**
     * Returns the current value.
     *
     * @return the current value.
     */
    public StandardUsernameCredentials getValue() {
        return value;
    }

    /**
     * Sets the current value.
     *
     * @param newValue the new value.
     */
    public void setValue(StandardUsernameCredentials newValue) {
        value = newValue;
    }
}
