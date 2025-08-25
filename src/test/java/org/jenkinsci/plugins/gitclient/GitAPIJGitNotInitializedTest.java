package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IGitAPI;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@ParameterizedClass(name = "{0}")
@MethodSource("gitObjects")
class GitAPIJGitNotInitializedTest {

    @RegisterExtension
    private final GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    private int logCount = 0;
    private static final String LOGGING_STARTED = "Logging started";
    private LogHandler handler = null;
    private TaskListener listener;

    @Parameter(0)
    private String gitImplName;

    WorkspaceWithRepo workspace;

    private GitClient testGitClient;

    static List<Arguments> gitObjects() {
        List<Arguments> arguments = new ArrayList<>();
        String[] gitImplNames = {"jgit", "jgitapache"};
        for (String gitImplName : gitImplNames) {
            Arguments item = Arguments.of(gitImplName);
            arguments.add(item);
        }
        return arguments;
    }

    @BeforeAll
    static void loadLocalMirror() throws Exception {
        /* Prime the local mirror cache before other tests run
         * Allow 2-5 second delay before priming the cache
         * Allow other tests a better chance to prime the cache
         * 2-5 second delay is small compared to execution time of this test
         */
        Random random = new Random();
        Thread.sleep(2000L + random.nextInt(3000)); // Wait 2-5 seconds before priming the cache
        TaskListener mirrorListener = StreamTaskListener.fromStdout();
        File tempDir =
                Files.createTempDirectory("PrimeGitAPITestJGitNotInitialized").toFile();
        WorkspaceWithRepo cache = new WorkspaceWithRepo(tempDir, "git", mirrorListener);
        cache.localMirror();
        Util.deleteRecursive(tempDir);
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

    @Test
    void testGetSubmoduleUrl() throws Exception {
        workspace.cloneRepo(workspace, workspace.localMirror());
        workspace.launchCommand("git", "checkout", "tests/getSubmodules");
        testGitClient.submoduleInit();

        IGitAPI igit = (IGitAPI) testGitClient;
        assertEquals("https://github.com/puppetlabs/puppetlabs-firewall.git", igit.getSubmoduleUrl("modules/firewall"));

        GitException thrown = assertThrows(GitException.class, () -> igit.getSubmoduleUrl("bogus"));
        assertThat(thrown.getMessage(), is("No such submodule: bogus"));
    }
}
