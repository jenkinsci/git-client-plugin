package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.util.StreamTaskListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@ParameterizedClass(name = "{0}")
@MethodSource("gitImplementations")
class MergeCommandTest {

    private static final String BRANCH_2_README_CONTENT = "# Branch 2 README ";

    @Parameter(0)
    private String gitImpl;

    private GitClient git;
    private MergeCommand mergeCmd;

    private File readmeOne;
    private File readme;

    private ObjectId commit1Master;
    private ObjectId commit1Branch;
    private ObjectId commit2Master;
    private ObjectId commit2Branch;
    private ObjectId commit1Branch2;
    private ObjectId commitConflict;

    @TempDir
    private File tempFolder;

    private static String defaultBranchName = "mast" + "er"; // Intentionally split string

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
    void createMergeTestRepo() throws Exception {
        EnvVars env = new hudson.EnvVars();
        TaskListener listener = StreamTaskListener.fromStdout();
        File repo = newFolder(tempFolder, "junit");
        git = Git.with(listener, env).in(repo).using(gitImpl).getClient();
        git.init_().workspace(repo.getAbsolutePath()).execute();
        CliGitCommand gitCmd = new CliGitCommand(git);
        gitCmd.initializeRepository(
                "Vojtěch MergeCommandTest Zweibrücken-Šafařík", "email.from.git.client@example.com");

        // Create a default branch
        char randomChar = (char) ((new Random()).nextInt(26) + 'a');
        readme = new File(repo, "README.adoc");
        try (PrintWriter writer = new PrintWriter(readme, StandardCharsets.UTF_8)) {
            writer.println("# Default Branch README " + randomChar);
        }
        git.add("README.adoc");
        git.commit("Commit README on default branch");
        commit1Master = git.revParse("HEAD");
        assertTrue(git.revList(defaultBranchName).contains(commit1Master), "master commit 1 missing on default branch");
        assertTrue(readme.exists(), "README missing on default branch");

        // Create branch-1
        readmeOne = new File(repo, "README-branch-1.md");
        git.checkoutBranch("branch-1", defaultBranchName);
        try (PrintWriter writer = new PrintWriter(readmeOne, StandardCharsets.UTF_8)) {
            writer.println("# Branch 1 README " + randomChar);
        }
        git.add(readmeOne.getName());
        git.commit("Commit README on branch 1");
        commit1Branch = git.revParse("HEAD");
        assertFalse(git.revList(defaultBranchName).contains(commit1Branch), "branch commit 1 on default branch");
        assertTrue(git.revList("branch-1").contains(commit1Branch), "branch commit 1 missing on branch 1");
        assertTrue(readmeOne.exists(), "Branch README missing on branch 1");
        assertTrue(readme.exists(), "Master README missing on branch 1");

        // Commit a second change to branch-1
        try (PrintWriter writer = new PrintWriter(readmeOne, StandardCharsets.UTF_8)) {
            writer.println("# Branch 1 README " + randomChar);
            writer.println("");
            writer.println("Second change to branch 1 README");
        }
        git.add(readmeOne.getName());
        git.commit("Commit 2nd README change on branch 1");
        commit2Branch = git.revParse("HEAD");
        assertFalse(git.revList(defaultBranchName).contains(commit2Branch), "branch commit 2 on default branch");
        assertTrue(git.revList("branch-1").contains(commit2Branch), "branch commit 2 not on branch 1");
        assertTrue(readmeOne.exists(), "Branch README missing on branch 1");
        assertTrue(readme.exists(), "Master README missing on branch 1");

        git.checkoutBranch("branch-2", defaultBranchName);
        try (PrintWriter writer = new PrintWriter(readme, StandardCharsets.UTF_8)) {
            writer.println(BRANCH_2_README_CONTENT + randomChar);
            writer.println("");
            writer.println("Changed on branch commit");
        }
        git.add("README.adoc");
        git.commit("Commit README change on branch 2");
        commit1Branch2 = git.revParse("HEAD");
        assertTrue(git.revListAll().contains(commit1Branch2), "Change README commit not on branch 2");
        assertFalse(
                git.revList(defaultBranchName).contains(commit1Branch2),
                "Change README commit on default branch unexpectedly");

        // Commit a second change to default branch
        git.checkout().ref(defaultBranchName).execute();
        try (PrintWriter writer = new PrintWriter(readme, StandardCharsets.UTF_8)) {
            writer.println("# Default Branch README " + randomChar);
            writer.println("");
            writer.println("Second commit");
        }
        git.add("README.adoc");
        git.commit("Commit 2nd README change on default branch");
        commit2Master = git.revParse("HEAD");
        assertTrue(git.revListAll().contains(commit2Master), "commit 2 not on default branch");
        assertFalse(
                git.revList(defaultBranchName).contains(commit2Branch),
                "Branch commit 2 on default branch unexpectedly");
        assertFalse(readmeOne.exists(), "README 1 on default branch unexpectedly");

        mergeCmd = git.merge();

        assertFalse(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 on default branch prematurely");
        assertFalse(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 on default branch prematurely");
    }

    private void createConflictingCommit() throws Exception {
        assertNotNull(git);
        // Create branch-conflict
        git.checkout().ref(defaultBranchName).execute();
        git.branch("branch-conflict");
        git.checkout().ref("branch-conflict").execute();
        try (PrintWriter writer = new PrintWriter(readmeOne, StandardCharsets.UTF_8)) {
            writer.println("# branch-conflict README with conflicting change");
        }
        git.add(readmeOne.getName());
        git.commit("Commit conflicting README on branch branch-conflict");
        commitConflict = git.revParse("HEAD");
        assertFalse(
                git.revList(defaultBranchName).contains(commitConflict), "branch branch-conflict on default branch");
        assertTrue(
                git.revList("branch-conflict").contains(commitConflict),
                "commit commitConflict missing on branch branch-conflict");
        assertTrue(readmeOne.exists(), "Conflicting README missing on branch branch-conflict");
        git.checkout().ref(defaultBranchName).execute();
    }

    static Collection<Arguments> gitImplementations() {
        List<Arguments> args = new ArrayList<>();
        String[] implementations = new String[] {"git", "jgit"};
        for (String implementation : implementations) {
            Arguments gitImpl = Arguments.of(implementation);
            args.add(gitImpl);
        }
        return args;
    }

    @Test
    void testSetRevisionToMergeCommit1() throws Exception {
        mergeCmd.setRevisionToMerge(commit1Branch).execute();
        assertTrue(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 not on default branch after merge");
        assertFalse(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 on default branch prematurely");
        assertTrue(readmeOne.exists(), "README 1 missing on default branch");
    }

    @Test
    void testSetRevisionToMergeCommit2() throws Exception {
        mergeCmd.setRevisionToMerge(commit2Branch).execute();
        assertTrue(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 not on default branch after merge");
        assertTrue(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 not on default branch after merge");
        assertTrue(readmeOne.exists(), "README 1 missing on default branch");
    }

    private void assertMessageInGitLog(ObjectId head, String substring) throws Exception {
        List<String> logged = git.showRevision(head);
        boolean found = false;
        for (String logLine : logged) {
            if (logLine.contains(substring)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Message '" + substring + "' not in log '" + logged + "'");
    }

    @Test
    void testCustomMergeMessage() throws Exception {
        String customMessage = "Custom merge message from test";
        mergeCmd.setMessage(customMessage).setRevisionToMerge(commit2Branch).execute();
        assertMessageInGitLog(git.revParse("HEAD"), customMessage);
    }

    @Test
    void testDefaultMergeMessage() throws Exception {
        String defaultMessage = "Merge commit '" + commit2Branch.getName() + "'";
        mergeCmd.setRevisionToMerge(commit2Branch).execute();
        assertMessageInGitLog(git.revParse("HEAD"), defaultMessage);
    }

    @Test
    void testEmptyMergeMessage() throws Exception {
        String emptyMessage = "";
        mergeCmd.setMessage(emptyMessage).setRevisionToMerge(commit2Branch).execute();
        /* Asserting an empty string in the merge message is too hard, only check for exceptions thrown */
    }

    @Test
    void testDefaultStrategy() throws Exception {
        mergeCmd.setStrategy(MergeCommand.Strategy.DEFAULT)
                .setRevisionToMerge(commit2Branch)
                .execute();
        assertTrue(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 not on default branch after merge");
        assertTrue(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 not on default branch after merge");
        assertTrue(readmeOne.exists(), "README 1 missing on default branch");
    }

    @Test
    void testResolveStrategy() throws Exception {
        mergeCmd.setStrategy(MergeCommand.Strategy.RESOLVE)
                .setRevisionToMerge(commit2Branch)
                .execute();
        assertTrue(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 not on default branch after merge");
        assertTrue(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 not on default branch after merge");
        assertTrue(readmeOne.exists(), "README 1 missing on default branch");
    }

    @Test
    void testRecursiveStrategy() throws Exception {
        mergeCmd.setStrategy(MergeCommand.Strategy.RECURSIVE)
                .setRevisionToMerge(commit2Branch)
                .execute();
        assertTrue(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 not on default branch after merge");
        assertTrue(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 not on default branch after merge");
        assertTrue(readmeOne.exists(), "README 1 missing on default branch");
    }

    @Test
    void testRecursiveTheirsStrategy() throws Exception, IOException {
        mergeCmd.setStrategy(MergeCommand.Strategy.RECURSIVE_THEIRS)
                .setRevisionToMerge(commit1Branch2)
                .execute();
        assertTrue(
                git.revList(defaultBranchName).contains(commit1Branch2),
                "branch 2 commit 1 not on default branch after merge");
        assertTrue(readme.exists(), "README.adoc is missing on master");
        try (FileReader reader = new FileReader(readme);
                BufferedReader br = new BufferedReader(reader)) {
            assertTrue(
                    br.readLine().startsWith(BRANCH_2_README_CONTENT), "README.adoc does not contain expected content");
        }
    }

    /* Octopus merge strategy is not implemented in JGit, not exposed in CliGitAPIImpl */
    @Test
    void testOctopusStrategy() throws Exception {
        mergeCmd.setStrategy(MergeCommand.Strategy.OCTOPUS)
                .setRevisionToMerge(commit2Branch)
                .execute();
        assertTrue(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 not on default branch after merge");
        assertTrue(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 not on default branch after merge");
        assertTrue(readmeOne.exists(), "README 1 missing on default branch");
    }

    @Test
    void testOursStrategy() throws Exception {
        mergeCmd.setStrategy(MergeCommand.Strategy.OURS)
                .setRevisionToMerge(commit2Branch)
                .execute();
        assertTrue(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 not on default branch after merge");
        assertTrue(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 not on default branch after merge");

        /* Note that next assertion is different than similar assertions */
        assertFalse(readmeOne.exists(), "README 1 found on default branch, Ours strategy should have not included it");
    }

    @Test
    void testSubtreeStrategy() throws Exception {
        mergeCmd.setStrategy(MergeCommand.Strategy.SUBTREE)
                .setRevisionToMerge(commit2Branch)
                .execute();
        assertTrue(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 not on default branch after merge");
        assertTrue(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 not on default branch after merge");
        assertTrue(readmeOne.exists(), "README 1 missing on default branch");
    }

    @Test
    void testSquash() throws Exception {
        mergeCmd.setSquash(true).setRevisionToMerge(commit2Branch).execute();
        assertFalse(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 on default branch after squash merge");
        assertFalse(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 on default branch after squash merge");
        assertTrue(readmeOne.exists(), "README 1 missing on default branch");
    }

    @Test
    void testCommitOnMerge() throws Exception {
        mergeCmd.setCommit(true).setRevisionToMerge(commit2Branch).execute();
        assertTrue(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 not on default branch after merge with commit");
        assertTrue(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 not on default branch after merge with commit");
        assertTrue(readmeOne.exists(), "README 1 missing in working directory");
    }

    @Test
    void testNoCommitOnMerge() throws Exception {
        mergeCmd.setCommit(false).setRevisionToMerge(commit2Branch).execute();
        assertFalse(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 on default branch after merge without commit");
        assertFalse(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 on default branch after merge without commit");
        assertTrue(readmeOne.exists(), "README 1 missing in working directory");
    }

    @Test
    void testConflictOnMerge() throws Exception {
        createConflictingCommit();
        mergeCmd.setRevisionToMerge(commit2Branch).execute();
        assertTrue(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 not on default branch after merge");
        assertTrue(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 not on default branch after merge");
        assertTrue(readmeOne.exists(), "README 1 missing in working directory");
        GitException e = assertThrows(GitException.class, () -> mergeCmd.setRevisionToMerge(commitConflict)
                .execute());
        assertThat(e.getMessage(), containsString(commitConflict.getName()));
    }

    @Test
    void testConflictNoCommitOnMerge() throws Exception {
        createConflictingCommit();
        mergeCmd.setCommit(false).setRevisionToMerge(commit2Branch).execute();
        assertFalse(
                git.revList(defaultBranchName).contains(commit1Branch),
                "branch commit 1 on default branch after merge without commit");
        assertFalse(
                git.revList(defaultBranchName).contains(commit2Branch),
                "branch commit 2 on default branch after merge without commit");
        assertTrue(readmeOne.exists(), "README 1 missing in working directory");
        GitException e = assertThrows(GitException.class, () -> mergeCmd.setRevisionToMerge(commitConflict)
                .execute());
        assertThat(e.getMessage(), containsString(commitConflict.getName()));
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
