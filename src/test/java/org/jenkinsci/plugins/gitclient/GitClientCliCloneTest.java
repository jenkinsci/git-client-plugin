package org.jenkinsci.plugins.gitclient;

import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

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

    /* JENKINS-33258 detected many calls to git rev-parse. This checks
     * those calls are not being made. The checkoutRandomBranch call
     * creates a branch with a random name. The later assertion checks that
     * the random branch name is not mentioned in a call to git rev-parse.
     */
    private String checkoutRandomBranch() throws GitException, InterruptedException {
        String branchName = "rev-parse-branch-" + UUID.randomUUID().toString();
        testGitClient.checkout().ref("origin/master").branch(branchName).execute();
        Set<String> branchNames = testGitClient.getBranches().stream().map(Branch::getName).collect(Collectors.toSet());
        assertThat(branchNames, hasItem(branchName));
        return branchName;
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
        String randomBranchName = checkoutRandomBranch();
        testGitClient.fetch_().from(new URIish("origin"), null).prune(true).execute();
        assertTimeout(testGitClient, "git fetch", CliGitAPIImpl.TIMEOUT);
        assertRevParseNotCalled(testGitClient, randomBranchName);
    }

    @Test
    public void test_fetch_timeout_logging() throws Exception {
        int largerTimeout = CliGitAPIImpl.TIMEOUT + 1 + random.nextInt(600);
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        String randomBranchName = checkoutRandomBranch(); // Check that prune(true) does not call git rev-parse
        testGitClient.fetch_().from(new URIish("origin"), null).prune(true).timeout(largerTimeout).execute();
        assertTimeout(testGitClient, "git fetch .* origin", largerTimeout);
        assertRevParseNotCalled(testGitClient, randomBranchName);
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

    /* JENKINS-33258 detected many calls to git rev-parse. This checks
     * those calls are not being made. The createRevParseBranch call
     * creates a branch whose name is unknown to the tests. This
     * checks that the branch name is not mentioned in a call to
     * git rev-parse.
     */
    private void assertRevParseNotCalled(GitClient gitClient, String unexpectedBranchName) {
        assertLoggedMessage(gitClient, "git rev-parse ", unexpectedBranchName, false);
    }
}
