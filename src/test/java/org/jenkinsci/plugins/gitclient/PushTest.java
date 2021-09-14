package org.jenkinsci.plugins.gitclient;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import static java.util.stream.Collectors.toList;

import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.util.StreamTaskListener;

import org.apache.commons.io.FileUtils;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import org.junit.After;
import org.junit.AfterClass;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Test various permutations of push refspecs. JENKINS-20393 highlights that
 * there are multiple allowed refspecs to be handled.
 *
 * Test is parameterized so that the same tests are executed for different
 * combinations of git implementation and refspec.
 *
 * @author Mark Waite
 */
@RunWith(Parameterized.class)
public class PushTest {

    private final String gitImpl;
    protected final String refSpec;
    private final String branchName;
    private final Class<Throwable> expectedException;

    private static File bareRepo;
    protected static URIish bareURI;
    private static GitClient bareGitClient;
    private static ObjectId bareFirstCommit;

    private static final String BRANCH_NAMES[] = {"master", "feature/push-test"};

    private ObjectId previousCommit;

    private File workingRepo;
    protected GitClient workingGitClient;
    private ObjectId workingCommit;

    @Rule
    public TestName name = new TestName();

    @ClassRule
    public static TemporaryFolder staticTemporaryFolder = new TemporaryFolder();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    public PushTest(String gitImpl, String branchName, String refSpec, Class<Throwable> expectedException) {
        this.gitImpl = gitImpl;
        this.branchName = branchName;
        this.refSpec = refSpec;
        this.expectedException = expectedException;
    }

    @Test
    public void push() throws IOException, GitException, InterruptedException, URISyntaxException {
        checkoutBranchAndCommitFile();

        if (expectedException != null) {
            assertThrows(expectedException,
                         () -> {
                             workingGitClient.push().to(bareURI).ref(refSpec).execute();
                         });
        } else {
            workingGitClient.push().to(bareURI).ref(refSpec).execute();
        }
    }

    @Test
    public void pushNonFastForwardForce() throws IOException, GitException, InterruptedException, URISyntaxException {
        checkoutOldBranchAndCommitFile();

        if (expectedException != null) {
            assertThrows(expectedException,
                         () -> {
                             workingGitClient.push().to(bareURI).ref(refSpec).force(true).execute();
                         });
        } else {
            workingGitClient.push().to(bareURI).ref(refSpec).force(true).execute();
        }
    }

    @Parameterized.Parameters(name = "{0} with {1} refspec {2}")
    public static Collection pushParameters() {
        List<Object[]> parameters = new ArrayList<>();
        final String[] implementations = {"git", "jgit"};
        final String[] goodRefSpecs = {
            "{0}",
            "HEAD",
            "HEAD:{0}",
            "{0}:{0}",
            "refs/heads/{0}",
            "{0}:heads/{0}",
            "{0}:refs/heads/{0}"
        };
        final String[] badRefSpecs = {
            /* ":", // JGit fails with "ERROR: branch is currently checked out" */
            /* ":{0}", // CliGitAPIImpl will delete the remote branch with this refspec */
            "this/ref/does/not/exist",
            "src/ref/does/not/exist:dest/ref/does/not/exist"
        };

        shuffleArray(implementations);
        shuffleArray(goodRefSpecs);
        shuffleArray(badRefSpecs);

        for (String implementation : implementations) {
            for (String branch : BRANCH_NAMES) {
                for (String paramRefSpec : goodRefSpecs) {
                    String spec = MessageFormat.format(paramRefSpec, branch);
                    Object[] parameter = {implementation, branch, spec, null};
                    parameters.add(parameter);
                }
                for (String paramRefSpec : badRefSpecs) {
                    String spec = MessageFormat.format(paramRefSpec, branch);
                    Object[] parameter = {implementation, branch, spec, GitException.class};
                    parameters.add(parameter);
                }
            }
        }
        return parameters;
    }

    @Before
    public void createWorkingRepository() throws IOException, InterruptedException, URISyntaxException {
        hudson.EnvVars env = new hudson.EnvVars();
        TaskListener listener = StreamTaskListener.fromStderr();
        List<RefSpec> refSpecs = new ArrayList<>();
        workingRepo = temporaryFolder.newFolder();
        workingGitClient = Git.with(listener, env).in(workingRepo).using(gitImpl).getClient();
        workingGitClient.clone_()
                .url(bareRepo.getAbsolutePath())
                .repositoryName("origin")
                .execute();
        workingGitClient.checkout()
                .branch(branchName)
                .deleteBranchIfExist(true)
                .ref("origin/" + branchName)
                .execute();
        assertNotNull(bareFirstCommit);
        assertTrue("Clone does not contain " + bareFirstCommit,
                workingGitClient.revList("origin/" + branchName).contains(bareFirstCommit));
        ObjectId workingHead = workingGitClient.getHeadRev(workingRepo.getAbsolutePath(), branchName);
        ObjectId bareHead = bareGitClient.getHeadRev(bareRepo.getAbsolutePath(), branchName);
        assertEquals("Initial checkout of " + branchName + " has different HEAD than bare repo", bareHead, workingHead);
        CliGitCommand gitCmd = new CliGitCommand(workingGitClient);
        gitCmd.run("config", "user.name", "Vojtěch PushTest working repo Zweibrücken-Šafařík");
        gitCmd.run("config", "user.email", "email.from.git.client@example.com");
    }

    @After
    public void verifyPushResultAndDeleteDirectory() throws GitException, InterruptedException, IOException {
        /* Confirm push reached bare repo */
        if (expectedException == null && !name.getMethodName().contains("Throws")) {
            ObjectId latestBareHead = bareGitClient.getHeadRev(bareRepo.getAbsolutePath(), branchName);
            assertEquals(branchName + " commit not pushed to " + refSpec, workingCommit, latestBareHead);
            assertNotEquals(previousCommit, workingCommit);
            assertNotEquals(previousCommit, latestBareHead);
        }
    }

    @BeforeClass
    public static void createBareRepository() throws Exception {
        /* Randomly choose git implementation to create bare repository */
        final String[] gitImplementations = {"git", "jgit"};
        Random random = new Random();
        String gitImpl = gitImplementations[random.nextInt(gitImplementations.length)];

        /* Create the bare repository */
        bareRepo = staticTemporaryFolder.newFolder();
        bareURI = new URIish(bareRepo.getAbsolutePath());
        hudson.EnvVars env = new hudson.EnvVars();
        TaskListener listener = StreamTaskListener.fromStderr();
        bareGitClient = Git.with(listener, env).in(bareRepo).using(gitImpl).getClient();
        bareGitClient.init_().workspace(bareRepo.getAbsolutePath()).bare(true).execute();

        /* Clone the bare repository into a working copy */
        File cloneRepo = staticTemporaryFolder.newFolder();
        GitClient cloneGitClient = Git.with(listener, env).in(cloneRepo).using(gitImpl).getClient();
        cloneGitClient.clone_()
                .url(bareRepo.getAbsolutePath())
                .repositoryName("origin")
                .execute();
        CliGitCommand gitCmd = new CliGitCommand(cloneGitClient);
        gitCmd.run("config", "user.name", "Vojtěch PushTest Zweibrücken-Šafařík");
        gitCmd.run("config", "user.email", "email.from.git.client@example.com");

        for (String branchName : BRANCH_NAMES) {
            /* Add a file with random content to the current branch of working repo */
            File added = File.createTempFile("added-", ".txt", cloneRepo);
            String randomContent = java.util.UUID.randomUUID().toString();
            String addedContent = "Initial commit to branch " + branchName + " content '" + randomContent + "'";
            FileUtils.writeStringToFile(added, addedContent, "UTF-8");
            cloneGitClient.add(added.getName());
            cloneGitClient.commit("Initial commit to " + branchName + " file " + added.getName() + " with " + randomContent);
            // checkoutOldBranchAndCommitFile needs at least two commits to the branch
            FileUtils.writeStringToFile(added, "Another revision " + randomContent, "UTF-8");
            cloneGitClient.add(added.getName());
            cloneGitClient.commit("Second commit to " + branchName);

            /* Push HEAD of current branch to branchName on the bare repository */
            cloneGitClient.push().to(bareURI).ref("HEAD:" + branchName).execute();
        }

        /* Remember the SHA1 of the first commit */
        bareFirstCommit = bareGitClient.getHeadRev(bareRepo.getAbsolutePath(), "master");
    }

    @AfterClass
    public static void removeBareRepository() throws IOException {
        /* JGit 5.3.1 has an open file handle leak in this test that does not exist in 5.3.0 and earlier */
        /* This conditional silences the JGit 5.3.1 failure */
        if (!isWindows()) {
            FileUtils.deleteDirectory(bareRepo);
        } else {
            try {
                FileUtils.deleteDirectory(bareRepo);
            } catch (IOException ioe) {
                System.err.println("**** Ignored bare repo delete directory cleanup failure:\n" + ioe);
            }
        }
    }

    protected void checkoutBranchAndCommitFile() throws GitException, InterruptedException, IOException {
        previousCommit = checkoutBranch(false);
        workingCommit = commitFileToCurrentBranch();
    }

    protected void checkoutOldBranchAndCommitFile() throws GitException, InterruptedException, IOException {
        previousCommit = checkoutBranch(true);
        workingCommit = commitFileToCurrentBranch();
    }

    private Collection<String> getBranchNames(List<Branch> branches) {
        return branches.stream().map(Branch::getName).collect(toList());
    }

    private ObjectId checkoutBranch(boolean useOldCommit) throws GitException, InterruptedException {
        /* Checkout branchName */
        workingGitClient.checkoutBranch(branchName, "origin/" + branchName + (useOldCommit ? "^" : ""));
        List<Branch> branches = workingGitClient.getBranchesContaining(branchName, false);
        assertThat(getBranchNames(branches), contains(branchName));
        return bareGitClient.getHeadRev(bareRepo.getAbsolutePath(), branchName);
    }

    private ObjectId commitFileToCurrentBranch() throws InterruptedException, GitException, IOException {
        /* Add a file with random content to the current branch of working repo */
        File added = File.createTempFile("added-", ".txt", workingRepo);
        String randomContent = java.util.UUID.randomUUID().toString();
        String addedContent = "Push test " + randomContent;
        FileUtils.writeStringToFile(added, addedContent, "UTF-8");
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

    /** inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue */
    private static boolean isWindows() {
        return File.pathSeparatorChar==';';
    }
}
