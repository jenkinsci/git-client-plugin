package org.jenkinsci.plugins.gitclient;

import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.io.FileMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(Parameterized.class)
public class GitClientFetchTest {

    @Rule
    public GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule secondRepo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule thirdRepo = new GitClientSampleRepoRule();

    WorkspaceWithRepoRule workspace;
    WorkspaceWithRepoRule bareWorkspace;
    WorkspaceWithRepoRule newAreaWorkspace;

    private GitClient testGitClient;
    private File testGitDir;
    private CliGitCommand cliGitCommand;
    private final String gitImplName;

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

    public GitClientFetchTest(final String gitImplName) {
        this.gitImplName = gitImplName;
    }

    @Before
    public void setUpRepositories() throws Exception {
        workspace = new WorkspaceWithRepoRule(repo, gitImplName, TaskListener.NULL);
        testGitClient = workspace.getGitClient();
        testGitDir = workspace.getGitFileDir();
        cliGitCommand = workspace.getCliGitCommand();
    }

    /* Workspace -> original repo, bareWorkspace -> bare repo and newAreaWorkspace -> newArea repo */
    @Test
    public void test_fetch() throws Exception {
        /* Create a working repo containing a commit */
        testGitClient.init();
        workspace.touch(testGitDir, "file1", "file1 content " + UUID.randomUUID().toString());
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        ObjectId commit1 = testGitClient.revParse("HEAD");

        /* Clone working repo into a bare repo */
        bareWorkspace = new WorkspaceWithRepoRule(secondRepo, gitImplName, TaskListener.NULL);
        bareWorkspace.initBareRepo(bareWorkspace.getGitClient(), true);
        testGitClient.setRemoteUrl("origin", bareWorkspace.getGitFileDir().getAbsolutePath());
        Set<Branch> remoteBranchesEmpty = testGitClient.getRemoteBranches();
        assertThat(remoteBranchesEmpty, is(empty()));
        testGitClient.push("origin", "master");
        ObjectId bareCommit1 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "master");
        assertEquals("bare != working", commit1, bareCommit1);
        assertEquals(commit1, bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "refs/heads/master"));

        /* Clone new working repo from bare repo */
        newAreaWorkspace = new WorkspaceWithRepoRule(thirdRepo, gitImplName, TaskListener.NULL);
        newAreaWorkspace.cloneRepo(newAreaWorkspace, bareWorkspace.getGitFileDir().getAbsolutePath());
        ObjectId newAreaHead = newAreaWorkspace.getGitClient().revParse("HEAD");
        assertEquals("bare != newArea", bareCommit1, newAreaHead);
        Set<Branch> remoteBranches1 = newAreaWorkspace.getGitClient().getRemoteBranches();
        assertThat(getBranchNames(remoteBranches1), hasItems("origin/master"));
        assertEquals(bareCommit1, newAreaWorkspace.getGitClient().getHeadRev(newAreaWorkspace.getGitFileDir().getAbsolutePath(), "refs/heads/master"));

        /* Commit a new change to the original repo */
        workspace.touch(testGitDir, "file2", "file2 content " + UUID.randomUUID().toString());
        testGitClient.add("file2");
        testGitClient.commit("commit2");
        ObjectId commit2 = testGitClient.revParse("HEAD");
        assertEquals(commit2, testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "refs/heads/master"));

        /* Push the new change to the bare repo */
        testGitClient.push("origin", "master");
        ObjectId bareCommit2 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "master");
        assertEquals("bare2 != working2", commit2, bareCommit2);
        assertEquals(commit2, bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "refs/heads/master"));

        /* Fetch new change into newArea repo */
        RefSpec defaultRefSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        List<RefSpec> refSpecs = new ArrayList<>();
        refSpecs.add(defaultRefSpec);
        newAreaWorkspace.launchCommand("git", "config", "fetch.prune", "false");
        newAreaWorkspace.getGitClient().fetch(new URIish(bareWorkspace.getGitFileDir().toString()), refSpecs);

        /* Confirm the fetch did not alter working branch */
        assertEquals("beforeMerge != commit1", commit1, newAreaWorkspace.getGitClient().revParse("HEAD"));

        /* Merge the fetch results into working branch */
        newAreaWorkspace.getGitClient().merge().setRevisionToMerge(bareCommit2).execute();
        assertEquals("bare2 != newArea2", bareCommit2, newAreaWorkspace.getGitClient().revParse("HEAD"));

        /* Commit a new change to the original repo */
        workspace.touch(testGitDir, "file3", "file3 content " + UUID.randomUUID().toString());
        testGitClient.add("file3");
        testGitClient.commit("commit3");
        ObjectId commit3 = testGitClient.revParse("HEAD");

        /* Push the new change to the bare repo */
        testGitClient.push("origin", "master");
        ObjectId bareCommit3 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "master");
        assertEquals("bare3 != working3", commit3, bareCommit3);

        /* Fetch new change into newArea repo using different argument forms */
        newAreaWorkspace.getGitClient().fetch(null, defaultRefSpec);
        newAreaWorkspace.getGitClient().fetch(null, defaultRefSpec, defaultRefSpec);

        /* Merge the fetch results into working branch */
        newAreaWorkspace.getGitClient().merge().setRevisionToMerge(bareCommit3).execute();
        assertEquals("bare3 != newArea3", bareCommit3, newAreaWorkspace.getGitClient().revParse("HEAD"));

        /* Commit a new change to the original repo */
        workspace.touch(testGitDir, "file4", "file4 content " + UUID.randomUUID().toString());
        testGitClient.add("file4");
        testGitClient.commit("commit4");
        ObjectId commit4 = testGitClient.revParse("HEAD");

        /* Push the new change to the bare repo */
        testGitClient.push("origin", "master");
        ObjectId bareCommit4 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "master");
        assertEquals("bare4 != working4", commit4, bareCommit4);

        /* Fetch new change into newArea repo using a different argument form */
        RefSpec[] refSpecArray = {defaultRefSpec, defaultRefSpec};
        newAreaWorkspace.getGitClient().fetch("origin", refSpecArray);

        /* Merge the fetch results into working branch */
        newAreaWorkspace.getGitClient().merge().setRevisionToMerge(bareCommit4).execute();
        assertEquals("bare4 != newArea4", bareCommit4, newAreaWorkspace.getGitClient().revParse("HEAD"));

        /* Commit a new change to the original repo */
        workspace.touch(testGitDir, "file5", "file5 content " + UUID.randomUUID().toString());
        testGitClient.add("file5");
        testGitClient.commit("commit5");
        ObjectId commit5 = testGitClient.revParse("HEAD");

        /* Push the new change to the bare repo */
        testGitClient.push("origin", "master");
        ObjectId bareCommit5 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "master");
        assertEquals("bare5 != working5", commit5, bareCommit5);

        /* Fetch into newArea repo with null RefSpec - should only
         * pull tags, not commits in git versions prior to git 1.9.0.
         * In git 1.9.0, fetch -t pulls tags and versions. */
        newAreaWorkspace.getGitClient().fetch("origin", null, null);
        assertEquals("null refSpec fetch modified local repo", bareCommit4, newAreaWorkspace.getGitClient().revParse("HEAD"));
        ObjectId expectedHead = bareCommit4;
        try {
            /* Assert that change did not arrive in repo if git
             * command line less than 1.9.  Assert that change arrives in
             * repo if git command line 1.9 or later. */
            newAreaWorkspace.getGitClient().merge().setRevisionToMerge(bareCommit5).execute();
            // JGit 4.9.0 and later copy the revision, JGit 4.8.0 and earlier did not
            // assertTrue("JGit should not have copied the revision", newArea.git instanceof CliGitAPIImpl);
            if (newAreaWorkspace.getGitClient() instanceof CliGitAPIImpl) {
                assertTrue("Wrong git version", workspace.cgit().isAtLeastVersion(1, 9, 0, 0));
            }
            expectedHead = bareCommit5;
        } catch (GitException ge) {
            assertTrue("Wrong cli git message :" + ge.getMessage(),
                    ge.getMessage().contains("Could not merge") ||
                            ge.getMessage().contains("not something we can merge") ||
                            ge.getMessage().contains("does not point to a commit"));
            assertExceptionMessageContains(ge, bareCommit5.name());
        }
        /* Assert that expected change is in repo after merge.  With
         * git 1.7 and 1.8, it should be bareCommit4.  With git 1.9
         * and later, it should be bareCommit5. */
        assertEquals("null refSpec fetch modified local repo", expectedHead, newAreaWorkspace.getGitClient().revParse("HEAD"));

        try {
            /* Fetch into newArea repo with invalid repo name and no RefSpec */
            newAreaWorkspace.getGitClient().fetch("invalid-remote-name");
            fail("Should have thrown an exception");
        } catch (GitException ge) {
            assertExceptionMessageContains(ge, "invalid-remote-name");
        }
    }

    @Test
    @Issue("JENKINS-19591")
    public void test_fetch_needs_preceding_prune() throws Exception {
        /* Create a working repo containing a commit */
        testGitClient.init();
        //w.init();
        workspace.touch(testGitDir, "file1", "file1 content " + UUID.randomUUID().toString());
        //w.touch("file1", "file1 content " + java.util.UUID.randomUUID().toString());
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        ObjectId commit1 = testGitClient.revParse("HEAD");
        assertThat(getBranchNames(testGitClient.getBranches()), contains("master"));
        assertThat(testGitClient.getRemoteBranches(), is(empty()));

        /* Prune when a remote is not yet defined */
        try {
            testGitClient.prune(new RemoteConfig(new Config(), "remote-is-not-defined"));
            fail("Should have thrown an exception");
        } catch (GitException ge) {
            String expected = testGitClient instanceof CliGitAPIImpl ? "returned status code 1" : "The uri was empty or null";
            final String msg = ge.getMessage();
            assertTrue("Wrong exception: " + msg, msg.contains(expected));
        }

        /* Clone working repo into a bare repo */
        //WorkingArea bare = new WorkingArea();
        bareWorkspace = new WorkspaceWithRepoRule(secondRepo, gitImplName, TaskListener.NULL);
        bareWorkspace.initBareRepo(bareWorkspace.getGitClient(), true);
        //bare.init(true);
        testGitClient.setRemoteUrl("origin", bareWorkspace.getGitFileDir().getAbsolutePath());
        testGitClient.push("origin", "master");
        ObjectId bareCommit1 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "master");
        assertEquals("bare != working", commit1, bareCommit1);
        assertThat(getBranchNames(testGitClient.getBranches()), contains("master"));
        assertThat(testGitClient.getRemoteBranches(), is(empty()));

        /* Create a branch in working repo named "parent" */
        testGitClient.branch("parent");
        testGitClient.checkout().ref("parent").execute();
        workspace.touch(testGitDir, "file2", "file2 content " + UUID.randomUUID().toString());
        testGitClient.add("file2");
        testGitClient.commit("commit2");
        ObjectId commit2 = testGitClient.revParse("HEAD");
        assertThat(getBranchNames(testGitClient.getBranches()), containsInAnyOrder("master", "parent"));
        assertThat(testGitClient.getRemoteBranches(), is(empty()));

        /* Push branch named "parent" to bare repo */
        testGitClient.push("origin", "parent");
        ObjectId bareCommit2 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "parent");
        assertEquals("working parent != bare parent", commit2, bareCommit2);
        assertThat(getBranchNames(testGitClient.getBranches()), containsInAnyOrder("master", "parent"));
        assertThat(testGitClient.getRemoteBranches(), is(empty()));

        /* Clone new working repo from bare repo */
        newAreaWorkspace = new WorkspaceWithRepoRule(thirdRepo, TaskListener.NULL);
        newAreaWorkspace.cloneRepo(newAreaWorkspace, bareWorkspace.getGitFileDir().getAbsolutePath());
        ObjectId newAreaHead = newAreaWorkspace.getGitClient().revParse("HEAD");
        assertEquals("bare != newArea", bareCommit1, newAreaHead);
        Set<Branch> remoteBranches = newAreaWorkspace.getGitClient().getRemoteBranches();
        assertThat(getBranchNames(remoteBranches), containsInAnyOrder("origin/master", "origin/parent", "origin/HEAD"));

        /* Checkout parent in new working repo */
        newAreaWorkspace.getGitClient().checkout().ref("origin/parent").branch("parent").execute();
        ObjectId newAreaParent = newAreaWorkspace.getGitClient().revParse("HEAD");
        assertEquals("parent1 != newAreaParent", commit2, newAreaParent);

        /* Delete parent branch from w */
        testGitClient.checkout().ref("master").execute();
        cliGitCommand.run("branch", "-D", "parent");
        assertThat(getBranchNames(testGitClient.getBranches()), contains("master"));
        assertEquals("Wrong branch count", 1, testGitClient.getBranches().size());

        /* Delete parent branch on bare repo*/
        bareWorkspace.launchCommand("git", "branch", "-D", "parent");
        // assertEquals("Wrong branch count", 1, bare.git.getBranches().size());

        /* Create parent/a branch in working repo */
        testGitClient.branch("parent/a");
        testGitClient.checkout().ref("parent/a").execute();
        workspace.touch(testGitDir, "file3", "file3 content " + UUID.randomUUID().toString());
        testGitClient.add("file3");
        testGitClient.commit("commit3");
        ObjectId commit3 = testGitClient.revParse("HEAD");

        /* Push parent/a branch to bare repo */
        testGitClient.push("origin", "parent/a");
        ObjectId bareCommit3 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "parent/a");
        assertEquals("parent/a != bare", commit3, bareCommit3);
        remoteBranches = bareWorkspace.getGitClient().getRemoteBranches();
        assertThat(remoteBranches, is(empty()));

        RefSpec defaultRefSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        List<RefSpec> refSpecs = new ArrayList<>();
        refSpecs.add(defaultRefSpec);
        try {
            /* Fetch parent/a into newArea repo - fails for
             * CliGitAPIImpl, succeeds for JGitAPIImpl */
            newAreaWorkspace.launchCommand("git", "config", "fetch.prune", "false");
            newAreaWorkspace.getGitClient().fetch(new URIish(newAreaWorkspace.getGitFileDir().toString()), refSpecs);
            assertTrue("CliGit should have thrown an exception", newAreaWorkspace.getGitClient() instanceof JGitAPIImpl);
        } catch (GitException ge) {
            final String msg = ge.getMessage();
            assertTrue("Wrong exception: " + msg, msg.contains("some local refs could not be updated") || msg.contains("error: cannot lock ref "));
        }

        /* Use git remote prune origin to remove obsolete branch named "parent" */
        newAreaWorkspace.getGitClient().prune(new RemoteConfig(new Config(), "origin"));

        /* Fetch should succeed */
        newAreaWorkspace.getGitClient().fetch_().from(new URIish(bareWorkspace.getGitFileDir().toString()), refSpecs).execute();
    }

    /**
     * JGit 3.3.0 thru 4.5.4 "prune during fetch" prunes more remote
     * branches than command line git prunes during fetch.  JGit 5.0.2
     * fixes the problem.
     * Refer to https://bugs.eclipse.org/bugs/show_bug.cgi?id=533549
     * Refer to https://bugs.eclipse.org/bugs/show_bug.cgi?id=533806
     */
    @Test
    @Issue("JENKINS-26197")
    public void test_fetch_with_prune() throws Exception {
        bareWorkspace = new WorkspaceWithRepoRule(secondRepo, TaskListener.NULL);
        bareWorkspace.initBareRepo(bareWorkspace.getGitClient(), true);
        /* Create a working repo containing three branches */
        /* master -> branch1 */
        /*        -> branch2 */
        testGitClient.init();
        testGitClient.setRemoteUrl("origin", bareWorkspace.getGitFileDir().getAbsolutePath());
        workspace.touch(testGitDir, "file-master", "file master content " + UUID.randomUUID().toString());
        testGitClient.add("file-master");
        testGitClient.commit("master-commit");
        assertEquals("Wrong branch count", 1, testGitClient.getBranches().size());
        testGitClient.push("origin", "master"); /* master branch is now on bare repo */

        testGitClient.checkout().ref("master").execute();
        testGitClient.branch("branch1");
        workspace.touch(testGitDir, "file-branch1", "file branch1 content " + UUID.randomUUID().toString());
        testGitClient.add("file-branch1");
        testGitClient.commit("branch1-commit");
        assertThat(getBranchNames(testGitClient.getBranches()), containsInAnyOrder("master", "branch1"));
        testGitClient.push("origin", "branch1"); /* branch1 is now on bare repo */

        testGitClient.checkout().ref("master").execute();
        testGitClient.branch("branch2");
        workspace.touch(testGitDir, "file-branch2", "file branch2 content " + UUID.randomUUID().toString());
        testGitClient.add("file-branch2");
        testGitClient.commit("branch2-commit");
        assertThat(getBranchNames(testGitClient.getBranches()), containsInAnyOrder("master", "branch1", "branch2"));
        assertThat(testGitClient.getRemoteBranches(), is(empty()));
        testGitClient.push("origin", "branch2"); /* branch2 is now on bare repo */

        /* Clone new working repo from bare repo */
        newAreaWorkspace = new WorkspaceWithRepoRule(thirdRepo, TaskListener.NULL);
        newAreaWorkspace.cloneRepo(newAreaWorkspace, bareWorkspace.getGitFileDir().getAbsolutePath());
        ObjectId newAreaHead = newAreaWorkspace.getGitClient().revParse("HEAD");
        Set<Branch> remoteBranches = newAreaWorkspace.getGitClient().getRemoteBranches();
        assertThat(getBranchNames(remoteBranches), containsInAnyOrder("origin/master", "origin/branch1", "origin/branch2", "origin/HEAD"));

        /* Remove branch1 from bare repo using original repo */
        cliGitCommand.run("push", bareWorkspace.getGitFileDir().getAbsolutePath(), ":branch1");

        List<RefSpec> refSpecs = Arrays.asList(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));

        /* Fetch without prune should leave branch1 in newArea */
        newAreaWorkspace.launchCommand("git", "config", "fetch.prune", "false");
        newAreaWorkspace.getGitClient().fetch_().from(new URIish(bareWorkspace.getGitFileDir().toString()), refSpecs).execute();
        remoteBranches = newAreaWorkspace.getGitClient().getRemoteBranches();
        assertThat(getBranchNames(remoteBranches), containsInAnyOrder("origin/master", "origin/branch1", "origin/branch2", "origin/HEAD"));

        /* Fetch with prune should remove branch1 from newArea */
        newAreaWorkspace.getGitClient().fetch_().from(new URIish(bareWorkspace.getGitFileDir().toString()), refSpecs).prune(true).execute();
        remoteBranches = newAreaWorkspace.getGitClient().getRemoteBranches();

        /* Git older than 1.7.9 (like 1.7.1 on Red Hat 6) does not prune branch1, don't fail the test
         * on that old git version.
         */
        if (newAreaWorkspace.getGitClient() instanceof CliGitAPIImpl && !workspace.cgit().isAtLeastVersion(1, 7, 9, 0)) {
            assertThat(getBranchNames(remoteBranches), containsInAnyOrder("origin/master", "origin/branch1", "origin/branch2", "origin/HEAD"));
        } else {
            assertThat(getBranchNames(remoteBranches), containsInAnyOrder("origin/master", "origin/branch2", "origin/HEAD"));
        }
    }

    @Test
    public void test_fetch_from_url() throws Exception {
        newAreaWorkspace = new WorkspaceWithRepoRule(thirdRepo, gitImplName, TaskListener.NULL);
        newAreaWorkspace.getGitClient().init();
        newAreaWorkspace.launchCommand("git", "commit", "--allow-empty", "-m", "init");
        String sha1 = newAreaWorkspace.launchCommand("git", "rev-list", "--no-walk", "--max-count=1", "HEAD");

        testGitClient.init();
        cliGitCommand.run("remote", "add", "origin", newAreaWorkspace.getGitFileDir().getAbsolutePath());
        testGitClient.fetch(new URIish(newAreaWorkspace.getGitFileDir().toString()), Collections.<RefSpec>emptyList());
        assertTrue(sha1.contains(newAreaWorkspace.launchCommand("git", "rev-list", "--no-walk", "--max-count=1", "HEAD")));
    }

    @Test
    public void test_fetch_shallow() throws Exception {
        testGitClient.init();
        testGitClient.setRemoteUrl("origin", workspace.localMirror());
        testGitClient.fetch_().from(new URIish("origin"), Collections.singletonList(new RefSpec("refs/heads/*:refs/remotes/origin/*"))).shallow(true).execute();
        check_remote_url(workspace, workspace.getGitClient(), "origin");
        assertBranchesExist(testGitClient.getRemoteBranches(), "origin/master");
        assertAlternatesFileExists(testGitDir);
        /* JGit does not support shallow fetch */
        boolean hasShallowFetchSupport = testGitClient instanceof CliGitAPIImpl && workspace.cgit().isAtLeastVersion(1, 5, 0, 0);
        assertEquals("isShallow?", hasShallowFetchSupport, workspace.cgit().isShallowRepository());
        String shallow = ".git" + File.separator + "shallow";
        assertEquals("shallow file existence: " + shallow, hasShallowFetchSupport, new File(testGitDir, shallow).exists());
    }

    @Test
    public void test_fetch_shallow_depth() throws Exception {
        testGitClient.init();
        testGitClient.setRemoteUrl("origin", workspace.localMirror());
        testGitClient.fetch_().from(new URIish("origin"), Collections.singletonList(new RefSpec("refs/heads/*:refs/remotes/origin/*"))).shallow(true).depth(2).execute();
        check_remote_url(workspace, workspace.getGitClient(), "origin");
        assertBranchesExist(testGitClient.getRemoteBranches(), "origin/master");
        assertAlternatesFileExists(testGitDir);
        /* JGit does not support shallow fetch */
        boolean hasShallowFetchSupport = testGitClient instanceof CliGitAPIImpl && workspace.cgit().isAtLeastVersion(1, 5, 0, 0);
        assertEquals("isShallow?", hasShallowFetchSupport, workspace.cgit().isShallowRepository());
        String shallow = ".git" + File.separator + "shallow";
        assertEquals("shallow file existence: " + shallow, hasShallowFetchSupport, new File(testGitDir, shallow).exists());
    }

    @Test
    public void test_fetch_noTags() throws Exception {
        testGitClient.init();
        testGitClient.setRemoteUrl("origin", workspace.localMirror());
        testGitClient.fetch_().from(new URIish("origin"), Collections.singletonList(new RefSpec("refs/heads/*:refs/remotes/origin/*"))).tags(false).execute();
        check_remote_url(workspace, workspace.getGitClient(), "origin");
        assertBranchesExist(testGitClient.getRemoteBranches(), "origin/master");
        Set<String> tags = testGitClient.getTagNames("");
        assertTrue("Tags have been found : " + tags, tags.isEmpty());
    }

    private void check_remote_url(WorkspaceWithRepoRule workspace, GitClient gitClient, final String repositoryName) throws InterruptedException, IOException {
        assertEquals("Wrong remote URL", workspace.localMirror(), gitClient.getRemoteUrl(repositoryName));
        String remotes = workspace.launchCommand("git", "remote", "-v");
        Assert.assertTrue("remote URL has not been updated", remotes.contains(workspace.localMirror()));
    }

    private void assertExceptionMessageContains(GitException ge, String expectedSubstring) {
        String actual = ge.getMessage().toLowerCase();
        assertTrue("Expected '" + expectedSubstring + "' exception message, but was: " + actual, actual.contains(expectedSubstring));
    }

    private Collection<String> getBranchNames(Collection<Branch> branches) {
        return branches.stream().map(Branch::getName).collect(toList());
    }

    private void assertBranchesExist(Set<Branch> branches, String... names) throws InterruptedException {
        Collection<String> branchNames = getBranchNames(branches);
        for (String name : names) {
            assertThat(branchNames, hasItem(name));
        }
    }

    private void assertAlternatesFileExists(File gitDir) {
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        assertThat(new File(gitDir, alternates), is(anExistingFile()));
    }

}
