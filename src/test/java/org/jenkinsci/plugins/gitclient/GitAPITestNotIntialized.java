package org.jenkinsci.plugins.gitclient;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.IGitAPI;
import hudson.util.StreamTaskListener;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

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
public class GitAPITestNotIntialized {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

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
        final File repoRoot = tempFolder.newFolder();

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

//        workspace = new WorkspaceWithRepo(repoRoot, gitImplName, listener);
//        workspace = new WorkspaceWithRepo(temporaryDirectoryAllocator.allocate(), gitImplName, listener);
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
}
