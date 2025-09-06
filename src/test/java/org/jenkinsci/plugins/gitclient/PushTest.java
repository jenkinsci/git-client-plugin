package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.*;

import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.*;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test various permutations of push refspecs. JENKINS-20393 highlights that
 * there are multiple allowed refspecs to be handled.
 *
 * Test is parameterized so that the same tests are executed for different
 * combinations of git implementation and refspec.
 *
 * @author Mark Waite
 */
@ParameterizedClass(name = "{0} with {1} refspec {2}")
@MethodSource("pushParameters")
class PushTest {

    @Parameter(0)
    protected String gitImpl;

    @Parameter(1)
    protected String branchName;

    @Parameter(2)
    protected String refSpec;

    @Parameter(3)
    protected Class<Throwable> expectedException;

    private static File bareRepo;
    protected static URIish bareURI;
    private static GitClient bareGitClient;
    private static ObjectId bareFirstCommit;

    private static final String[] BRANCH_NAMES = {"master", "feature/push-test"};

    private ObjectId previousCommit;

    private File workingRepo;
    protected GitClient workingGitClient;
    private ObjectId workingCommit;

    @TempDir
    private static File staticTemporaryFolder;

    @TempDir
    private File temporaryFolder;

    @Test
    void push() throws Exception {
        checkoutBranchAndCommitFile();

        if (expectedException != null) {
            assertThrows(
                    expectedException,
                    () -> workingGitClient.push().to(bareURI).ref(refSpec).execute());
        } else {
            workingGitClient.push().to(bareURI).ref(refSpec).execute();
        }
    }

    @Test
    void pushNonFastForwardForce() throws Exception {
        checkoutOldBranchAndCommitFile();

        if (expectedException != null) {
            assertThrows(expectedException, () -> workingGitClient
                    .push()
                    .to(bareURI)
                    .ref(refSpec)
                    .force(true)
                    .execute());
        } else {
            workingGitClient.push().to(bareURI).ref(refSpec).force(true).execute();
        }
    }

    static List<Arguments> pushParameters() {
        List<Arguments> parameters = new ArrayList<>();
        final String[] implementations = {"git", "jgit"};
        final String[] goodRefSpecs = {
            "{0}", "HEAD", "HEAD:{0}", "{0}:{0}", "refs/heads/{0}", "{0}:heads/{0}", "{0}:refs/heads/{0}"
        };
        final String[] badRefSpecs = {
            /* ":", // JGit fails with "ERROR: branch is currently checked out" */
            /* ":{0}", // CliGitAPIImpl will delete the remote branch with this refspec */
            "this/ref/does/not/exist", "src/ref/does/not/exist:dest/ref/does/not/exist"
        };

        shuffleArray(implementations);
        shuffleArray(goodRefSpecs);
        shuffleArray(badRefSpecs);

        for (String implementation : implementations) {
            for (String branch : BRANCH_NAMES) {
                for (String paramRefSpec : goodRefSpecs) {
                    String spec = MessageFormat.format(paramRefSpec, branch);
                    Arguments parameter = Arguments.of(implementation, branch, spec, null);
                    parameters.add(parameter);
                }
                for (String paramRefSpec : badRefSpecs) {
                    String spec = MessageFormat.format(paramRefSpec, branch);
                    Arguments parameter = Arguments.of(implementation, branch, spec, GitException.class);
                    parameters.add(parameter);
                }
            }
        }
        return parameters;
    }

    @BeforeEach
    void createWorkingRepository() throws Exception {
        hudson.EnvVars env = new hudson.EnvVars();
        TaskListener listener = StreamTaskListener.fromStderr();
        workingRepo = newFolder(temporaryFolder, "junit-" + System.nanoTime());
        workingGitClient =
                Git.with(listener, env).in(workingRepo).using(gitImpl).getClient();
        workingGitClient
                .clone_()
                .url(bareRepo.getAbsolutePath())
                .repositoryName("origin")
                .execute();
        workingGitClient
                .checkout()
                .branch(branchName)
                .deleteBranchIfExist(true)
                .ref("origin/" + branchName)
                .execute();
        assertNotNull(bareFirstCommit);
        assertTrue(
                workingGitClient.revList("origin/" + branchName).contains(bareFirstCommit),
                "Clone does not contain " + bareFirstCommit);
        ObjectId workingHead = workingGitClient.getHeadRev(workingRepo.getAbsolutePath(), branchName);
        ObjectId bareHead = bareGitClient.getHeadRev(bareRepo.getAbsolutePath(), branchName);
        assertEquals(bareHead, workingHead, "Initial checkout of " + branchName + " has different HEAD than bare repo");
        CliGitCommand gitCmd = new CliGitCommand(workingGitClient);
        gitCmd.initializeRepository(
                "Vojtěch PushTest working repo Zweibrücken-Šafařík", "email.from.git.client@example.com");
    }

    @AfterEach
    void verifyPushResultAndDeleteDirectory(TestInfo info) throws Exception {
        /* Confirm push reached bare repo */
        if (expectedException == null
                && !info.getTestMethod().orElseThrow().getName().contains("Throws")) {
            ObjectId latestBareHead = bareGitClient.getHeadRev(bareRepo.getAbsolutePath(), branchName);
            assertEquals(workingCommit, latestBareHead, branchName + " commit not pushed to " + refSpec);
            assertNotEquals(previousCommit, workingCommit);
            assertNotEquals(previousCommit, latestBareHead);
        }
    }

    @BeforeAll
    static void createBareRepository() throws Exception {
        /* Randomly choose git implementation to create bare repository */
        final String[] gitImplementations = {"git", "jgit"};
        Random random = new Random();
        String gitImpl = gitImplementations[random.nextInt(gitImplementations.length)];

        /* Create the bare repository */
        bareRepo = newFolder(staticTemporaryFolder, "junit-" + System.nanoTime());
        bareURI = new URIish(bareRepo.getAbsolutePath());
        hudson.EnvVars env = new hudson.EnvVars();
        TaskListener listener = StreamTaskListener.fromStderr();
        bareGitClient = Git.with(listener, env).in(bareRepo).using(gitImpl).getClient();
        bareGitClient.init_().workspace(bareRepo.getAbsolutePath()).bare(true).execute();

        /* Clone the bare repository into a working copy */
        File cloneRepo = newFolder(staticTemporaryFolder, "junit-" + System.nanoTime());
        GitClient cloneGitClient =
                Git.with(listener, env).in(cloneRepo).using(gitImpl).getClient();
        cloneGitClient
                .clone_()
                .url(bareRepo.getAbsolutePath())
                .repositoryName("origin")
                .execute();
        CliGitCommand gitCmd = new CliGitCommand(cloneGitClient);
        gitCmd.initializeRepository("Vojtěch PushTest Zweibrücken-Šafařík", "email.from.git.client@example.com");

        for (String branchName : BRANCH_NAMES) {
            /* Add a file with random content to the current branch of working repo */
            File added = File.createTempFile("added-", ".txt", cloneRepo);
            String randomContent = java.util.UUID.randomUUID().toString();
            String addedContent = "Initial commit to branch " + branchName + " content '" + randomContent + "'";
            Files.writeString(added.toPath(), addedContent, StandardCharsets.UTF_8);
            cloneGitClient.add(added.getName());
            cloneGitClient.commit(
                    "Initial commit to " + branchName + " file " + added.getName() + " with " + randomContent);
            // checkoutOldBranchAndCommitFile needs at least two commits to the branch
            Files.writeString(added.toPath(), "Another revision " + randomContent, StandardCharsets.UTF_8);
            cloneGitClient.add(added.getName());
            cloneGitClient.commit("Second commit to " + branchName);

            /* Push HEAD of current branch to branchName on the bare repository */
            cloneGitClient.push().to(bareURI).ref("HEAD:" + branchName).execute();
        }

        /* Remember the SHA1 of the first commit */
        bareFirstCommit = bareGitClient.getHeadRev(bareRepo.getAbsolutePath(), "master");
    }

    @AfterAll
    static void removeBareRepository() throws Exception {
        FileUtils.deleteDirectory(bareRepo);
    }

    protected void checkoutBranchAndCommitFile() throws Exception {
        previousCommit = checkoutBranch(false);
        workingCommit = commitFileToCurrentBranch();
    }

    protected void checkoutOldBranchAndCommitFile() throws Exception {
        previousCommit = checkoutBranch(true);
        workingCommit = commitFileToCurrentBranch();
    }

    private Collection<String> getBranchNames(List<Branch> branches) {
        return branches.stream().map(Branch::getName).toList();
    }

    private ObjectId checkoutBranch(boolean useOldCommit) throws Exception {
        /* Checkout branchName */
        workingGitClient.checkoutBranch(branchName, "origin/" + branchName + (useOldCommit ? "^" : ""));
        List<Branch> branches = workingGitClient.getBranchesContaining(branchName, false);
        assertThat(getBranchNames(branches), contains(branchName));
        return bareGitClient.getHeadRev(bareRepo.getAbsolutePath(), branchName);
    }

    private ObjectId commitFileToCurrentBranch() throws Exception {
        /* Add a file with random content to the current branch of working repo */
        File added = File.createTempFile("added-", ".txt", workingRepo);
        String randomContent = java.util.UUID.randomUUID().toString();
        String addedContent = "Push test " + randomContent;
        Files.writeString(added.toPath(), addedContent, StandardCharsets.UTF_8);
        workingGitClient.add(added.getName());
        workingGitClient.commit("Added " + added.getName() + " with " + randomContent);

        /* Confirm file was committed */
        workingCommit = workingGitClient.getHeadRev(workingRepo.getAbsolutePath(), branchName);
        assertNotNull(workingCommit);
        assertNotEquals(bareFirstCommit, workingCommit);

        return workingCommit;
    }

    private static void shuffleArray(String[] ar) {
        Random rnd = new Random();
        for (int i = ar.length - 1; i > 0; i--) {
            int index = rnd.nextInt(i + 1);
            // Simple swap
            String a = ar[index];
            ar[index] = ar[i];
            ar[i] = a;
        }
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
