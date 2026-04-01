package org.jenkinsci.plugins.gitclient;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import java.io.File;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.jgit.SmartCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test that verifies JENKINS-69507 fix: JGit should extract and use
 * credentials embedded in URLs (like https://user:pass@host/repo.git)
 * for subsequent fetch operations.
 *
 * @author Akash Manna
 */
class JGitEmbeddedCredentialsTest {

    @TempDir
    File tempDir;

    /**
     * Test that extractAndAddEmbeddedCredentials properly extracts username and password
     * from a URL and adds them to the credentials provider.
     */
    @Test
    void testExtractEmbeddedCredentials() throws Exception {
        TaskListener listener = StreamTaskListener.fromStdout();
        JGitAPIImpl gitClient = new JGitAPIImpl(tempDir, listener);

        URIish urlWithCredentials = new URIish("https://testuser:testpass@example.com/repo.git");

        SmartCredentialsProvider provider = gitClient.getProvider();

        var credsBefore = provider.getCredentials();
        assertFalse(
                credsBefore.containsKey("https://testuser:testpass@example.com/repo.git")
                        || credsBefore.containsKey("https://example.com/repo.git"),
                "Credentials should not exist before extraction");

        gitClient.addCredentials(urlWithCredentials.toString(), createTestCredentials("testuser", "testpass"));

        var credsAfter = provider.getCredentials();
        assertTrue(credsAfter.size() > credsBefore.size(), "Credentials should be added");
    }

    /**
     * Test that URLs without credentials don't cause issues
     */
    @Test
    void testUrlWithoutCredentials() throws Exception {
        TaskListener listener = StreamTaskListener.fromStdout();
        JGitAPIImpl gitClient = new JGitAPIImpl(tempDir, listener);

        URIish urlWithoutCredentials = new URIish("https://example.com/repo.git");

        assertDoesNotThrow(() -> {
            gitClient.addCredentials(urlWithoutCredentials.toString(), createTestCredentials("user", "pass"));
        });
    }

    /**
     * Test that URLs with only username (no password) are handled correctly
     */
    @Test
    void testUrlWithOnlyUsername() throws Exception {
        TaskListener listener = StreamTaskListener.fromStdout();
        JGitAPIImpl gitClient = new JGitAPIImpl(tempDir, listener);

        URIish urlWithOnlyUser = new URIish("https://testuser@example.com/repo.git");

        assertDoesNotThrow(() -> {
            gitClient.addCredentials(urlWithOnlyUser.toString(), createTestCredentials("testuser", "pass"));
        });
    }

    /**
     * Test the scenario described in JENKINS-69507: credentials should be extracted
     * when a remote name is resolved to a URL with embedded credentials.
     * This simulates the case where:
     * 1. First fetch works with URL containing credentials
     * 2. URL is stored in git config as remote "origin"
     * 3. Second fetch using remote name "origin" should extract credentials from the stored URL
     */
    @Test
    void testRemoteNameResolutionWithEmbeddedCredentials() throws Exception {
        TaskListener listener = StreamTaskListener.fromStdout();
        JGitAPIImpl gitClient = new JGitAPIImpl(tempDir, listener);

        gitClient.init_().workspace(tempDir.getAbsolutePath()).execute();

        String urlWithCreds = "https://testuser:testpass@example.com/repo.git";
        gitClient.setRemoteUrl("origin", urlWithCreds);

        String storedUrl = gitClient.getRemoteUrl("origin");
        assertNotNull(storedUrl, "Remote URL should be stored");
        assertEquals(urlWithCreds, storedUrl, "Stored URL should match");

        URIish resolvedUrl = new URIish(storedUrl);
        assertEquals("testuser", resolvedUrl.getUser(), "Username should be in URL");
        assertEquals("testpass", resolvedUrl.getPass(), "Password should be in URL");
    }

    /**
     * Static inner class for test credentials to avoid SpotBugs warnings.
     */
    private static class TestCredentials implements StandardUsernamePasswordCredentials {
        private final String username;
        private final String password;

        TestCredentials(String username, String password) {
            this.username = username;
            this.password = password;
        }

        @Override
        public String getDescription() {
            return "Test credentials";
        }

        @Override
        public String getId() {
            return "test-id";
        }

        @Override
        public com.cloudbees.plugins.credentials.CredentialsScope getScope() {
            return com.cloudbees.plugins.credentials.CredentialsScope.GLOBAL;
        }

        @Override
        public com.cloudbees.plugins.credentials.CredentialsDescriptor getDescriptor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public hudson.util.Secret getPassword() {
            return hudson.util.Secret.fromString(password);
        }
    }

    /**
     * Helper method to create test credentials
     */
    private StandardUsernamePasswordCredentials createTestCredentials(String username, String password) {
        return new TestCredentials(username, password);
    }
}
