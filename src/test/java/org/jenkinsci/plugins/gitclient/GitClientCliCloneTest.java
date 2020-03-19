package org.jenkinsci.plugins.gitclient;

import hudson.model.TaskListener;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

public class GitClientCliCloneTest {

    @Rule
    public GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    private final Random random = new Random();
    private LogHandler handler = null;
    private TaskListener listener;

    private WorkspaceWithRepo workspace;

    private GitClient testGitClient;

    @Before
    public void setUpRepositories() throws Exception {
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + random.nextInt());
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);

        workspace = new WorkspaceWithRepo(repo.getRoot(), "git", listener); // Tests explicitly check CLI git only
        testGitClient = workspace.getGitClient();
    }

    @Test
    public void test_clone_default_timeout_logging() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        assertTimeout(testGitClient, "git fetch", CliGitAPIImpl.TIMEOUT);
    }

    @Test
    public void test_clone_timeout_logging() throws Exception {
        int largerTimeout = CliGitAPIImpl.TIMEOUT + 1 + random.nextInt(600);
        testGitClient.clone_().url(workspace.localMirror()).timeout(largerTimeout).repositoryName("origin").execute();
        assertTimeout(testGitClient, "git fetch", largerTimeout);
    }

    @Test
    public void test_fetch_default_timeout_logging() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        testGitClient.fetch_().from(new URIish("origin"), null).execute();
        assertTimeout(testGitClient, "git fetch", CliGitAPIImpl.TIMEOUT);
    }

    @Test
    public void test_fetch_timeout_logging() throws Exception {
        int largerTimeout = CliGitAPIImpl.TIMEOUT + 1 + random.nextInt(600);
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        testGitClient.fetch_().from(new URIish("origin"), null).timeout(largerTimeout).execute();
        assertTimeout(testGitClient, "git fetch .* origin", largerTimeout);
    }

    @Test
    public void test_checkout_default_timeout_logging() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        testGitClient.checkout().ref("origin/master").execute();
        assertTimeout(testGitClient, "git checkout", CliGitAPIImpl.TIMEOUT);
    }

    @Test
    public void test_checkout_timeout_logging() throws Exception {
        int largerTimeout = CliGitAPIImpl.TIMEOUT + 1 + random.nextInt(600);
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        testGitClient.checkout().timeout(largerTimeout).ref("origin/master").execute();
        assertTimeout(testGitClient, "git checkout", largerTimeout);
    }

    @Test
    public void test_submodule_update_default_timeout_logging() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        testGitClient.checkout().ref("origin/tests/getSubmodules").execute();
        assertTimeout(testGitClient, "git checkout", CliGitAPIImpl.TIMEOUT);
        testGitClient.submoduleUpdate().execute();
        assertTimeout(testGitClient, "git submodule update", CliGitAPIImpl.TIMEOUT);
    }

    @Test
    public void test_submodule_update_timeout_logging() throws Exception {
        int largerTimeout = CliGitAPIImpl.TIMEOUT + 1 + random.nextInt(600);
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        testGitClient.checkout().ref("origin/tests/getSubmodules").execute();
        assertTimeout(testGitClient, "git checkout", CliGitAPIImpl.TIMEOUT);
        testGitClient.submoduleUpdate().timeout(largerTimeout).execute();
        assertTimeout(testGitClient, "git submodule update", largerTimeout);
    }

    protected void assertTimeout(GitClient gitClient, final String substring, int expectedTimeout) {
        List<String> messages = handler.getMessages();
        List<String> substringMessages = new ArrayList<>();
        List<String> substringTimeoutMessages = new ArrayList<>();
        final String messageRegEx = ".*\\b" + substring + "\\b.*"; // the expected substring
        final String timeoutRegEx = messageRegEx
                + " [#] timeout=" + expectedTimeout + "\\b.*"; // # timeout=<value>
        for (String message : messages) {
            if (message.matches(messageRegEx)) {
                substringMessages.add(message);
            }
            if (message.matches(timeoutRegEx)) {
                substringTimeoutMessages.add(message);
            }
        }
        assertThat("No messages logged", messages, is(not(empty())));
        assertThat("No messages matched substring '" + substring + "'", substringMessages, is(not(empty())));
        assertThat("Messages matched substring '" + substring + "', found: " + substringMessages + "\nExpected timeout: " + expectedTimeout, substringTimeoutMessages, is(not(empty())));
        assertThat("Timeout messages", substringTimeoutMessages, is(substringMessages));
    }
}
