package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;

import hudson.EnvVars;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Git client maintenance tests.
 *
 * @author Hrushikesh Rao
 */
@ParameterizedClass(name = "{0}")
@MethodSource("gitObjects")
class GitClientMaintenanceTest {

    /* Git implementation name, either "git", "jgit", or "jgitapache". */
    @Parameter(0)
    private String gitImplName;

    /* Git client plugin repository directory. */
    private static File srcRepoDir = null;

    /* Instance of object under test */
    private GitClient gitClient = null;

    private final Random random = new Random();

    @TempDir
    private File tempFolder;

    private File repoRoot = null;
    private LogHandler handler;

    static List<Arguments> gitObjects() {
        List<Arguments> arguments = new ArrayList<>();
        String[] gitImplNames = {"git", "jgit", "jgitapache"};
        for (String gitImplName : gitImplNames) {
            Arguments item = Arguments.of(gitImplName);
            arguments.add(item);
        }
        return arguments;
    }

    /**
     * Mirror the git-client-plugin repo so that the tests have a reasonable and
     * repeatable set of commits, tags, and branches.
     */
    private static File mirrorParent = null;

    @BeforeAll
    static void mirrorUpstreamRepositoryLocally() throws Exception {
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

    @AfterAll
    static void removeMirrorAndSrcRepos() {
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

    @BeforeEach
    void setGitClient() throws Exception {
        repoRoot = newFolder(tempFolder, "junit");
        handler = new LogHandler();
        TaskListener listener = newListener(handler);
        gitClient = Git.with(listener, new EnvVars())
                .in(repoRoot)
                .using(gitImplName)
                .getClient();
        File gitDir = gitClient.withRepository((repo, channel) -> repo.getDirectory());
        assertThat("Premature " + gitDir, gitDir, is(not(anExistingDirectory())));
        gitClient.init_().workspace(repoRoot.getAbsolutePath()).execute();
        assertThat("Missing " + gitDir, gitDir, is(anExistingDirectory()));
        gitClient.setRemoteUrl("origin", srcRepoDir.getAbsolutePath());
        CliGitCommand gitCmd = new CliGitCommand(gitClient);
        gitCmd.initializeRepository(
                "Vojtěch GitClientMaintenanceTest Zweibrücken-Šafařík",
                "email.from.git.client.maintenance@example.com");

        if (gitClient instanceof CliGitAPIImpl cliGitClient) {
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
        return new hudson.util.LogTaskListener(logger, Level.ALL);
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
        final String content = "A random maintenance UUID: %s\n".formatted(UUID.randomUUID());
        return commitFile("One-Maintenance-File.txt", content, commitMessage);
    }

    private ObjectId commitFile(final String path, final String content, final String commitMessage) throws Exception {
        createFile(path, content);
        gitClient.add(path);
        gitClient.commit(commitMessage);
        List<ObjectId> headList = gitClient.revList(Constants.HEAD);
        assertThat(headList.size(), is(greaterThan(0)));
        return headList.get(0);
    }

    private void createFile(String path, String content) throws Exception {
        File aFile = new File(repoRoot, path);
        File parentDir = aFile.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
        try (PrintWriter writer = new PrintWriter(aFile, StandardCharsets.UTF_8)) {
            writer.printf(content);
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
    void test_loose_objects_maintenance() throws Exception {
        if (!looseObjectsSupported) {
            return;
        }

        commitSeveralFiles();

        File objectsPath = new File(repoRoot.getAbsolutePath(), ".git/objects");

        String[] expectedDirList = {"info", "pack"};

        // Assert loose objects are in the objects directory
        String[] looseObjects = objectsPath.list();
        assertThat(Arrays.asList(looseObjects), hasItems(expectedDirList));
        assertThat(
                "Missing expected loose objects in objects dir, only found " + String.join(",", looseObjects),
                looseObjects.length,
                is(greaterThan(2))); // Initially loose objects are present

        // Run the loose objects maintenance task, will create loose-objects pack file
        boolean isExecuted = gitClient.maintenance("loose-objects");
        // Check if maintenance has executed successfully.
        assertThat(isExecuted, is(true));

        // Confirm loose-object pack file is present in the pack directory
        File looseObjectPackFilePath = new File(objectsPath.getAbsolutePath(), "pack");
        String[] looseObjectPackFile = looseObjectPackFilePath.list((dir1, name) -> name.startsWith("loose-"));
        // CLI git 2.41 adds a new ".rev" suffixed file that is ignored in these assertions
        List<String> fileNames = Arrays.asList(looseObjectPackFile);
        List<String> requiredSuffixes = Arrays.asList(".idx", ".pack");
        requiredSuffixes.forEach(expected -> assertThat(fileNames, hasItem(endsWith(expected))));

        // Clean the loose objects present in the repo.
        isExecuted = gitClient.maintenance("loose-objects");

        // Assert that loose objects are no longer in the objects directory
        assertThat(objectsPath.list(), is(arrayContainingInAnyOrder(expectedDirList)));

        assertThat(isExecuted, is(true));
    }

    @Test
    void test_incremental_repack_maintenance() throws Exception {
        String maintenanceTask = "incremental-repack";

        commitSeveralFiles();

        // Run incremental repack maintenance task
        // Need to create pack files to use incremental repack
        assertThat(gitClient.maintenance("gc"), is(!gitImplName.startsWith("jgit"))); // No gc on JGit maintenance

        assertThat(gitClient.maintenance(maintenanceTask), is(incrementalRepackSupported));

        String expectedMessage = getExpectedMessage(maintenanceTask, incrementalRepackSupported);
        assertThat(handler.getMessages(), hasItem(startsWith(expectedMessage)));
    }

    @Test
    void test_commit_graph_maintenance() throws Exception {
        String maintenanceTask = "commit-graph";

        commitSeveralFiles();

        assertThat(gitClient.maintenance(maintenanceTask), is(commitGraphSupported));

        String expectedMessage = getExpectedMessage(maintenanceTask, commitGraphSupported);
        assertThat(handler.getMessages(), hasItem(startsWith(expectedMessage)));
    }

    @Test
    void test_gc_maintenance() throws Exception {
        String maintenanceTask = "gc";

        commitSeveralFiles();

        assertThat(gitClient.maintenance("gc"), is(garbageCollectionSupported));

        String expectedMessage = getExpectedMessage(maintenanceTask, garbageCollectionSupported);
        assertThat(handler.getMessages(), hasItem(startsWith(expectedMessage)));
    }

    @Test
    void test_prefetch_maintenance() throws Exception {
        String maintenanceTask = "prefetch";

        assertThat(gitClient.maintenance("prefetch"), is(prefetchSupported));

        String expectedMessage = getExpectedMessage(maintenanceTask, prefetchSupported);
        assertThat(handler.getMessages(), hasItem(startsWith(expectedMessage)));
    }

    @Test
    void test_error_reported_by_invalid_maintenance_task() throws Exception {
        String maintenanceTask = "invalid-maintenance-task";

        // Should always fail to execute
        assertThat(gitClient.maintenance(maintenanceTask), is(false));

        String expectedMessage = gitImplName.startsWith("jgit")
                ? "JGIT doesn't support git maintenance. Use CLIGIT to execute maintenance tasks."
                : "Error executing invalid-maintenance-task maintenance task";
        assertThat(handler.getMessages(), hasItem(expectedMessage));
    }

    private static File newFolder(File root, String... subDirs) throws Exception {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + result);
        }
        return result;
    }
}
