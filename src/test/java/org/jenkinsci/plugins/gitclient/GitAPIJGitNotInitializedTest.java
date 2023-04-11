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
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
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
public class GitAPIJGitNotInitializedTest {

    @Rule
    public GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    private int logCount = 0;
    private static final String LOGGING_STARTED = "Logging started";
    private LogHandler handler = null;
    private TaskListener listener;
    private final String gitImplName;

    WorkspaceWithRepo workspace;

    private GitClient testGitClient;

    public GitAPIJGitNotInitializedTest(final String gitImplName) {
        this.gitImplName = gitImplName;
    }

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
        /* Prime the local mirror cache before other tests run
         * Allow 2-5 second delay before priming the cache
         * Allow other tests a better chance to prime the cache
         * 2-5 second delay is small compared to execution time of this test
         */
        Random random = new Random();
        Thread.sleep(2000L + random.nextInt(3000)); // Wait 2-5 seconds before priming the cache
        TaskListener mirrorListener = StreamTaskListener.fromStdout();
        File tempDir = Files.createTempDirectory("PrimeGitAPITestJGitNotInitialized").toFile();
        WorkspaceWithRepo cache = new WorkspaceWithRepo(tempDir, "git", mirrorListener);
        cache.localMirror();
        Util.deleteRecursive(tempDir);
    }

    @Before
    public void setUpRepositories() throws Exception {
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
    }

    @After
    public void afterTearDown() {
        try {
            String messages = StringUtils.join(handler.getMessages(), ";");
            assertTrue("Logging not started: " + messages, handler.containsMessageSubstring(LOGGING_STARTED));
        } finally {
            handler.close();
        }
    }

    @Test
    public void testGetSubmoduleUrl() throws Exception {
        workspace.cloneRepo(workspace, workspace.localMirror());
        workspace.launchCommand("git", "checkout", "tests/getSubmodules");
        testGitClient.submoduleInit();

        IGitAPI igit = (IGitAPI) testGitClient;
        assertEquals("https://github.com/puppetlabs/puppetlabs-firewall.git", igit.getSubmoduleUrl("modules/firewall"));

        GitException thrown = assertThrows(GitException.class, () -> igit.getSubmoduleUrl("bogus"));
        assertThat(thrown.getMessage(), is("No such submodule: bogus"));
    }
}
