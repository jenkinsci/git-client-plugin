package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.IGitAPI;
import hudson.remoting.VirtualChannel;
import hudson.util.StreamTaskListener;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Git API Tests which doesn't need a working initialized git repo.
 * Implemented in JUnit 4
 */

@RunWith(Parameterized.class)
public class GitAPITestNotIntialized {

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

    public GitAPITestNotIntialized(final String gitImplName) { this.gitImplName = gitImplName; }

    @Parameterized.Parameters(name = "{0}")
    public static Collection gitObjects() {
        List<Object[]> arguments = new ArrayList<>();
        String[] gitImplNames = {"git", "jgit", "jgitapache"};
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

    @Test
    public void testHasGitRepoWithInvalidGitRepo() throws Exception {
        // Create an empty directory named .git - "corrupt" git repo
        File emptyDotGitDir = workspace.file(".git");
        assertTrue("mkdir .git failed", emptyDotGitDir.mkdir());
        boolean hasGitRepo = testGitClient.hasGitRepo();
        // Don't assert condition if the temp directory is inside the dev dir.
        // CLI git searches up the directory tree seeking a '.git' directory.
        // If it finds such a directory, it uses it.
        if (emptyDotGitDir.getAbsolutePath().contains("target") && emptyDotGitDir.getAbsolutePath().contains("tmp")) {
            return;
        }
        assertFalse("Invalid Git repo reported as valid in " + emptyDotGitDir.getAbsolutePath(), hasGitRepo);
    }

    @Test
    public void testSetSubmoduleUrl() throws Exception {
        workspace.cloneRepo(workspace, workspace.localMirror());
        workspace.launchCommand("git", "checkout", "tests/getSubmodules");
        testGitClient.submoduleInit();

        String DUMMY = "/dummy";
        IGitAPI igit = (IGitAPI) testGitClient;
        igit.setSubmoduleUrl("modules/firewall", DUMMY);

        // create a brand new Git object to make sure it's persisted
        WorkspaceWithRepo subModuleVerify = new WorkspaceWithRepo(testGitDir, gitImplName, TaskListener.NULL);
        IGitAPI subModuleIgit = (IGitAPI) subModuleVerify.getGitClient();
        assertEquals(DUMMY, subModuleIgit.getSubmoduleUrl("modules/firewall"));
    }

    private final String remoteMirrorURL = "https://github.com/jenkinsci/git-client-plugin.git";

    @Issue("JENKINS-23299")
    @Test
    public void testGetHeadRev() throws Exception {
        Map<String, ObjectId> heads = testGitClient.getHeadRev(remoteMirrorURL);
        ObjectId master = testGitClient.getHeadRev(remoteMirrorURL, "refs/heads/master");
        assertEquals("URL is " + remoteMirrorURL + ", heads is " + heads, master, heads.get("refs/heads/master"));

        /* Test with a specific tag reference - JENKINS-23299 */
        ObjectId knownTag = testGitClient.getHeadRev(remoteMirrorURL, "refs/tags/git-client-1.10.0");
        ObjectId expectedTag = ObjectId.fromString("1fb23708d6b639c22383c8073d6e75051b2a63aa"); // commit SHA1
        assertEquals("Wrong SHA1 for git-client-1.10.0 tag", expectedTag, knownTag);
    }


    /**
     * Test getHeadRev with wildcard matching in the branch name.
     * Relies on the branches in the git-client-plugin repository
     * include at least branches named:
     *   master
     *   tests/getSubmodules
     *
     * Also relies on a specific return ordering of the values in the
     * pattern matching performed by getHeadRev, and relies on not
     * having new branches created which match the patterns and will
     * occur earlier than the expected value.
     */
    @Test
    public void testGetHeadRevWildCards() throws Exception {
        Map<String, ObjectId> heads = testGitClient.getHeadRev(workspace.localMirror());
        ObjectId master = testGitClient.getHeadRev(workspace.localMirror(), "refs/heads/master");
        assertEquals("heads is " + heads, heads.get("refs/heads/master"), master);
        ObjectId wildOrigin = testGitClient.getHeadRev(workspace.localMirror(), "*/master");
        assertEquals("heads is " + heads, heads.get("refs/heads/master"), wildOrigin);
        ObjectId master1 = testGitClient.getHeadRev(workspace.localMirror(), "not-a-real-origin-but-allowed/m*ster"); // matches master
        assertEquals("heads is " + heads, heads.get("refs/heads/master"), master1);
        ObjectId getSubmodules1 = testGitClient.getHeadRev(workspace.localMirror(), "X/g*[b]m*dul*"); // matches tests/getSubmodules
        assertEquals("heads is " + heads, heads.get("refs/heads/tests/getSubmodules"), getSubmodules1);
        ObjectId getSubmodules = testGitClient.getHeadRev(workspace.localMirror(), "N/*et*modul*");
        assertEquals("heads is " + heads, heads.get("refs/heads/tests/getSubmodules"), getSubmodules);
    }

    /* Opening a git repository in a directory with a symbolic git file instead
     * of a git directory should function properly.
     */
    @Test
    public void testWithRepositoryWorksWithSubmodule() throws Exception {
        workspace.cloneRepo(workspace, workspace.localMirror());
        assertSubmoduleDirs(testGitDir, false, false);

        /* Checkout a branch which includes submodules (in modules directory) */
        String subBranch = testGitClient instanceof CliGitAPIImpl ? "tests/getSubmodules" : "tests/getSubmodules-jgit";
        String subRefName = "origin/" + subBranch;
        testGitClient.checkout().ref(subRefName).branch(subBranch).execute();
        testGitClient.submoduleInit();
        testGitClient.submoduleUpdate().recursive(true).execute();
        assertSubmoduleRepository(new File(testGitDir, "modules/ntp"));
        assertSubmoduleRepository(new File(testGitDir, "modules/firewall"));
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
