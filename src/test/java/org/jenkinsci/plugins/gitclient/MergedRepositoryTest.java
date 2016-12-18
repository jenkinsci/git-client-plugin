package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.After;
import org.junit.Before;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Random;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * To be used by tests cases that require a git repository that has branch merges
 */
public abstract class MergedRepositoryTest {

    private final String gitImpl;

    public MergedRepositoryTest(String implementation) {
        gitImpl = implementation;
    }

    GitClient git;
    MergeCommand mergeCmd;

    File readmeOne;

    ObjectId commit1Master;
    ObjectId commit1Branch;
    ObjectId commit2Master;
    ObjectId commit2Branch;

    public final TemporaryDirectoryAllocator temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();

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
        try (PrintWriter writer = new PrintWriter(readme, "UTF-8")) {
            writer.println("# Master Branch README " + randomChar);
        }
        git.add("README.md");
        git.commit("Commit README on master branch");
        commit1Master = git.revParse("HEAD");
        assertTrue("master commit 1 missing on master branch", git.revList("master").contains(commit1Master));
        assertTrue("README missing on master branch", readme.exists());

        // Create branch-1
        readmeOne = new File(repo, "README-branch-1.md");
        git.checkoutBranch("branch-1", "master");
        try (PrintWriter writer = new PrintWriter(readmeOne, "UTF-8")) {
            writer.println("# Branch 1 README " + randomChar);
        }
        git.add(readmeOne.getName());
        git.commit("Commit README on branch 1");
        commit1Branch = git.revParse("HEAD");
        assertFalse("branch commit 1 on master branch", git.revList("master").contains(commit1Branch));
        assertTrue("branch commit 1 missing on branch 1", git.revList("branch-1").contains(commit1Branch));
        assertTrue("Branch README missing on branch 1", readmeOne.exists());
        assertTrue("Master README missing on branch 1", readme.exists());

        // Commit a second change to branch-1
        try (PrintWriter writer = new PrintWriter(readmeOne, "UTF-8")) {
            writer.println("# Branch 1 README " + randomChar);
            writer.println("");
            writer.println("Second change to branch 1 README");
        }
        git.add(readmeOne.getName());
        git.commit("Commit 2nd README change on branch 1");
        commit2Branch = git.revParse("HEAD");
        assertFalse("branch commit 2 on master branch", git.revList("master").contains(commit2Branch));
        assertTrue("branch commit 2 not on branch 1", git.revList("branch-1").contains(commit2Branch));
        assertTrue("Branch README missing on branch 1", readmeOne.exists());
        assertTrue("Master README missing on branch 1", readme.exists());

        // Commit a second change to master branch
        git.checkout("master");
        try (PrintWriter writer = new PrintWriter(readme, "UTF-8")) {
            writer.println("# Master Branch README " + randomChar);
            writer.println("");
            writer.println("Second commit");
        }
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

    @After
    public void removeMergeTestRepo() {
        temporaryDirectoryAllocator.disposeAsync();
    }

}
