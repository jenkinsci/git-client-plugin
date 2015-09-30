package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;

@RunWith(Parameterized.class)
public class MergeCommandTest {

    private final String gitImpl;

    public MergeCommandTest(String implementation) {
        gitImpl = implementation;
    }

    private GitClient git;
    private MergeCommand mergeCmd;

    private File readmeOne;

    private ObjectId commit1Master;
    private ObjectId commit1Branch;
    private ObjectId commit2Master;
    private ObjectId commit2Branch;
    private ObjectId commitConflict;

    public final TemporaryDirectoryAllocator temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void createMergeTestRepo() throws IOException, InterruptedException {
        EnvVars env = new hudson.EnvVars();
        TaskListener listener = StreamTaskListener.fromStdout();
        File repo = temporaryDirectoryAllocator.allocate();
        git = Git.with(listener, env).in(repo).using(gitImpl).getClient();
        git.init_().workspace(repo.getAbsolutePath()).execute();

        // Create a master branch
        char randomChar = (char) ((new Random()).nextInt(26) + 'a');
        File readme = new File(repo, "README.md");
        PrintWriter writer = new PrintWriter(readme, "UTF-8");
        writer.println("# Master Branch README " + randomChar);
        writer.close();
        git.add("README.md");
        git.commit("Commit README on master branch");
        commit1Master = git.revParse("HEAD");
        assertTrue("master commit 1 missing on master branch", git.revList("master").contains(commit1Master));
        assertTrue("README missing on master branch", readme.exists());

        // Create branch-1
        readmeOne = new File(repo, "README-branch-1.md");
        git.checkoutBranch("branch-1", "master");
        writer = new PrintWriter(readmeOne, "UTF-8");
        writer.println("# Branch 1 README " + randomChar);
        writer.close();
        git.add(readmeOne.getName());
        git.commit("Commit README on branch 1");
        commit1Branch = git.revParse("HEAD");
        assertFalse("branch commit 1 on master branch", git.revList("master").contains(commit1Branch));
        assertTrue("branch commit 1 missing on branch 1", git.revList("branch-1").contains(commit1Branch));
        assertTrue("Branch README missing on branch 1", readmeOne.exists());
        assertTrue("Master README missing on branch 1", readme.exists());

        // Commit a second change to branch-1
        writer = new PrintWriter(readmeOne, "UTF-8");
        writer.println("# Branch 1 README " + randomChar);
        writer.println("");
        writer.println("Second change to branch 1 README");
        writer.close();
        git.add(readmeOne.getName());
        git.commit("Commit 2nd README change on branch 1");
        commit2Branch = git.revParse("HEAD");
        assertFalse("branch commit 2 on master branch", git.revList("master").contains(commit2Branch));
        assertTrue("branch commit 2 not on branch 1", git.revList("branch-1").contains(commit2Branch));
        assertTrue("Branch README missing on branch 1", readmeOne.exists());
        assertTrue("Master README missing on branch 1", readme.exists());

        // Commit a second change to master branch
        git.checkout("master");
        writer = new PrintWriter(readme, "UTF-8");
        writer.println("# Master Branch README " + randomChar);
        writer.println("");
        writer.println("Second commit");
        writer.close();
        git.add("README.md");
        git.commit("Commit 2nd README change on master branch");
        commit2Master = git.revParse("HEAD");
        assertTrue("commit 2 not on master branch", git.revListAll().contains(commit2Master));
        assertFalse("Branch commit 2 on master branch unexpectedly", git.revList("master").contains(commit2Branch));
        assertFalse("README 1 on master branch unexpectedly", readmeOne.exists());

        mergeCmd = git.merge();

        assertFalse("branch commit 1 on master branch prematurely", git.revList("master").contains(commit1Branch));
        assertFalse("branch commit 2 on master branch prematurely", git.revList("master").contains(commit2Branch));
    }

    private void createConflictingCommit() throws Exception {
        assertNotNull(git);
        // Create branch-conflict
        git.checkout("master");
        git.branch("branch-conflict");
        git.checkout("branch-conflict");
        PrintWriter writer = new PrintWriter(readmeOne, "UTF-8");
        writer.println("# branch-conflict README with conflicting change");
        writer.close();
        git.add(readmeOne.getName());
        git.commit("Commit conflicting README on branch branch-conflict");
        commitConflict = git.revParse("HEAD");
        assertFalse("branch branch-conflict on master branch", git.revList("master").contains(commitConflict));
        assertTrue("commit commitConflict missing on branch branch-conflict", git.revList("branch-conflict").contains(commitConflict));
        assertTrue("Conflicting README missing on branch branch-conflict", readmeOne.exists());
        git.checkout("master");
    }

    @After
    public void removeMergeTestRepo() {
        temporaryDirectoryAllocator.disposeAsync();
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection gitImplementations() {
        List<Object[]> args = new ArrayList<Object[]>();
        String[] implementations = new String[]{"git", "jgit"};
        for (String implementation : implementations) {
            Object[] gitImpl = {implementation};
            args.add(gitImpl);
        }
        return args;
    }

    @Test
    public void testSetRevisionToMergeCommit1() throws GitException, InterruptedException {
        mergeCmd.setRevisionToMerge(commit1Branch).execute();
        assertTrue("branch commit 1 not on master branch after merge", git.revList("master").contains(commit1Branch));
        assertFalse("branch commit 2 on master branch prematurely", git.revList("master").contains(commit2Branch));
        assertTrue("README 1 missing on master branch", readmeOne.exists());
    }

    @Test
    public void testSetRevisionToMergeCommit2() throws GitException, InterruptedException {
        mergeCmd.setRevisionToMerge(commit2Branch).execute();
        assertTrue("branch commit 1 not on master branch after merge", git.revList("master").contains(commit1Branch));
        assertTrue("branch commit 2 not on master branch after merge", git.revList("master").contains(commit2Branch));
        assertTrue("README 1 missing on master branch", readmeOne.exists());
    }

    private void assertMessageInGitLog(ObjectId head, String substring) throws GitException, InterruptedException {
        List<String> logged = git.showRevision(head);
        boolean found = false;
        for (String logLine : logged) {
            if (logLine.contains(substring)) {
                found = true;
            }
        }
        assertTrue("Message '" + substring + "' not in log '" + logged + "'", found);
    }

    @Test
    public void testCustomMergeMessage() throws GitException, InterruptedException {
        String customMessage = "Custom merge message from test";
        mergeCmd.setMessage(customMessage).setRevisionToMerge(commit2Branch).execute();
        assertMessageInGitLog(git.revParse("HEAD"), customMessage);
    }

    @Test
    public void testDefaultMergeMessage() throws GitException, InterruptedException {
        String defaultMessage = "Merge commit '" + commit2Branch.getName() + "'";
        mergeCmd.setRevisionToMerge(commit2Branch).execute();
        assertMessageInGitLog(git.revParse("HEAD"), defaultMessage);
    }

    @Test
    public void testEmptyMergeMessage() throws GitException, InterruptedException {
        String emptyMessage = "";
        mergeCmd.setMessage(emptyMessage).setRevisionToMerge(commit2Branch).execute();
        /* Asserting an empty string in the merge message is too hard, only check for exceptions thrown */
    }

    @Test
    public void testDefaultStrategy() throws GitException, InterruptedException {
        mergeCmd.setStrategy(MergeCommand.Strategy.DEFAULT).setRevisionToMerge(commit2Branch).execute();
        assertTrue("branch commit 1 not on master branch after merge", git.revList("master").contains(commit1Branch));
        assertTrue("branch commit 2 not on master branch after merge", git.revList("master").contains(commit2Branch));
        assertTrue("README 1 missing on master branch", readmeOne.exists());
    }

    @Test
    public void testResolveStrategy() throws GitException, InterruptedException {
        mergeCmd.setStrategy(MergeCommand.Strategy.RESOLVE).setRevisionToMerge(commit2Branch).execute();
        assertTrue("branch commit 1 not on master branch after merge", git.revList("master").contains(commit1Branch));
        assertTrue("branch commit 2 not on master branch after merge", git.revList("master").contains(commit2Branch));
        assertTrue("README 1 missing on master branch", readmeOne.exists());
    }

    @Test
    public void testRecursiveStrategy() throws GitException, InterruptedException {
        mergeCmd.setStrategy(MergeCommand.Strategy.RECURSIVE).setRevisionToMerge(commit2Branch).execute();
        assertTrue("branch commit 1 not on master branch after merge", git.revList("master").contains(commit1Branch));
        assertTrue("branch commit 2 not on master branch after merge", git.revList("master").contains(commit2Branch));
        assertTrue("README 1 missing on master branch", readmeOne.exists());
    }

    /* Octopus merge strategy is not implemented in JGit, not exposed in CliGitAPIImpl */
    @Test
    public void testOctopusStrategy() throws GitException, InterruptedException {
        mergeCmd.setStrategy(MergeCommand.Strategy.OCTOPUS).setRevisionToMerge(commit2Branch).execute();
        assertTrue("branch commit 1 not on master branch after merge", git.revList("master").contains(commit1Branch));
        assertTrue("branch commit 2 not on master branch after merge", git.revList("master").contains(commit2Branch));
        assertTrue("README 1 missing on master branch", readmeOne.exists());
    }

    @Test
    public void testOursStrategy() throws GitException, InterruptedException {
        mergeCmd.setStrategy(MergeCommand.Strategy.OURS).setRevisionToMerge(commit2Branch).execute();
        assertTrue("branch commit 1 not on master branch after merge", git.revList("master").contains(commit1Branch));
        assertTrue("branch commit 2 not on master branch after merge", git.revList("master").contains(commit2Branch));

        /* Note that next assertion is different than similar assertions */
        assertFalse("README 1 found on master branch, Ours strategy should have not included it", readmeOne.exists());
    }

    @Test
    public void testSubtreeStrategy() throws GitException, InterruptedException {
        mergeCmd.setStrategy(MergeCommand.Strategy.SUBTREE).setRevisionToMerge(commit2Branch).execute();
        assertTrue("branch commit 1 not on master branch after merge", git.revList("master").contains(commit1Branch));
        assertTrue("branch commit 2 not on master branch after merge", git.revList("master").contains(commit2Branch));
        assertTrue("README 1 missing on master branch", readmeOne.exists());
    }

    @Test
    public void testSquash() throws GitException, InterruptedException {
        mergeCmd.setSquash(true).setRevisionToMerge(commit2Branch).execute();
        assertFalse("branch commit 1 on master branch after squash merge", git.revList("master").contains(commit1Branch));
        assertFalse("branch commit 2 on master branch after squash merge", git.revList("master").contains(commit2Branch));
        assertTrue("README 1 missing on master branch", readmeOne.exists());
    }

    @Test
    public void testCommitOnMerge() throws GitException, InterruptedException {
        mergeCmd.setCommit(true).setRevisionToMerge(commit2Branch).execute();
        assertTrue("branch commit 1 not on master branch after merge with commit", git.revList("master").contains(commit1Branch));
        assertTrue("branch commit 2 not on master branch after merge with commit", git.revList("master").contains(commit2Branch));
        assertTrue("README 1 missing in working directory", readmeOne.exists());
    }

    @Test
    public void testNoCommitOnMerge() throws GitException, InterruptedException {
        mergeCmd.setCommit(false).setRevisionToMerge(commit2Branch).execute();
        assertFalse("branch commit 1 on master branch after merge without commit", git.revList("master").contains(commit1Branch));
        assertFalse("branch commit 2 on master branch after merge without commit", git.revList("master").contains(commit2Branch));
        assertTrue("README 1 missing in working directory", readmeOne.exists());
    }

    @Test
    public void testConflictOnMerge() throws Exception {
        createConflictingCommit();
        mergeCmd.setRevisionToMerge(commit2Branch).execute();
        assertTrue("branch commit 1 not on master branch after merge", git.revList("master").contains(commit1Branch));
        assertTrue("branch commit 2 not on master branch after merge", git.revList("master").contains(commit2Branch));
        assertTrue("README 1 missing in working directory", readmeOne.exists());
        thrown.expect(GitException.class);
        thrown.expectMessage(commitConflict.getName());
        mergeCmd.setRevisionToMerge(commitConflict).execute();
    }

    @Test
    public void testConflictNoCommitOnMerge() throws Exception {
        createConflictingCommit();
        mergeCmd.setCommit(false).setRevisionToMerge(commit2Branch).execute();
        assertFalse("branch commit 1 on master branch after merge without commit", git.revList("master").contains(commit1Branch));
        assertFalse("branch commit 2 on master branch after merge without commit", git.revList("master").contains(commit2Branch));
        assertTrue("README 1 missing in working directory", readmeOne.exists());
        thrown.expect(GitException.class);
        thrown.expectMessage(commitConflict.getName());
        mergeCmd.setRevisionToMerge(commitConflict).execute();
    }
}
