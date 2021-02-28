package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.util.StreamTaskListener;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.Repository;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
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
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Git API Test which are solely for CLI git,
 * but doesn't need an initialized working repo.
 * These tests are not implemented for JGit.
 */

@RunWith(Parameterized.class)
public class GitAPITestCliGitNotIntialized {
    @Rule
    public GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule secondRepo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule thirdRepo = new GitClientSampleRepoRule();

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

    public GitAPITestCliGitNotIntialized(final String gitImplName) {
        this.gitImplName = gitImplName;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection gitObjects() {
        List<Object[]> arguments = new ArrayList<>();
        String[] gitImplNames = {"git"};
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

    /* Submodule checkout in JGit does not support renamed submodules.
     * The test branch intentionally includes a renamed submodule, so this test
     * is not run with JGit.
     */
    @Test
    public void testSubmoduleCheckoutSimple() throws Exception {
        workspace.cloneRepo(workspace, workspace.localMirror());
        assertSubmoduleDirs(testGitDir, false, false);

        /* Checkout a branch which includes submodules (in modules directory) */
        String subBranch = "tests/getSubmodules";
        String subRefName = "origin/" + subBranch;
        testGitClient.checkout().ref(subRefName).branch(subBranch).execute();
        assertSubmoduleDirs(testGitDir, true, false);

        testGitClient.submoduleUpdate().recursive(true).execute();
        assertSubmoduleDirs(testGitDir, true, true);
        assertSubmoduleContents(testGitDir);
        assertSubmoduleRepository(new File(testGitDir, "modules/ntp"));
        assertSubmoduleRepository(new File(testGitDir, "modules/firewall"));
        assertSubmoduleRepository(new File(testGitDir, "modules/sshkeys"));
    }

    private void assertSubmoduleRepository(File submoduleDir) throws Exception {
        /* Get a client directly on the submoduleDir */
        GitClient submoduleClient = setupGitAPI(submoduleDir);

        /* Assert that when we invoke the repository callback it gets a
         * functioning repository object
         */
        submoduleClient.withRepository((final Repository repo, VirtualChannel channel) -> {
            assertTrue(repo.getDirectory() + " is not a valid repository",
                    repo.getObjectDatabase().exists());
            return null;
        });
    }

    protected GitClient setupGitAPI(File ws) throws Exception {
        setCliGitDefaults();
        return Git.with(listener, new EnvVars()).in(ws).using(gitImplName).getClient();
    }

    private static boolean cliGitDefaultsSet = false;

    private void setCliGitDefaults() throws Exception {
        if (!cliGitDefaultsSet) {
            CliGitCommand gitCmd = new CliGitCommand(null);
        }
        cliGitDefaultsSet = true;
    }

    private void assertSubmoduleContents(File repo) throws IOException {
        final File modulesDir = new File(repo, "modules");

        final File sshkeysDir = new File(modulesDir, "sshkeys");
        final File sshkeysModuleFile = new File(sshkeysDir, "Modulefile");
        assertFileExists(sshkeysModuleFile);

        final File keeperFile = new File(modulesDir, "keeper");
        final String keeperContent = "";
        assertFileExists(keeperFile);
        assertFileContents(keeperFile, keeperContent);

        final File ntpDir = new File(modulesDir, "ntp");
        final File ntpContributingFile = new File(ntpDir, "CONTRIBUTING.md");
        final String ntpContributingContent = "Puppet Labs modules on the Puppet Forge are open projects";
        assertFileExists(ntpContributingFile);
        assertFileContains(ntpContributingFile, ntpContributingContent); /* Check substring in file */
    }

    private void assertFileContains(File file, String expectedContent) throws IOException {
        assertFileExists(file);
        final String fileContent = FileUtils.readFileToString(file, "UTF-8");
        final String message = file + " does not contain '" + expectedContent + "', contains '" + fileContent + "'";
        assertTrue(message, fileContent.contains(expectedContent));
    }

    private void assertFileContents(File file, String expectedContent) throws IOException {
        assertFileExists(file);
        final String fileContent = FileUtils.readFileToString(file, "UTF-8");
        assertEquals(file + " wrong content", expectedContent, fileContent);
    }

    private void assertSubmoduleDirs(File repo, boolean dirsShouldExist, boolean filesShouldExist) throws IOException {
        final File modulesDir = new File(repo, "modules");
        final File ntpDir = new File(modulesDir, "ntp");
        final File firewallDir = new File(modulesDir, "firewall");
        final File keeperFile = new File(modulesDir, "keeper");
        final File ntpContributingFile = new File(ntpDir, "CONTRIBUTING.md");
        final File sshkeysDir = new File(modulesDir, "sshkeys");
        final File sshkeysModuleFile = new File(sshkeysDir, "Modulefile");
        if (dirsShouldExist) {
            assertDirExists(modulesDir);
            assertDirExists(ntpDir);
            assertDirExists(firewallDir);
            assertDirExists(sshkeysDir);
            /* keeperFile is in the submodules branch, but is a plain file */
            assertFileExists(keeperFile);
        } else {
            assertDirNotFound(modulesDir);
            assertDirNotFound(ntpDir);
            assertDirNotFound(firewallDir);
            assertDirNotFound(sshkeysDir);
            /* keeperFile is in the submodules branch, but is a plain file */
            assertFileNotFound(keeperFile);
        }
        if (filesShouldExist) {
            assertFileExists(ntpContributingFile);
            assertFileExists(sshkeysModuleFile);
        } else {
            assertFileNotFound(ntpContributingFile);
            assertFileNotFound(sshkeysModuleFile);
        }
    }

    private void assertDirNotFound(File dir) {
        assertFileNotFound(dir);
    }

    private void assertFileNotFound(File file) {
        assertFalse(file + " found, peer files: " + listDir(file.getParentFile()), file.exists());
    }

    private void assertDirExists(File dir) {
        assertFileExists(dir);
        assertTrue(dir + " is not a directory", dir.isDirectory());
    }

    private void assertFileExists(File file) {
        assertTrue(file + " not found, peer files: " + listDir(file.getParentFile()), file.exists());
    }

    private String listDir(File dir) {
        if (dir == null || !dir.exists()) {
            return "";
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return "";
        }
        StringBuilder fileList = new StringBuilder();
        for (File file : files) {
            fileList.append(file.getName());
            fileList.append(',');
        }
        if (fileList.length() > 0) {
            fileList.deleteCharAt(fileList.length() - 1);
        }
        return fileList.toString();
    }
}
