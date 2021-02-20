package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
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

public class CredentialsProviderImplTest {

    private CredentialsProviderImpl provider;
    private TaskListener listener;
    private final String USER_NAME = "user-name";
    private final URIish uri;
    private final String SECRET_VALUE = "secret-credentials-provider-impl-test";

    public CredentialsProviderImplTest() throws URISyntaxException {
        uri = new URIish("git://github.com/jenkinsci/git-client-plugin.git");
    }

    @Before
    public void setUp() {
        Secret secret = Secret.fromString(SECRET_VALUE);
        listener = StreamTaskListener.fromStdout();
        StandardUsernameCredentials cred = new StandardUsernamePasswordCredentialsImpl(USER_NAME, secret);
        provider = new CredentialsProviderImpl(listener, cred);
    }

    @Test
    public void testIsInteractive() {
        assertFalse(provider.isInteractive());
    }

    @Test
    public void testSupportsNullItems() {
        CredentialItem.Username nullItem = null;
        assertFalse(provider.supports(nullItem));
    }

    @Test
    public void testSupportsUsername() {
        CredentialItem.Username username = new CredentialItem.Username();
        assertNull(username.getValue());
        assertTrue(provider.supports(username));
        assertTrue(provider.get(uri, username));
        assertEquals(USER_NAME, username.getValue());
    }

    @Test
    public void testSupportsPassword() {
        CredentialItem.Password password = new CredentialItem.Password();
        assertNull(password.getValue());
        assertTrue(provider.supports(password));
        assertTrue(provider.get(uri, password));
        assertNotNull(password.getValue());
    }

    @Test
    public void testSupportsSpecialStringType() {
        CredentialItem.StringType specialStringType = new CredentialItem.StringType("Password: ", false);
        assertNull(specialStringType.getValue());

        /* The supports() method does not know about the "special" StringType
         * which can be used to return the password plaintext.  Passing a
         * StringType with the prompt text "Password: " will return the plain
         * text password.
         */
        assertFalse(provider.supports(specialStringType));
        assertTrue(provider.get(uri, specialStringType));
        assertEquals(SECRET_VALUE, specialStringType.getValue());
    }

    @Test
    public void testSpecialStringTypeThrowsException() {
        CredentialItem.StringType specialStringType = new CredentialItem.StringType("Bad Password: ", false);
        assertFalse(provider.supports(specialStringType));
        assertThrows(UnsupportedCredentialItem.class,
                     () -> {
                         provider.get(uri, specialStringType);
                     });
    }

    @Test
    public void testThrowsUnsupportedOperationException() {
        CredentialItem.InformationalMessage message = new CredentialItem.InformationalMessage("Some info");
        assertFalse(provider.supports(message));
        assertThrows(UnsupportedCredentialItem.class,
                     () -> {
                         provider.get(uri, message);
                     });
    }

    @Test
    public void testSupportsDisallowed() {
        Secret secret = Secret.fromString(SECRET_VALUE);
        listener = StreamTaskListener.fromStdout();
        StandardUsernameCredentials badCred = new MyUsernameCredentialsImpl(USER_NAME);
        CredentialsProviderImpl badProvider = new CredentialsProviderImpl(listener, badCred);
        CredentialItem.Username username = new CredentialItem.Username();
        assertNull(username.getValue());
        assertFalse(badProvider.supports(username));
        assertFalse(badProvider.get(uri, username));
        assertNull(username.getValue());
    }

    private class MyUsernameCredentialsImpl implements StandardUsernameCredentials {

        private final String username;

        MyUsernameCredentialsImpl(String username) {
            this.username = username;
        }

        public String getDescription() {
            throw new UnsupportedOperationException("Do not call");
        }

        public String getId() {
            throw new UnsupportedOperationException("Do not call");
        }

        public CredentialsScope getScope() {
            throw new UnsupportedOperationException("Do not call");
        }

        public CredentialsDescriptor getDescriptor() {
            throw new UnsupportedOperationException("Do not call");
        }

        public String getUsername() {
            return username;
        }

    }
}
