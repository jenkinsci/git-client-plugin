package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Git client maintenance tests.
 *
 * @author Hrushikesh Rao
 */
@RunWith(Parameterized.class)
public class GitClientMaintenanceTest {

    /* Git implementation name, either "git", "jgit", or "jgitapache". */
    private final String gitImplName;

    /* Git client plugin repository directory. */
    private static File srcRepoDir = null;

    /* Instance of object under test */
    private GitClient gitClient = null;

    private final Random random = new Random();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    private File repoRoot = null;
    private LogHandler handler;

    public GitClientMaintenanceTest(final String gitImplName) throws IOException, InterruptedException {
        this.gitImplName = gitImplName;
    }

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

    /**
     * Mirror the git-client-plugin repo so that the tests have a reasonable and
     * repeatable set of commits, tags, and branches.
     */
    private static File mirrorParent = null;

    @BeforeClass
    public static void mirrorUpstreamRepositoryLocally() throws Exception {
        File currentDir = new File(".");
        CliGitAPIImpl currentDirCliGit = (CliGitAPIImpl) Git.with(TaskListener.NULL, new EnvVars())
                .in(currentDir)
                .using("git")
                .getClient();
        boolean currentDirIsShallow = currentDirCliGit.isShallowRepository();

        mirrorParent = Files.createTempDirectory("mirror").toFile();
        /* Clone mirror into mirrorParent/git-client-plugin.git as a bare repo */
        CliGitCommand mirrorParentGitCmd = new CliGitCommand(Git.with(TaskListener.NULL, new EnvVars())
                .in(mirrorParent)
                .using("git")
                .getClient());
        if (currentDirIsShallow) {
            mirrorParentGitCmd.run(
                    "clone",
                    // "--reference", currentDir.getAbsolutePath(), // --reference of shallow repo fails
                    "--mirror",
                    "https://github.com/jenkinsci/git-client-plugin");
        } else {
            mirrorParentGitCmd.run(
                    "clone",
                    "--reference",
                    currentDir.getAbsolutePath(),
                    "--mirror",
                    "https://github.com/jenkinsci/git-client-plugin");
        }
        File mirrorDir = new File(mirrorParent, "git-client-plugin.git");
        assertThat(
                "Git client mirror repo not created at " + mirrorDir.getAbsolutePath(),
                mirrorDir,
                is(anExistingDirectory()));
        GitClient mirrorClient = Git.with(TaskListener.NULL, new EnvVars())
                .in(mirrorDir)
                .using("git")
                .getClient();
        assertThat(mirrorClient.getTagNames("git-client-1.6.3"), contains("git-client-1.6.3"));

        /* Clone from bare mirrorParent/git-client-plugin.git to working mirrorParent/git-client-plugin */
        mirrorParentGitCmd.run("clone", mirrorDir.getAbsolutePath());
        srcRepoDir = new File(mirrorParent, "git-client-plugin");
    }

    @AfterClass
    public static void removeMirrorAndSrcRepos() throws Exception {
        try {
            FileUtils.deleteDirectory(mirrorParent);
        } catch (IOException ioe) {
            System.out.println("Ignored cleanup failure on " + mirrorParent);
        }
    }

    private boolean garbageCollectionSupported = true;
    private boolean incrementalRepackSupported = true;
    private boolean commitGraphSupported = true;
    private boolean prefetchSupported = true;
    private boolean looseObjectsSupported = true;

    @Before
    public void setGitClient() throws IOException, InterruptedException {
        repoRoot = tempFolder.newFolder();
        handler = new LogHandler();
        TaskListener listener = newListener(handler);
        gitClient = Git.with(listener, new EnvVars())
                .in(repoRoot)
                .using(gitImplName)
                .getClient();
        File gitDir = gitClient.withRepository((repo, channel) -> repo.getDirectory());
        collector.checkThat("Premature " + gitDir, gitDir, is(not(anExistingDirectory())));
        gitClient.init_().workspace(repoRoot.getAbsolutePath()).execute();
        collector.checkThat("Missing " + gitDir, gitDir, is(anExistingDirectory()));
        gitClient.setRemoteUrl("origin", srcRepoDir.getAbsolutePath());
        CliGitCommand gitCmd = new CliGitCommand(gitClient);
        gitCmd.run("config", "user.name", "Vojtěch GitClientMaintenanceTest Zweibrücken-Šafařík");
        gitCmd.run("config", "user.email", "email.from.git.client.maintenance@example.com");
        if (gitClient instanceof CliGitAPIImpl) {
            CliGitAPIImpl cliGitClient = (CliGitAPIImpl) gitClient;
            if (!cliGitClient.isAtLeastVersion(1, 8, 0, 0)) {
                incrementalRepackSupported = false;
                commitGraphSupported = false;
                prefetchSupported = false;
                looseObjectsSupported = false;
            }
            if (!cliGitClient.isAtLeastVersion(2, 19, 0, 0)) {
                prefetchSupported = false;
                looseObjectsSupported = false;
            }
            if (!cliGitClient.isAtLeastVersion(2, 25, 0, 0)) {
                prefetchSupported = false;
                looseObjectsSupported = false;
            }
            if (!cliGitClient.isAtLeastVersion(2, 30, 0, 0)) {
                prefetchSupported = false;
                looseObjectsSupported = false;
            }
        } else {
            garbageCollectionSupported = false;
            incrementalRepackSupported = false;
            commitGraphSupported = false;
            prefetchSupported = false;
            looseObjectsSupported = false;
        }
    }

    private TaskListener newListener(LogHandler handler) {
        handler.setLevel(Level.ALL);
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + random.nextInt());
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        TaskListener listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        return listener;
    }

    private static final String COMMITTED_ONE_TEXT_FILE = "A maintenance file ";

    private void commitSeveralFiles() throws Exception {
        // Commit from 3 to 8 times
        int fileCount = 3 + random.nextInt(5);
        for (int i = 0; i < fileCount; i++) {
            commitOneFile(COMMITTED_ONE_TEXT_FILE + (1000 + random.nextInt(9000)));
        }
    }

    private ObjectId commitOneFile(final String commitMessage) throws Exception {
        final String content = String.format("A random maintenance UUID: %s\n", UUID.randomUUID());
        return commitFile("One-Maintenance-File.txt", content, commitMessage);
    }

    private ObjectId commitFile(final String path, final String content, final String commitMessage) throws Exception {
        createFile(path, content);
        gitClient.add(path);
        gitClient.commit(commitMessage);
        List<ObjectId> headList = gitClient.revList(Constants.HEAD);
        collector.checkThat(headList.size(), is(greaterThan(0)));
        return headList.get(0);
    }

    private void createFile(String path, String content) {
        File aFile = new File(repoRoot, path);
        File parentDir = aFile.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
        try (PrintWriter writer = new PrintWriter(aFile, StandardCharsets.UTF_8)) {
            writer.printf(content);
        } catch (IOException ex) {
            throw new GitException(ex);
        }
    }

    private String getExpectedMessage(String maintenanceTask, boolean expectedResult) {
        if (gitImplName.startsWith("jgit")) {
            return "JGIT doesn't support git maintenance. Use CLIGIT to execute maintenance tasks.";
        }
        return expectedResult
                ? "Git maintenance task " + maintenanceTask + " finished"
                : "Error executing " + maintenanceTask + " maintenance task";
    }

    @Test
    public void test_loose_objects_maintenance() throws Exception {
        if (!looseObjectsSupported) {
            return;
        }

        commitSeveralFiles();

        File objectsPath = new File(repoRoot.getAbsolutePath(), ".git/objects");

        String expectedDirList[] = {"info", "pack"};

        // Assert loose objects are in the objects directory
        String looseObjects[] = objectsPath.list();
        collector.checkThat(Arrays.asList(looseObjects), hasItems(expectedDirList));
        collector.checkThat(
                "Missing expected loose objects in objects dir, only found " + String.join(",", looseObjects),
                looseObjects.length,
                is(greaterThan(2))); // Initially loose objects are present

        // Run the loose objects maintenance task, will create loose-objects pack file
        boolean isExecuted = gitClient.maintenance("loose-objects");
        // Check if maintenance has executed successfully.
        collector.checkThat(isExecuted, is(true));

        // Confirm loose-object pack file is present in the pack directory
        File looseObjectPackFilePath = new File(objectsPath.getAbsolutePath(), "pack");
        String[] looseObjectPackFile = looseObjectPackFilePath.list((dir1, name) -> name.startsWith("loose-"));
        collector.checkThat(
                "Missing expected loose objects in git dir, only found " + String.join(",", looseObjectPackFile),
                looseObjectPackFile.length,
                is(2)); // Contains loose-${hash}.pack and loose-${hash}.idx

        // Clean the loose objects present in the repo.
        isExecuted = gitClient.maintenance("loose-objects");

        // Assert that loose objects are no longer in the objects directory
        collector.checkThat(objectsPath.list(), is(arrayContainingInAnyOrder(expectedDirList)));

        collector.checkThat(isExecuted, is(true));
    }

    @Test
    public void test_incremental_repack_maintenance() throws Exception {
        String maintenanceTask = "incremental-repack";

        commitSeveralFiles();

        // Run incremental repack maintenance task
        // Need to create pack files to use incremental repack
        collector.checkThat(
                gitClient.maintenance("gc"), is(!gitImplName.startsWith("jgit"))); // No gc on JGit maintenace

        collector.checkThat(gitClient.maintenance(maintenanceTask), is(incrementalRepackSupported));

        String expectedMessage = getExpectedMessage(maintenanceTask, incrementalRepackSupported);
        collector.checkThat(handler.getMessages(), hasItem(startsWith(expectedMessage)));
    }

    @Test
    public void test_commit_graph_maintenance() throws Exception {
        String maintenanceTask = "commit-graph";

        commitSeveralFiles();

        collector.checkThat(gitClient.maintenance(maintenanceTask), is(commitGraphSupported));

        String expectedMessage = getExpectedMessage(maintenanceTask, commitGraphSupported);
        collector.checkThat(handler.getMessages(), hasItem(startsWith(expectedMessage)));
    }

    @Test
    public void test_gc_maintenance() throws Exception {
        String maintenanceTask = "gc";

        commitSeveralFiles();

        collector.checkThat(gitClient.maintenance("gc"), is(garbageCollectionSupported));

        String expectedMessage = getExpectedMessage(maintenanceTask, garbageCollectionSupported);
        collector.checkThat(handler.getMessages(), hasItem(startsWith(expectedMessage)));
    }

    @Test
    public void test_prefetch_maintenance() throws Exception {
        String maintenanceTask = "prefetch";

        collector.checkThat(gitClient.maintenance("prefetch"), is(prefetchSupported));

        String expectedMessage = getExpectedMessage(maintenanceTask, prefetchSupported);
        collector.checkThat(handler.getMessages(), hasItem(startsWith(expectedMessage)));
    }

    @Test
    public void test_error_reported_by_invalid_maintenance_task() throws Exception {
        String maintenanceTask = "invalid-maintenance-task";

        // Should always fail to execute
        collector.checkThat(gitClient.maintenance(maintenanceTask), is(false));

        String expectedMessage = gitImplName.startsWith("jgit")
                ? "JGIT doesn't support git maintenance. Use CLIGIT to execute maintenance tasks."
                : "Error executing invalid-maintenance-task maintenance task";
        collector.checkThat(handler.getMessages(), hasItem(expectedMessage));
    }
}
