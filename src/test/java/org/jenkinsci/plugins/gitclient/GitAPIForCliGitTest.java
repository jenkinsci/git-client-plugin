package org.jenkinsci.plugins.gitclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Git API Test which are solely for CLI git,
 * These tests are not implemented for JGit.
 */
@ParameterizedClass(name = "{0}")
@MethodSource("gitObjects")
public class GitAPIForCliGitTest {

    @RegisterExtension
    private final GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    @RegisterExtension
    private final GitClientSampleRepoRule secondRepo = new GitClientSampleRepoRule();

    private int logCount = 0;
    private static final String LOGGING_STARTED = "Logging started";
    private LogHandler handler = null;
    private TaskListener listener;

    @Parameter(0)
    private String gitImplName;

    private WorkspaceWithRepo workspace;

    private GitClient testGitClient;
    private File testGitDir;

    /**
     * Tests that need the default branch name can use this variable.
     */
    private static String defaultBranchName = "mast" + "er"; // Intentionally split string

    static List<Arguments> gitObjects() {
        List<Arguments> arguments = new ArrayList<>();
        String[] gitImplNames = {"git"};
        for (String gitImplName : gitImplNames) {
            Arguments item = Arguments.of(gitImplName);
            arguments.add(item);
        }
        return arguments;
    }

    @BeforeAll
    static void loadLocalMirror() throws Exception {
        /* Prime the local mirror cache before other tests run */
        /* Allow 2-6 second delay before priming the cache */
        /* Allow other tests a better chance to prime the cache */
        /* 2-6 second delay is small compared to execution time of this test */
        Random random = new Random();
        Thread.sleep(2000L + random.nextInt(4000)); // Wait 2-6 seconds before priming the cache
        TaskListener mirrorListener = StreamTaskListener.fromStdout();
        File tempDir = Files.createTempDirectory("PrimeGitAPITestForCliGit").toFile();
        WorkspaceWithRepo cache = new WorkspaceWithRepo(tempDir, "git", mirrorListener);
        cache.localMirror();
        Util.deleteRecursive(tempDir);
    }

    /**
     * Determine the global default branch name.
     * Command line git is moving towards more inclusive naming.
     * Git 2.32.0 honors the configuration variable `init.defaultBranch` and uses it for the name of the initial branch.
     * This method reads the global configuration and uses it to set the value of `defaultBranchName`.
     */
    @BeforeAll
    static void computeDefaultBranchName() throws Exception {
        File configDir = Files.createTempDirectory("readGitConfig").toFile();
        CliGitCommand getDefaultBranchNameCmd = new CliGitCommand(Git.with(TaskListener.NULL, new hudson.EnvVars())
                .in(configDir)
                .using("git")
                .getClient());
        String[] output = getDefaultBranchNameCmd.runWithoutAssert("config", "--get", "init.defaultBranch");
        for (String s : output) {
            String result = s.trim();
            if (result != null && !result.isEmpty()) {
                defaultBranchName = result;
            }
        }
        assertTrue(configDir.delete(), "Failed to delete temporary readGitConfig directory");
    }

    @BeforeEach
    void setUpRepositories() throws Exception {
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
        workspace.initializeWorkspace();
    }

    @AfterEach
    void afterTearDown() {
        try {
            String messages = StringUtils.join(handler.getMessages(), ";");
            assertTrue(handler.containsMessageSubstring(LOGGING_STARTED), "Logging not started: " + messages);
        } finally {
            handler.close();
        }
    }

    /* Test should move to a class that will also test JGit */
    @Test
    void testPushFromShallowClone() throws Exception {
        WorkspaceWithRepo remote = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        remote.initializeWorkspace();
        remote.commitEmpty("init");
        remote.touch(remote.getGitFileDir(), "file1", "");
        remote.getGitClient().add("file1");
        remote.getGitClient().commit("commit1");
        remote.launchCommand("git", "checkout", "-b", "other");

        workspace.launchCommand(
                "git", "remote", "add", "origin", remote.getGitFileDir().getAbsolutePath());
        workspace.launchCommand("git", "pull", "--depth=1", "origin", defaultBranchName);

        workspace.touch(testGitDir, "file2", "");
        testGitClient.add("file2");
        testGitClient.commit("commit2");
        ObjectId sha1 = workspace.head();

        testGitClient.push("origin", defaultBranchName);
        String remoteSha1 =
                remote.launchCommand("git", "rev-parse", defaultBranchName).substring(0, 40);
        assertEquals(sha1.name(), remoteSha1);
    }
}
