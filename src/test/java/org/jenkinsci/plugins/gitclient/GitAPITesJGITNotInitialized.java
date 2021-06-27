package org.jenkinsci.plugins.gitclient;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IGitAPI;
import hudson.util.StreamTaskListener;
import org.apache.commons.lang.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

@RunWith(Parameterized.class)
public class GitAPITesJGITNotInitialized {
    @Rule
    public GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule secondRepo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule thirdRepo = new GitClientSampleRepoRule();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private int logCount = 0;
    private final Random random = new Random();
    private static final String LOGGING_STARTED = "Logging started";
    private LogHandler handler = null;
    private TaskListener listener;
    private final String gitImplName;

    private String revParseBranchName = null;

    private int checkoutTimeout = -1;
    private int cloneTimeout = -1;
    private int fetchTimeout = -1;
    private int submoduleUpdateTimeout = -1;


    WorkspaceWithRepo workspace;

    private GitClient testGitClient;
    private File testGitDir;
    private CliGitCommand cliGitCommand;

    public GitAPITesJGITNotInitialized(final String gitImplName) { this.gitImplName = gitImplName; }

    @Parameterized.Parameters(name = "{0}")
    public static Collection gitObjects() {
        List<Object[]> arguments = new ArrayList<>();
        String[] gitImplNames = {"jgit", "jgitapache"};
        for (String gitImplName : gitImplNames) {
            Object[] item = {gitImplName};
            arguments.add(item);
        }
        return arguments;
    }

    @BeforeClass
    public static void loadLocalMirror() throws Exception {
        /* Prime the local mirror cache before other tests run */
        /* Allow 2-5 second delay before priming the cache */
        /* Allow other tests a better chance to prime the cache */
        /* 2-5 second delay is small compared to execution time of this test */
        Random random = new Random();
        Thread.sleep((2 + random.nextInt(4)) * 1000L); // Wait 2-5 seconds before priming the cache
        TaskListener mirrorListener = StreamTaskListener.fromStdout();
        File tempDir = Files.createTempDirectory("PrimeGITAPITest").toFile();
        WorkspaceWithRepo cache = new WorkspaceWithRepo(tempDir, "git", mirrorListener);
        cache.localMirror();
        Util.deleteRecursive(tempDir);
    }

    @Before
    public void setUpRepositories() throws Exception {
        revParseBranchName = null;
        checkoutTimeout = -1;
        cloneTimeout = -1;
        fetchTimeout = -1;
        submoduleUpdateTimeout = -1;

        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        listener.getLogger().println(LOGGING_STARTED);

        workspace = new WorkspaceWithRepo(repo.getRoot(), gitImplName, listener);

        testGitClient = workspace.getGitClient();
        testGitDir = workspace.getGitFileDir();
        cliGitCommand = workspace.getCliGitCommand();
    }

    @After
    public void afterTearDown() throws Exception {
        try {
            String messages = StringUtils.join(handler.getMessages(), ";");
            assertTrue("Logging not started: " + messages, handler.containsMessageSubstring(LOGGING_STARTED));
            assertCheckoutTimeout();
            assertCloneTimeout();
            assertFetchTimeout();
            assertSubmoduleUpdateTimeout();
            assertRevParseCalls(revParseBranchName);
        } finally {
            handler.close();
        }
    }

    private void assertCheckoutTimeout() {
        if (checkoutTimeout > 0) {
            assertSubstringTimeout("git checkout", checkoutTimeout);
        }
    }

    private void assertCloneTimeout() {
        if (cloneTimeout > 0) {
            // clone_() uses "git fetch" internally, not "git clone"
            assertSubstringTimeout("git fetch", cloneTimeout);
        }
    }

    private void assertFetchTimeout() {
        if (fetchTimeout > 0) {
            assertSubstringTimeout("git fetch", fetchTimeout);
        }
    }

    private void assertSubmoduleUpdateTimeout() {
        if (submoduleUpdateTimeout > 0) {
            assertSubstringTimeout("git submodule update", submoduleUpdateTimeout);
        }
    }

    private void assertSubstringTimeout(final String substring, int expectedTimeout) {
        if (!(testGitClient instanceof CliGitAPIImpl)) { // Timeout only implemented in CliGitAPIImpl
            return;
        }
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
        assertThat(messages, is(not(empty())));
        assertThat(substringMessages, is(not(empty())));
        assertThat(substringTimeoutMessages, is(not(empty())));
        assertEquals(substringMessages, substringTimeoutMessages);
    }

    /* JENKINS-33258 detected many calls to git rev-parse. This checks
     * those calls are not being made. The createRevParseBranch call
     * creates a branch whose name is unknown to the tests. This
     * checks that the branch name is not mentioned in a call to
     * git rev-parse.
     */
    private void assertRevParseCalls(String branchName) {
        if (revParseBranchName == null) {
            return;
        }
        String messages = StringUtils.join(handler.getMessages(), ";");
        // Linux uses rev-parse without quotes
        assertFalse("git rev-parse called: " + messages, handler.containsMessageSubstring("rev-parse " + branchName));
        // Windows quotes the rev-parse argument
        assertFalse("git rev-parse called: " + messages, handler.containsMessageSubstring("rev-parse \"" + branchName));
    }

    @Test
    public void testGetSubmoduleUrl() throws Exception {
        workspace.cloneRepo(workspace, workspace.localMirror());
        workspace.launchCommand("git", "checkout", "tests/getSubmodules");
        testGitClient.submoduleInit();

        IGitAPI igit = (IGitAPI) testGitClient;
        assertEquals("https://github.com/puppetlabs/puppetlabs-firewall.git", igit.getSubmoduleUrl("modules/firewall"));

        thrown.expect(GitException.class);
        igit.getSubmoduleUrl("bogus");
    }
}
