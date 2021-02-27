package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.TaskListener;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import java.net.URISyntaxException;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class SmartCredentialsProviderTest {

    private TaskListener listener;
    private SmartCredentialsProvider provider;
    private final URIish gitURI;
    private CredentialItem.Username username;
    private CredentialItem.Password password;
    private CredentialItem.StringType maskedStringType;
    private CredentialItem.StringType unmaskedStringType;
    private CredentialItem.StringType specialStringType;
    private StandardUsernameCredentialsCredentialItem maskedUsername;
    private StandardUsernameCredentialsCredentialItem unmaskedUsername;
    private final String MASKED_USER_NAME_PROMPT = "masked user name prompt";
    private final String UNMASKED_USER_NAME_PROMPT = "unmasked user name prompt";
    private final String MASKED_STRING_TYPE_PROMPT = "masked string type prompt";
    private final String UNMASKED_STRING_TYPE_PROMPT = "unmasked string type prompt";
    private final String SPECIAL_STRING_TYPE_PROMPT = "Password: ";

    public SmartCredentialsProviderTest() throws URISyntaxException {
        gitURI = new URIish("git://github.com/jenkinsci/git-client-plugin.git");
    }

    @Before
    public void setUp() {
        listener = StreamTaskListener.fromStdout();
        provider = new SmartCredentialsProvider(listener);
        username = new CredentialItem.Username();
        password = new CredentialItem.Password();

        maskedUsername = new StandardUsernameCredentialsCredentialItem(MASKED_USER_NAME_PROMPT, true);
        unmaskedUsername = new StandardUsernameCredentialsCredentialItem(UNMASKED_USER_NAME_PROMPT, false);

        maskedStringType = new CredentialItem.StringType(MASKED_STRING_TYPE_PROMPT, true);
        unmaskedStringType = new CredentialItem.StringType(UNMASKED_STRING_TYPE_PROMPT, false);
        specialStringType = new CredentialItem.StringType(SPECIAL_STRING_TYPE_PROMPT, false);

        assertNull(username.getValue());
        assertNull(password.getValue());
        assertNull(maskedUsername.getValue());
        assertNull(unmaskedUsername.getValue());
        assertNull(maskedStringType.getValue());
        assertNull(unmaskedStringType.getValue());
    }

    @Test
    public void testClearCredentials() {
        provider.clearCredentials();

        assertFalse(provider.supports(username, password));
        assertFalse(provider.supports(maskedUsername, unmaskedUsername));
        assertFalse(provider.supports(maskedStringType, unmaskedStringType));
        assertFalse(provider.supports(specialStringType));
        assertFalse(provider.get(gitURI, username, password, maskedUsername, unmaskedUsername));

        assertFalse(username.isValueSecure());
        assertEquals("Username", username.getPromptText());
        assertNull(username.getValue());

        assertTrue(password.isValueSecure());
        assertEquals("Password", password.getPromptText());
        assertNull(password.getValue());

        assertTrue(maskedUsername.isValueSecure());
        assertEquals(MASKED_USER_NAME_PROMPT, maskedUsername.getPromptText());
        assertNull(maskedUsername.getValue());

        assertFalse(unmaskedUsername.isValueSecure());
        assertEquals(UNMASKED_USER_NAME_PROMPT, unmaskedUsername.getPromptText());
        assertNull(unmaskedUsername.getValue());
    }

    @Test
    public void testAddCredentials() {
        String expectedUsername = "expected-add-credentials-username";
        String secretValue = "secret-value";
        Secret secret = Secret.fromString(secretValue);
        StandardUsernamePasswordCredentials credentials = new StandardUsernamePasswordCredentialsImpl(expectedUsername, secret);

        assertFalse(provider.supports(username, password));
        assertFalse(provider.supports(maskedUsername, unmaskedUsername));
        assertFalse(provider.supports(maskedStringType, unmaskedStringType));
        assertFalse(provider.supports(specialStringType));

        provider.addCredentials(gitURI.toString(), credentials);

        assertTrue(provider.supports(username, password));
        assertTrue(provider.supports(maskedUsername, unmaskedUsername));

        /* The supports() method does not know about the "special" StringType
         * which can be used to return the password plaintext.  Passing a
         * StringType with the prompt text "Password: " will return the plain
         * text password.
         */
        assertFalse(provider.supports(specialStringType));

        assertFalse(provider.supports(maskedStringType, unmaskedStringType));
        /* Check that if any arguments are not supported, method returns false */
        assertFalse(provider.supports(username, password, maskedUsername, unmaskedUsername, specialStringType, maskedStringType, unmaskedStringType));
        assertNull(specialStringType.getValue()); /* Expected, since nothing has been assigned */

        assertTrue(provider.get(gitURI, username, password, maskedUsername, unmaskedUsername, specialStringType));

        assertNotNull(username.getValue());
        assertNotNull(password.getValue());
        assertNotNull(maskedUsername.getValue());
        assertNotNull(unmaskedUsername.getValue());
        assertNotNull(specialStringType.getValue());

        assertFalse(username.isValueSecure());
        assertEquals("Username", username.getPromptText());
        assertEquals(expectedUsername, username.getValue());

        assertTrue(password.isValueSecure());
        assertEquals("Password", password.getPromptText());
        assertNotNull(password.getValue());

        assertTrue(maskedUsername.isValueSecure());
        assertEquals(expectedUsername, maskedUsername.getValue().getUsername());
        assertEquals(MASKED_USER_NAME_PROMPT, maskedUsername.getPromptText());

        assertFalse(unmaskedUsername.isValueSecure());
        assertEquals(expectedUsername, unmaskedUsername.getValue().getUsername());
        assertEquals(UNMASKED_USER_NAME_PROMPT, unmaskedUsername.getPromptText());

        assertFalse(specialStringType.isValueSecure());
        assertEquals(SPECIAL_STRING_TYPE_PROMPT, specialStringType.getPromptText());
        assertEquals(secretValue, specialStringType.getValue());
    }

    @Test
    public void testAddDefaultCredentials() {
        String expectedUsername = "expected-add-credentials-username";
        String secretValue = "secret-value";
        Secret secret = Secret.fromString(secretValue);
        StandardUsernamePasswordCredentials credentials = new StandardUsernamePasswordCredentialsImpl(expectedUsername, secret);

        assertFalse(provider.supports(username, password));
        assertFalse(provider.supports(maskedUsername, unmaskedUsername));
        assertFalse(provider.supports(maskedStringType, unmaskedStringType));
        assertFalse(provider.supports(specialStringType));

        provider.addDefaultCredentials(credentials);

        assertTrue(provider.supports(username, password));
        assertTrue(provider.supports(maskedUsername, unmaskedUsername));

        /* The supports() method does not know about the "special" StringType
         * which can be used to return the password plaintext.  Passing a
         * StringType with the prompt text "Password: " will return the plain
         * text password.
         */
        assertFalse(provider.supports(specialStringType));

        assertFalse(provider.supports(maskedStringType, unmaskedStringType));
        /* Check that if any arguments are not supported, method returns false */
        assertFalse(provider.supports(username, password, maskedUsername, unmaskedUsername, specialStringType, maskedStringType, unmaskedStringType));

        assertTrue(provider.get(null, username, password, maskedUsername, unmaskedUsername, specialStringType));

        assertFalse(username.isValueSecure());
        assertEquals("Username", username.getPromptText());
        assertEquals(expectedUsername, username.getValue());

        assertTrue(password.isValueSecure());
        assertEquals("Password", password.getPromptText());
        assertNotNull(password.getValue());

        assertTrue(maskedUsername.isValueSecure());
        assertEquals(expectedUsername, maskedUsername.getValue().getUsername());
        assertEquals(MASKED_USER_NAME_PROMPT, maskedUsername.getPromptText());

        assertFalse(unmaskedUsername.isValueSecure());
        assertEquals(expectedUsername, unmaskedUsername.getValue().getUsername());
        assertEquals(UNMASKED_USER_NAME_PROMPT, unmaskedUsername.getPromptText());

        assertFalse(specialStringType.isValueSecure());
        assertEquals(SPECIAL_STRING_TYPE_PROMPT, specialStringType.getPromptText());
        assertEquals(secretValue, specialStringType.getValue());
    }

    @Test
    public void testIsInteractive() {
        assertFalse(provider.isInteractive());
    }

    @Test
    public void testClearCredentialsItem() {
        String expectedUsername = "expected-add-credentials-username";
        String secretValue = "secret-value";
        Secret secret = Secret.fromString(secretValue);
        StandardUsernamePasswordCredentials credentials = new StandardUsernamePasswordCredentialsImpl(expectedUsername, secret);
        maskedUsername.setValue(credentials);
        assertEquals(credentials, maskedUsername.getValue());
        maskedUsername.clear();
        assertNull(maskedUsername.getValue());
    }

    @Test
    public void testGetThrowsException() {
        String expectedUsername = "expected-add-credentials-username";
        Secret secret = Secret.fromString("password-secret");
        StandardUsernamePasswordCredentials credentials = new StandardUsernamePasswordCredentialsImpl(expectedUsername, secret);
        provider.addCredentials(gitURI.toString(), credentials);
        assertThrows(UnsupportedCredentialItem.class,
                     () ->
                     {
                         provider.get(gitURI, username, password, maskedUsername, unmaskedUsername, maskedStringType);
                     });
    }
}
