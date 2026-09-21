package org.jenkinsci.plugins.gitclient;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.jgit.SmartCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.Issue;

/**
 * Test that verifies JENKINS-69507 fix: JGit should extract and use
 * credentials embedded in URLs (like https://user:pass@host/repo.git)
 * for subsequent fetch operations.
 *
 * @author Akash Manna
 */
@Issue("JENKINS-69507")
class JGitEmbeddedCredentialsTest {

    /** Refuses connections immediately, so a fetch fails without reaching the network. */
    private static final String UNREACHABLE_AUTHORITY = "localhost:1";

    @TempDir
    private File tempDir;

    private JGitAPIImpl newClient(String name) throws Exception {
        TaskListener listener = StreamTaskListener.fromStdout();
        File workspace = new File(tempDir, name);
        assertTrue(workspace.mkdirs(), "Failed to create " + workspace);
        return new JGitAPIImpl(workspace, listener);
    }

    private JGitAPIImpl newInitializedClient(String name) throws Exception {
        JGitAPIImpl gitClient = newClient(name);
        gitClient.init_().workspace(gitClient.getWorkTree().getRemote()).execute();
        return gitClient;
    }

    private static void extractCredentials(JGitAPIImpl gitClient, URIish url) throws Exception {
        Method method = JGitAPIImpl.class.getDeclaredMethod("extractAndAddEmbeddedCredentials", URIish.class);
        method.setAccessible(true);
        method.invoke(gitClient, new Object[] {url});
    }

    private static Map<String, StandardCredentials> registeredCredentials(JGitAPIImpl gitClient) {
        Map<String, StandardCredentials> credentials =
                new HashMap<>(gitClient.getProvider().getCredentials());
        credentials.remove("");
        return credentials;
    }

    private static StandardUsernamePasswordCredentials assertRegistered(
            JGitAPIImpl gitClient, String key, String expectedUsername, String expectedPassword) {
        Map<String, StandardCredentials> credentials = registeredCredentials(gitClient);
        StandardCredentials registered = credentials.get(key);
        assertNotNull(registered, "No credentials registered for " + key + ", registered " + credentials.keySet());
        StandardUsernamePasswordCredentials usernamePassword =
                assertInstanceOf(StandardUsernamePasswordCredentials.class, registered);
        assertEquals(expectedUsername, usernamePassword.getUsername());
        assertEquals(expectedPassword, usernamePassword.getPassword().getPlainText());
        return usernamePassword;
    }

    private static void assertProviderAuthenticates(
            SmartCredentialsProvider provider, String url, String expectedUsername, String expectedPassword)
            throws Exception {
        CredentialItem.Username username = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        assertTrue(provider.get(new URIish(url), username, password), "No credentials provided for " + url);
        assertEquals(expectedUsername, username.getValue());
        assertEquals(expectedPassword, new String(password.getValue()));
    }

    @Test
    void testExtractEmbeddedCredentials() throws Exception {
        JGitAPIImpl gitClient = newClient("extract");

        extractCredentials(gitClient, new URIish("https://testuser:testpass@example.com/repo.git"));

        assertEquals(2, registeredCredentials(gitClient).size());
        assertRegistered(gitClient, "https://testuser@example.com/repo", "testuser", "testpass");
        assertRegistered(gitClient, "https://example.com/repo", "testuser", "testpass");
        assertProviderAuthenticates(gitClient.getProvider(), "https://example.com/repo.git", "testuser", "testpass");
        assertProviderAuthenticates(
                gitClient.getProvider(), "https://testuser@example.com/repo.git", "testuser", "testpass");
    }

    @Test
    void testExtractPercentEncodedCredentials() throws Exception {
        JGitAPIImpl gitClient = newClient("encoded");

        extractCredentials(
                gitClient, new URIish("https://user%40domain.com:p%40ss%3Aword@example.com:8443/team/repo.git"));

        assertEquals(2, registeredCredentials(gitClient).size());
        assertRegistered(
                gitClient, "https://user%40domain.com@example.com:8443/team/repo", "user@domain.com", "p@ss:word");
        assertRegistered(gitClient, "https://example.com:8443/team/repo", "user@domain.com", "p@ss:word");
    }

    @Test
    void testExtractCredentialsFromIPv6UrlWithPort() throws Exception {
        JGitAPIImpl gitClient = newClient("ipv6");

        extractCredentials(gitClient, new URIish("https://ipv6user:ipv6pass@[fe80::1]:8443/repo.git"));

        assertEquals(2, registeredCredentials(gitClient).size());
        assertRegistered(gitClient, "https://ipv6user@[fe80::1]:8443/repo", "ipv6user", "ipv6pass");
        assertRegistered(gitClient, "https://[fe80::1]:8443/repo", "ipv6user", "ipv6pass");
    }

    @Test
    void testUrlWithOnlyUsername() throws Exception {
        JGitAPIImpl gitClient = newClient("user-only");

        extractCredentials(gitClient, new URIish("https://testuser@example.com/repo.git"));

        assertTrue(registeredCredentials(gitClient).isEmpty(), "Credentials registered without a password");
    }

    @Test
    void testUrlWithoutCredentials() throws Exception {
        JGitAPIImpl gitClient = newClient("no-credentials");

        extractCredentials(gitClient, new URIish("https://example.com/repo.git"));

        assertTrue(registeredCredentials(gitClient).isEmpty(), "Credentials registered for an anonymous URL");
    }

    @Test
    void testNonHttpUrlsAreIgnored() throws Exception {
        JGitAPIImpl gitClient = newClient("other-protocols");

        extractCredentials(gitClient, new URIish("ssh://sshuser:sshpass@example.com/repo.git"));
        extractCredentials(gitClient, new URIish("git@example.com:jenkinsci/git-client-plugin.git"));
        extractCredentials(
                gitClient,
                new URIish(new File(tempDir, "other-protocols").toURI().toString()));
        extractCredentials(gitClient, null);

        assertTrue(registeredCredentials(gitClient).isEmpty(), "Credentials registered for a non http URL");
    }

    @Test
    void testRepeatedExtractionDoesNotGrowProvider() throws Exception {
        JGitAPIImpl gitClient = newClient("repeated");
        URIish url = new URIish("https://testuser:testpass@example.com/repo.git");

        for (int i = 0; i < 5; i++) {
            extractCredentials(gitClient, url);
        }

        assertEquals(2, registeredCredentials(gitClient).size());
    }

    @Test
    void testEmbeddedCredentialsProvideDescriptor() throws Exception {
        JGitAPIImpl gitClient = newClient("descriptor");

        extractCredentials(gitClient, new URIish("https://testuser:testpass@example.com/repo.git"));

        StandardUsernamePasswordCredentials credentials =
                assertRegistered(gitClient, "https://example.com/repo", "testuser", "testpass");
        CredentialsDescriptor descriptor = credentials.getDescriptor();
        assertNotNull(descriptor, "Embedded credentials have no descriptor");
        assertEquals("Embedded URL credentials", descriptor.getDisplayName());
    }

    @Test
    void testEmbeddedCredentialsEqualsAndHashCode() throws Exception {
        JGitAPIImpl first = newClient("equals-first");
        JGitAPIImpl second = newClient("equals-second");
        JGitAPIImpl third = newClient("equals-third");

        extractCredentials(first, new URIish("https://testuser:testpass@example.com/repo.git"));
        extractCredentials(second, new URIish("https://testuser:testpass@example.com/other.git"));
        extractCredentials(third, new URIish("https://testuser:different@example.com/repo.git"));

        StandardCredentials firstCredentials = registeredCredentials(first).get("https://example.com/repo");
        StandardCredentials secondCredentials = registeredCredentials(second).get("https://example.com/other");
        StandardCredentials thirdCredentials = registeredCredentials(third).get("https://example.com/repo");

        assertEquals(firstCredentials, secondCredentials);
        assertEquals(firstCredentials.hashCode(), secondCredentials.hashCode());
        assertNotEquals(firstCredentials, thirdCredentials);
        assertNotEquals(firstCredentials, null);
        assertNotEquals(firstCredentials, "not a credential");
    }

    @Test
    void testFetchWithEmbeddedCredentialsPopulatesProvider() throws Exception {
        JGitAPIImpl gitClient = newInitializedClient("fetch-url");
        URIish url = new URIish("https://fetchuser:fetchpass@" + UNREACHABLE_AUTHORITY + "/repo.git");
        List<RefSpec> refSpecs = List.of(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));

        assertTrue(registeredCredentials(gitClient).isEmpty());
        assertThrows(
                GitException.class,
                () -> gitClient
                        .fetch_()
                        .from(url, refSpecs)
                        .tags(false)
                        .timeout(1)
                        .execute());

        assertRegistered(gitClient, "https://fetchuser@" + UNREACHABLE_AUTHORITY + "/repo", "fetchuser", "fetchpass");
        assertRegistered(gitClient, "https://" + UNREACHABLE_AUTHORITY + "/repo", "fetchuser", "fetchpass");
        assertProviderAuthenticates(
                gitClient.getProvider(), "https://" + UNREACHABLE_AUTHORITY + "/repo.git", "fetchuser", "fetchpass");
    }

    @Test
    void testFetchByRemoteNameResolvesEmbeddedCredentials() throws Exception {
        JGitAPIImpl gitClient = newInitializedClient("fetch-remote-name");
        String urlWithCredentials = "https://remoteuser:remotepass@" + UNREACHABLE_AUTHORITY + "/repo.git";
        gitClient.setRemoteUrl("origin", urlWithCredentials);
        assertEquals(urlWithCredentials, gitClient.getRemoteUrl("origin"));

        List<RefSpec> refSpecs = List.of(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));
        URIish remoteName = new URIish("origin");

        assertTrue(registeredCredentials(gitClient).isEmpty());
        assertThrows(
                GitException.class,
                () -> gitClient
                        .fetch_()
                        .from(remoteName, refSpecs)
                        .tags(false)
                        .timeout(1)
                        .execute());

        assertRegistered(
                gitClient, "https://remoteuser@" + UNREACHABLE_AUTHORITY + "/repo", "remoteuser", "remotepass");
        assertRegistered(gitClient, "https://" + UNREACHABLE_AUTHORITY + "/repo", "remoteuser", "remotepass");
    }

    @Test
    void testFetchByRemoteNameWithoutEmbeddedCredentials() throws Exception {
        JGitAPIImpl gitClient = newInitializedClient("fetch-remote-name-anonymous");
        gitClient.setRemoteUrl("origin", "https://" + UNREACHABLE_AUTHORITY + "/repo.git");

        List<RefSpec> refSpecs = List.of(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));
        URIish remoteName = new URIish("origin");

        assertThrows(
                GitException.class,
                () -> gitClient
                        .fetch_()
                        .from(remoteName, refSpecs)
                        .tags(false)
                        .timeout(1)
                        .execute());

        assertTrue(registeredCredentials(gitClient).isEmpty(), "Credentials registered for an anonymous remote");
    }

    @Test
    void testFetchFromLocalRepositoryRegistersNoCredentials() throws Exception {
        JGitAPIImpl bare = newInitializedClient("local-source");
        JGitAPIImpl gitClient = newInitializedClient("local-destination");

        URIish source = new URIish(bare.getWorkTree().getRemote());
        List<RefSpec> refSpecs = List.of(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));
        gitClient.fetch_().from(source, refSpecs).tags(false).execute();

        assertTrue(registeredCredentials(gitClient).isEmpty(), "Credentials registered for a local fetch");
    }
}
