package org.jenkinsci.plugins.gitclient;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.io.FileMatchers.aReadableFile;
import static org.junit.Assert.assertThrows;
import org.jvnet.hudson.test.Issue;

/*
 * Tests that are specific to command line git.
 */
public class GitClientCliCloneTest {

    @Rule
    public GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    private final Random random = new Random();
    private LogHandler handler = null;
    private TaskListener listener;

    private WorkspaceWithRepo workspace;

    private GitClient testGitClient;

    @BeforeClass
    public static void loadLocalMirror() throws Exception {
        /* Prime the local mirror cache before other tests run */
        TaskListener mirrorListener = StreamTaskListener.fromStdout();
        File tempDir = Files.createTempDirectory("PrimeCliCloneTest").toFile();
        WorkspaceWithRepo cache = new WorkspaceWithRepo(tempDir, "git", mirrorListener);
        cache.localMirror();
        Util.deleteRecursive(tempDir);
    }

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
    public void test_submodule_update_timeout_logging() throws Exception {
        int largerTimeout = CliGitAPIImpl.TIMEOUT + 1 + random.nextInt(600);
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        testGitClient.checkout().ref("origin/tests/getSubmodules").execute();
        assertTimeout(testGitClient, "git checkout", CliGitAPIImpl.TIMEOUT);
        testGitClient.submoduleUpdate().timeout(largerTimeout).execute();
        assertTimeout(testGitClient, "git submodule update", largerTimeout);
    }

    @Issue("JENKINS-25353")
    @Test
    public void test_checkout_interrupted() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        File lockFile = new File(workspace.getGitFileDir(), "index.lock");
        assertThat("Lock file", lockFile, is(not(aReadableFile())));
        String exceptionMsg = "test checkout intentionally interrupted";
        /* Configure next checkout to fail with an exception */
        CliGitAPIImpl cli = workspace.cgit();
        cli.interruptNextCheckoutWithMessage(exceptionMsg);
        Exception exception = assertThrows(InterruptedException.class, () -> {
            cli.checkout().ref("6b7bbcb8f0e51668ddba349b683fb06b4bd9d0ea").execute(); // git-client-1.6.0
        });
        assertThat(exception.getMessage(), is(exceptionMsg)); // Except exact exception message returned
        assertThat("Lock file removed by checkout", lockFile, is(not(aReadableFile())));
    }

    @Issue("JENKINS-25353")
    @Test
    public void test_checkout_interrupted_with_existing_lock() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        File lockFile = new File(workspace.getGitFileDir(), "index.lock");
        boolean created = lockFile.createNewFile();
        assertThat("Lock file creation failed " + lockFile.getAbsolutePath(), created, is(true));
        assertThat("Lock file", lockFile, is(aReadableFile()));
        Thread.sleep(1800); // Wait 1.8 seconds to "age" the lock file - lock created before checkout
        String exceptionMsg = "test checkout intentionally interrupted";
        /* Configure next checkout to fail with an exception */
        CliGitAPIImpl cli = workspace.cgit();
        cli.interruptNextCheckoutWithMessage(exceptionMsg);
        Exception exception = assertThrows(InterruptedException.class, () -> {
            cli.checkout().ref("6b7bbcb8f0e51668ddba349b683fb06b4bd9d0ea").execute(); // git-client-1.6.0
        });
        assertThat(exception.getMessage(), containsString(exceptionMsg));
        assertThat("Lock file removed by checkout", lockFile, is(aReadableFile()));
    }

    private void assertLoggedMessage(GitClient gitClient, final String candidateSubstring, final String expectedValue, final boolean expectToFindMatch) {
        List<String> messages = handler.getMessages();
        List<String> candidateMessages = new ArrayList<>();
        List<String> matchedMessages = new ArrayList<>();
        final String messageRegEx = ".*\\b" + candidateSubstring + "\\b.*"; // the expected substring
        final String timeoutRegEx = messageRegEx + expectedValue + "\\b.*"; // # timeout=<value>
        for (String message : messages) {
            if (message.matches(messageRegEx)) {
                candidateMessages.add(message);
            }
            if (message.matches(timeoutRegEx)) {
                matchedMessages.add(message);
            }
        }
        assertThat("No messages logged", messages, is(not(empty())));
        if (expectToFindMatch) {
            assertThat("No messages matched substring '" + candidateSubstring + "'", candidateMessages, is(not(empty())));
            assertThat("Messages matched substring '" + candidateSubstring + "', found: " + candidateMessages + "\nExpected " + expectedValue, matchedMessages, is(not(empty())));
            assertThat("All candidate messages matched", matchedMessages, is(candidateMessages));
        } else {
            assertThat("Messages matched substring '" + candidateSubstring + "' unexpectedly", candidateMessages, is(empty()));
        }
    }

    private void assertTimeout(GitClient gitClient, final String substring, int expectedTimeout) {
        assertLoggedMessage(gitClient, substring, " [#] timeout=" + expectedTimeout, true);
    }
}
