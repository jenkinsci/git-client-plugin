package org.jenkinsci.plugins.gitclient;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.util.StreamTaskListener;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.io.FileMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;

@RunWith(Parameterized.class)
public class GitClientFetchTest {

    @Rule
    public GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule secondRepo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule thirdRepo = new GitClientSampleRepoRule();

    WorkspaceWithRepo workspace;
    WorkspaceWithRepo bareWorkspace;
    WorkspaceWithRepo newAreaWorkspace;

    private GitClient testGitClient;
    private File testGitDir;
    private CliGitCommand cliGitCommand;
    private final String gitImplName;

    private final Random random = new Random();
    private LogHandler handler = null;
    private TaskListener listener;

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

    /**
     * Default branch name in the upstream repository.
     */
    private static final String DEFAULT_MIRROR_BRANCH_NAME = "mast" + "er";

    /**
     * Tests that need the default branch name can use this variable.
     */
    private static String defaultBranchName = "mast" + "er"; // Intentionally separated string

    /**
     * Determine the global default branch name.
     * Command line git is moving towards more inclusive naming.
     * Git 2.32.0 honors the configuration variable `init.defaultBranch` and uses it for the name of the initial branch.
     * This method reads the global configuration and uses it to set the value of `defaultBranchName`.
     */
    @BeforeClass
    public static void computeDefaultBranchName() throws Exception {
        File configDir = Files.createTempDirectory("readGitConfig").toFile();
        CliGitCommand getDefaultBranchNameCmd = new CliGitCommand(Git.with(TaskListener.NULL, new hudson.EnvVars()).in(configDir).using("git").getClient());
        String[] output = getDefaultBranchNameCmd.runWithoutAssert("config", "--get", "init.defaultBranch");
        for (String s : output) {
            String result = s.trim();
            if (result != null && !result.isEmpty()) {
                defaultBranchName = result;
            }
        }
        assertThat("Failed to delete temporary readGitConfig directory", configDir.delete(), is(true));
    }

    @BeforeClass
    public static void loadLocalMirror() throws Exception {
        /* Prime the local mirror cache before other tests run */
        /* Allow 2-6 second delay before priming the cache */
        /* Allow other tests a better chance to prime the cache */
        /* 2-6 second delay is small compared to execution time of this test */
        Random random = new Random();
        Thread.sleep(2000L + random.nextInt(4000)); // Wait 2-6 seconds before priming the cache
        TaskListener mirrorListener = StreamTaskListener.fromStdout();
        File tempDir = Files.createTempDirectory("PrimeGitClientFetchTest").toFile();
        WorkspaceWithRepo cache = new WorkspaceWithRepo(tempDir, "git", mirrorListener);
        cache.localMirror();
        Util.deleteRecursive(tempDir);
    }

    @Before
    public void setUpRepositories() throws Exception {
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + random.nextInt());
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);

        workspace = new WorkspaceWithRepo(repo.getRoot(), gitImplName, listener);
        testGitClient = workspace.getGitClient();
        testGitDir = workspace.getGitFileDir();
        cliGitCommand = workspace.getCliGitCommand();
        testGitClient.init();
        cliGitCommand.run("config", "user.name", "Vojtěch GitClientFetchTest Zweibrücken-Šafařík");
        cliGitCommand.run("config", "user.email", "email.by.git.client.test@example.com");
    }

    /* Workspace -> original repo, bareWorkspace -> bare repo and newAreaWorkspace -> newArea repo */
    @Test
    public void test_fetch() throws Exception {
        /* Create a working repo containing a commit */
        workspace.touch(testGitDir, "file1", "file1 content " + UUID.randomUUID());
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        ObjectId commit1 = testGitClient.revParse("HEAD");

        /* Clone working repo into a bare repo */
        bareWorkspace = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        bareWorkspace.initBareRepo(bareWorkspace.getGitClient(), true);
        testGitClient.setRemoteUrl("origin", bareWorkspace.getGitFileDir().getAbsolutePath());
        Set<Branch> remoteBranchesEmpty = testGitClient.getRemoteBranches();
        assertThat(remoteBranchesEmpty, is(empty()));
        testGitClient.push("origin", defaultBranchName);
        ObjectId bareCommit1 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), defaultBranchName);
        assertThat("bare != working", bareCommit1, is(commit1));
        assertThat(bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "refs/heads/" + defaultBranchName), is(commit1));

        /* Clone new working repo from bare repo */
        newAreaWorkspace = new WorkspaceWithRepo(thirdRepo.getRoot(), gitImplName, TaskListener.NULL);
        newAreaWorkspace.cloneRepo(newAreaWorkspace, bareWorkspace.getGitFileDir().getAbsolutePath());
        ObjectId newAreaHead = newAreaWorkspace.getGitClient().revParse("HEAD");
        assertThat("bare != newArea", newAreaHead, is(bareCommit1));
        Set<Branch> remoteBranches1 = newAreaWorkspace.getGitClient().getRemoteBranches();
        assertThat(getBranchNames(remoteBranches1), hasItems("origin/" + defaultBranchName));
        assertThat(newAreaWorkspace.getGitClient().getHeadRev(newAreaWorkspace.getGitFileDir().getAbsolutePath(), "refs/heads/" + defaultBranchName), is(bareCommit1));

        /* Commit a new change to the original repo */
        workspace.touch(testGitDir, "file2", "file2 content " + UUID.randomUUID());
        testGitClient.add("file2");
        testGitClient.commit("commit2");
        ObjectId commit2 = testGitClient.revParse("HEAD");
        assertThat(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "refs/heads/" + defaultBranchName), is(commit2));

        /* Push the new change to the bare repo */
        testGitClient.push("origin", defaultBranchName);
        ObjectId bareCommit2 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), defaultBranchName);
        assertThat("bare2 != working2", bareCommit2, is(commit2));
        assertThat(bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "refs/heads/" + defaultBranchName), is(commit2));

        /* Fetch new change into newArea repo */
        RefSpec defaultRefSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        List<RefSpec> refSpecs = new ArrayList<>();
        refSpecs.add(defaultRefSpec);
        newAreaWorkspace.launchCommand("git", "config", "fetch.prune", "false");
        newAreaWorkspace.getGitClient().fetch(new URIish(bareWorkspace.getGitFileDir().toString()), refSpecs);

        /* Confirm the fetch did not alter working branch */
        assertThat("beforeMerge != commit1", newAreaWorkspace.getGitClient().revParse("HEAD"), is(commit1));

        /* Merge the fetch results into working branch */
        newAreaWorkspace.getGitClient().merge().setRevisionToMerge(bareCommit2).execute();
        assertThat("bare2 != newArea2", newAreaWorkspace.getGitClient().revParse("HEAD"), is(bareCommit2));

        /* Commit a new change to the original repo */
        workspace.touch(testGitDir, "file3", "file3 content " + UUID.randomUUID());
        testGitClient.add("file3");
        testGitClient.commit("commit3");
        ObjectId commit3 = testGitClient.revParse("HEAD");

        /* Push the new change to the bare repo */
        testGitClient.push("origin", defaultBranchName);
        ObjectId bareCommit3 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), defaultBranchName);
        assertThat("bare3 != working3", bareCommit3, is(commit3));

        /* Fetch new change into newArea repo using different argument forms */
        newAreaWorkspace.getGitClient().fetch(null, defaultRefSpec);
        newAreaWorkspace.getGitClient().fetch(null, defaultRefSpec, defaultRefSpec);

        /* Merge the fetch results into working branch */
        newAreaWorkspace.getGitClient().merge().setRevisionToMerge(bareCommit3).execute();
        assertThat("bare3 != newArea3", newAreaWorkspace.getGitClient().revParse("HEAD"), is(bareCommit3));

        /* Commit a new change to the original repo */
        workspace.touch(testGitDir, "file4", "file4 content " + UUID.randomUUID());
        testGitClient.add("file4");
        testGitClient.commit("commit4");
        ObjectId commit4 = testGitClient.revParse("HEAD");

        /* Push the new change to the bare repo */
        testGitClient.push("origin", defaultBranchName);
        ObjectId bareCommit4 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), defaultBranchName);
        assertThat("bare4 != working4", bareCommit4, is(commit4));

        /* Fetch new change into newArea repo using a different argument form */
        RefSpec[] refSpecArray = {defaultRefSpec, defaultRefSpec};
        newAreaWorkspace.getGitClient().fetch("origin", refSpecArray);

        /* Merge the fetch results into working branch */
        newAreaWorkspace.getGitClient().merge().setRevisionToMerge(bareCommit4).execute();
        assertThat("bare4 != newArea4", newAreaWorkspace.getGitClient().revParse("HEAD"), is(bareCommit4));

        /* Commit a new change to the original repo */
        workspace.touch(testGitDir, "file5", "file5 content " + UUID.randomUUID());
        testGitClient.add("file5");
        testGitClient.commit("commit5");
        ObjectId commit5 = testGitClient.revParse("HEAD");

        /* Push the new change to the bare repo */
        testGitClient.push("origin", defaultBranchName);
        ObjectId bareCommit5 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), defaultBranchName);
        assertThat("bare5 != working5", bareCommit5, is(commit5));

        /* Fetch into newArea repo with null RefSpec - should only
         * pull tags, not commits in git versions prior to git 1.9.0.
         * In git 1.9.0, fetch -t pulls tags and versions. */
        newAreaWorkspace.getGitClient().fetch("origin", null, null);
        assertThat("null refSpec fetch modified local repo", newAreaWorkspace.getGitClient().revParse("HEAD"), is(bareCommit4));
        ObjectId expectedHead = bareCommit4;
        if (gitImplName.startsWith("jgit") || workspace.cgit().isAtLeastVersion(1, 9, 0, 0)) {
            newAreaWorkspace.getGitClient().merge().setRevisionToMerge(bareCommit5).execute();
            expectedHead = bareCommit5;
        } else {
            GitException gitException = assertThrows(GitException.class,
                    () -> newAreaWorkspace.getGitClient().merge().setRevisionToMerge(bareCommit5).execute());
            assertThat(gitException.getMessage(), anyOf(
                    containsString("Could not merge"),
                    containsString("not something we can merge"),
                    containsString("does not point to a commit")
            ));
        }
        /* Assert that expected change is in repo after merge.  With
         * git 1.7 and 1.8, it should be bareCommit4.  With git 1.9
         * and later, it should be bareCommit5. */
        assertThat("null refSpec fetch modified local repo", newAreaWorkspace.getGitClient().revParse("HEAD"), is(expectedHead));
    }

    @Test
    @Issue("JENKINS-19591")
    public void test_fetch_needs_preceding_prune() throws Exception {
        /* Create a working repo containing a commit */
        workspace.touch(testGitDir, "file1", "file1 content " + UUID.randomUUID());
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        ObjectId commit1 = testGitClient.revParse("HEAD");
        assertThat(getBranchNames(testGitClient.getBranches()), contains(defaultBranchName));
        assertThat(testGitClient.getRemoteBranches(), is(empty()));

        /* Clone working repo into a bare repo */
        bareWorkspace = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        bareWorkspace.initBareRepo(bareWorkspace.getGitClient(), true);
        testGitClient.setRemoteUrl("origin", bareWorkspace.getGitFileDir().getAbsolutePath());
        testGitClient.push("origin", defaultBranchName);
        ObjectId bareCommit1 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), defaultBranchName);
        assertThat("bare != working", bareCommit1, is(commit1));
        assertThat(getBranchNames(testGitClient.getBranches()), contains(defaultBranchName));
        assertThat(testGitClient.getRemoteBranches(), is(empty()));

        /* Create a branch in working repo named "parent" */
        testGitClient.branch("parent");
        testGitClient.checkout().ref("parent").execute();
        workspace.touch(testGitDir, "file2", "file2 content " + UUID.randomUUID());
        testGitClient.add("file2");
        testGitClient.commit("commit2");
        ObjectId commit2 = testGitClient.revParse("HEAD");
        assertThat(getBranchNames(testGitClient.getBranches()), containsInAnyOrder(defaultBranchName, "parent"));
        assertThat(testGitClient.getRemoteBranches(), is(empty()));

        /* Push branch named "parent" to bare repo */
        testGitClient.push("origin", "parent");
        ObjectId bareCommit2 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "parent");
        assertThat("working parent != bare parent", bareCommit2, is(commit2));
        assertThat(getBranchNames(testGitClient.getBranches()), containsInAnyOrder(defaultBranchName, "parent"));
        assertThat(testGitClient.getRemoteBranches(), is(empty()));

        /* Clone new working repo from bare repo */
        newAreaWorkspace = new WorkspaceWithRepo(thirdRepo.getRoot(), gitImplName, TaskListener.NULL);
        newAreaWorkspace.cloneRepo(newAreaWorkspace, bareWorkspace.getGitFileDir().getAbsolutePath());
        ObjectId newAreaHead = newAreaWorkspace.getGitClient().revParse("HEAD");
        assertThat("bare != newArea", newAreaHead, is(bareCommit1));
        Set<Branch> remoteBranches = newAreaWorkspace.getGitClient().getRemoteBranches();
        assertThat(getBranchNames(remoteBranches), containsInAnyOrder("origin/" + defaultBranchName, "origin/parent", "origin/HEAD"));

        /* Checkout parent in new working repo */
        newAreaWorkspace.getGitClient().checkout().ref("origin/parent").branch("parent").execute();
        ObjectId newAreaParent = newAreaWorkspace.getGitClient().revParse("HEAD");
        assertThat("parent1 != newAreaParent", newAreaParent, is(commit2));

        /* Delete parent branch from testGitClient */
        testGitClient.checkout().ref(defaultBranchName).execute();
        cliGitCommand.run("branch", "-D", "parent");
        assertThat(getBranchNames(testGitClient.getBranches()), contains(defaultBranchName));
        assertThat("Wrong branch count", testGitClient.getBranches().size(), is(1));

        /* Delete parent branch on bare repo*/
        bareWorkspace.launchCommand("git", "branch", "-D", "parent");
        // assertEquals("Wrong branch count", 1, bare.git.getBranches().size());

        /* Create parent/a branch in working repo */
        testGitClient.branch("parent/a");
        testGitClient.checkout().ref("parent/a").execute();
        workspace.touch(testGitDir, "file3", "file3 content " + UUID.randomUUID());
        testGitClient.add("file3");
        testGitClient.commit("commit3");
        ObjectId commit3 = testGitClient.revParse("HEAD");

        /* Push parent/a branch to bare repo */
        testGitClient.push("origin", "parent/a");
        ObjectId bareCommit3 = bareWorkspace.getGitClient().getHeadRev(bareWorkspace.getGitFileDir().getAbsolutePath(), "parent/a");
        assertThat("parent/a != bare", bareCommit3, is(commit3));
        remoteBranches = bareWorkspace.getGitClient().getRemoteBranches();
        assertThat(remoteBranches, is(empty()));

        RefSpec defaultRefSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        List<RefSpec> refSpecs = new ArrayList<>();
        refSpecs.add(defaultRefSpec);
        try {
            /* Fetch parent/a into newArea repo - fails for
             * CliGitAPIImpl, succeeds for JGitAPIImpl */
            newAreaWorkspace.launchCommand("git", "config", "fetch.prune", "false");
            newAreaWorkspace.getGitClient().fetch(new URIish(bareWorkspace.getGitFileDir().toString()), refSpecs);
            assertThat("CliGit did not throw an expected exception", newAreaWorkspace.getGitClient(), instanceOf(JGitAPIImpl.class));
        } catch (GitException ge) {
            final String msg = ge.getMessage();
            assertThat("Wrong exception: " + msg, msg,
                    anyOf(
                            containsString("some local refs could not be updated"),
                            containsString("error: cannot lock ref ")
                    )
            );
        }

        /* Use git remote prune origin to remove obsolete branch named "parent" */
        newAreaWorkspace.getGitClient().prune(new RemoteConfig(new Config(), "origin"));

        /* Fetch should succeed */
        newAreaWorkspace.getGitClient().fetch_().from(new URIish(bareWorkspace.getGitFileDir().toString()), refSpecs).timeout(7).execute();
    }

    @Test
    public void test_prune_without_remote() {
        /* Prune when a remote is not yet defined */
        String expectedMessage = testGitClient instanceof CliGitAPIImpl ? "returned status code 1" : "The uri was empty or null";
        GitException gitException = assertThrows(GitException.class,
                () -> testGitClient.prune(new RemoteConfig(new Config(), "remote-is-not-defined")));
        assertThat(gitException.getMessage(), containsString(expectedMessage));
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
        bareWorkspace = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        bareWorkspace.initBareRepo(bareWorkspace.getGitClient(), true);
        /* Create a working repo containing three branches */
        /* main -> branch1 */
        /*      -> branch2 */
        testGitClient.setRemoteUrl("origin", bareWorkspace.getGitFileDir().getAbsolutePath());
        workspace.touch(testGitDir, "file-main", "file main content " + UUID.randomUUID());
        testGitClient.add("file-main");
        testGitClient.commit("main-commit");
        assertThat("Wrong branch count", testGitClient.getBranches().size(), is(1));
        testGitClient.push("origin", defaultBranchName); /* main branch is now on bare repo */

        testGitClient.checkout().ref(defaultBranchName).execute();
        testGitClient.branch("branch1");
        workspace.touch(testGitDir, "file-branch1", "file branch1 content " + UUID.randomUUID());
        testGitClient.add("file-branch1");
        testGitClient.commit("branch1-commit");
        assertThat(getBranchNames(testGitClient.getBranches()), containsInAnyOrder(defaultBranchName, "branch1"));
        testGitClient.push("origin", "branch1"); /* branch1 is now on bare repo */

        testGitClient.checkout().ref(defaultBranchName).execute();
        testGitClient.branch("branch2");
        workspace.touch(testGitDir, "file-branch2", "file branch2 content " + UUID.randomUUID());
        testGitClient.add("file-branch2");
        testGitClient.commit("branch2-commit");
        assertThat(getBranchNames(testGitClient.getBranches()), containsInAnyOrder(defaultBranchName, "branch1", "branch2"));
        assertThat(testGitClient.getRemoteBranches(), is(empty()));
        testGitClient.push("origin", "branch2"); /* branch2 is now on bare repo */

        /* Clone new working repo from bare repo */
        newAreaWorkspace = new WorkspaceWithRepo(thirdRepo.getRoot(), gitImplName, TaskListener.NULL);
        newAreaWorkspace.cloneRepo(newAreaWorkspace, bareWorkspace.getGitFileDir().getAbsolutePath());
        ObjectId newAreaHead = newAreaWorkspace.getGitClient().revParse("HEAD");
        Set<Branch> remoteBranches = newAreaWorkspace.getGitClient().getRemoteBranches();
        assertThat(getBranchNames(remoteBranches), containsInAnyOrder("origin/" + defaultBranchName, "origin/branch1", "origin/branch2", "origin/HEAD"));

        /* Remove branch1 from bare repo using original repo */
        cliGitCommand.run("push", bareWorkspace.getGitFileDir().getAbsolutePath(), ":branch1");

        List<RefSpec> refSpecs = Collections.singletonList(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));

        /* Fetch without prune should leave branch1 in newArea */
        newAreaWorkspace.launchCommand("git", "config", "fetch.prune", "false");
        newAreaWorkspace.getGitClient().fetch_().from(new URIish(bareWorkspace.getGitFileDir().toString()), refSpecs).timeout(13).execute();
        remoteBranches = newAreaWorkspace.getGitClient().getRemoteBranches();
        assertThat(getBranchNames(remoteBranches), containsInAnyOrder("origin/" + defaultBranchName, "origin/branch1", "origin/branch2", "origin/HEAD"));

        /* Fetch with prune should remove branch1 from newArea */
        newAreaWorkspace.getGitClient().fetch_().from(new URIish(bareWorkspace.getGitFileDir().toString()), refSpecs).prune(true).execute();
        remoteBranches = newAreaWorkspace.getGitClient().getRemoteBranches();

        assertThat(getBranchNames(remoteBranches), containsInAnyOrder("origin/" + defaultBranchName, "origin/branch2", "origin/HEAD"));
    }

    @Test
    public void test_fetch_from_url() throws Exception {
        newAreaWorkspace = new WorkspaceWithRepo(thirdRepo.getRoot(), gitImplName, TaskListener.NULL);
        newAreaWorkspace.getGitClient().init();
        newAreaWorkspace.launchCommand("git", "config", "user.name", "Vojtěch fetch from URL Zweibrücken-Šafařík");
        newAreaWorkspace.launchCommand("git", "config", "user.email", "email.by.git.fetch.test@example.com");
        newAreaWorkspace.launchCommand("git", "commit", "--allow-empty", "-m", "init");
        String sha1 = newAreaWorkspace.launchCommand("git", "rev-list", "--no-walk", "--max-count=1", "HEAD");

        cliGitCommand.run("remote", "add", "origin", newAreaWorkspace.getGitFileDir().getAbsolutePath());
        testGitClient.fetch(new URIish(newAreaWorkspace.getGitFileDir().toString()), Collections.emptyList());
        assertThat(sha1.contains(newAreaWorkspace.launchCommand("git", "rev-list", "--no-walk", "--max-count=1", "HEAD")), is(true));
    }

    @Test
    public void test_fetch_shallow() throws Exception {
        testGitClient.setRemoteUrl("origin", workspace.localMirror());
        testGitClient.fetch_().from(new URIish("origin"), Collections.singletonList(new RefSpec("refs/heads/*:refs/remotes/origin/*"))).shallow(true).execute();
        check_remote_url(workspace, workspace.getGitClient(), "origin");
        assertBranchesExist(testGitClient.getRemoteBranches(), "origin/" + DEFAULT_MIRROR_BRANCH_NAME);
        assertAlternatesFileExists(testGitDir);
        assertThat("isShallow?", workspace.cgit().isShallowRepository(), is(true));
        String shallow = ".git" + File.separator + "shallow";
        assertThat("shallow file existence: " + shallow, new File(testGitDir, shallow).exists(), is(true));
    }

    private void fetch_shallow_depth(Integer fetchDepth) throws Exception {
        testGitClient.setRemoteUrl("origin", workspace.localMirror());
        testGitClient.fetch_().from(new URIish("origin"), Collections.singletonList(new RefSpec("refs/heads/*:refs/remotes/origin/*"))).shallow(true).depth(fetchDepth).execute();
        check_remote_url(workspace, workspace.getGitClient(), "origin");
        assertBranchesExist(testGitClient.getRemoteBranches(), "origin/" + DEFAULT_MIRROR_BRANCH_NAME);
        assertAlternatesFileExists(testGitDir);
        assertThat("isShallow?", workspace.cgit().isShallowRepository(), is(true));
        String shallow = ".git" + File.separator + "shallow";
        assertThat("shallow file existence: " + shallow, new File(testGitDir, shallow).exists(), is(true));
    }

    @Test
    public void test_fetch_shallow_depth() throws Exception {
        fetch_shallow_depth(2);
    }

    @Test
    public void test_fetch_shallow_null_depth() throws Exception {
        fetch_shallow_depth(null);
    }

    @Test
    public void test_fetch_noTags() throws Exception {
        testGitClient.setRemoteUrl("origin", workspace.localMirror());
        testGitClient.fetch_().from(new URIish("origin"), Collections.singletonList(new RefSpec("refs/heads/*:refs/remotes/origin/*"))).tags(false).execute();
        check_remote_url(workspace, workspace.getGitClient(), "origin");
        assertBranchesExist(testGitClient.getRemoteBranches(), "origin/" + DEFAULT_MIRROR_BRANCH_NAME);
        Set<String> tags = testGitClient.getTagNames("");
        assertThat("Tags have been found : " + tags, tags.isEmpty(), is(true));
    }

    /* JENKINS-33258 detected many calls to git rev-parse. This checks
     * those calls are not being made. The checkoutRandomBranch call
     * creates a branch with a random name. The later assertion checks that
     * the random branch name is not mentioned in a call to git rev-parse.
     */
    private String checkoutRandomBranch() throws GitException, InterruptedException {
        String branchName = "rev-parse-branch-" + UUID.randomUUID();
        testGitClient.checkout().ref("origin/master").branch(branchName).execute();
        Set<String> branchNames = testGitClient.getBranches().stream().map(Branch::getName).collect(Collectors.toSet());
        assertThat(branchNames, hasItem(branchName));
        return branchName;
    }

    @Test
    public void test_fetch_default_timeout_logging() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        String randomBranchName = checkoutRandomBranch();
        testGitClient.fetch_().from(new URIish("origin"), null).prune().execute();
        assertTimeout(testGitClient, "fetch", CliGitAPIImpl.TIMEOUT);
        assertRevParseNotCalled(testGitClient, randomBranchName);
    }

    private void check_remote_url(WorkspaceWithRepo workspace, GitClient gitClient, final String repositoryName) throws InterruptedException, IOException {
        assertThat("Wrong remote URL", gitClient.getRemoteUrl(repositoryName), is(workspace.localMirror()));
        String remotes = workspace.launchCommand("git", "remote", "-v");
        assertThat("remote URL has not been updated", remotes.contains(workspace.localMirror()), is(true));
    }

    private void assertExceptionMessageContains(GitException ge, String expectedSubstring) {
        String actual = ge.getMessage().toLowerCase();
        assertThat("Expected '" + expectedSubstring + "' exception message, but was: " + actual, actual.contains(expectedSubstring), is(true));
    }

    private Collection<String> getBranchNames(Collection<Branch> branches) {
        return branches.stream().map(Branch::getName).collect(toList());
    }

    private void assertBranchesExist(Set<Branch> branches, String... names) {
        Collection<String> branchNames = getBranchNames(branches);
        for (String name : names) {
            assertThat(branchNames, hasItem(name));
        }
    }

    private void assertAlternatesFileExists(File gitDir) {
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        assertThat(new File(gitDir, alternates), is(not(anExistingFile())));
    }

    private void assertTimeout(GitClient gitClient, final String substring, int expectedTimeout) {
        assertLoggedMessage(gitClient, substring, " [#] timeout=" + expectedTimeout, true);
    }

    private void assertLoggedMessage(GitClient gitClient, final String candidateSubstring, final String expectedValue, final boolean expectToFindMatch) {
        List<String> messages = handler.getMessages();
        List<String> candidateMessages = new ArrayList<>();
        List<String> matchedMessages = new ArrayList<>();
        final String messageRegEx = ".*\\b" + candidateSubstring + "\\b.*"; // the expected substring
        final String timeoutRegEx = messageRegEx + expectedValue + "\\b.*"; // # timeout=<value>
        for (String message : messages) {
            if (message.matches(messageRegEx)) {
                candidateMessages.add(message);
            }
            if (message.matches(timeoutRegEx)) {
                matchedMessages.add(message);
            }
        }
        assertThat("No messages logged", messages, is(not(empty())));
        if (expectToFindMatch) {
            assertThat("No messages matched substring '" + candidateSubstring + "'", candidateMessages, is(not(empty())));
            assertThat("Messages matched substring '" + candidateSubstring + "', found: " + candidateMessages + "\nExpected " + expectedValue, matchedMessages, is(not(empty())));
            assertThat("All candidate messages matched", matchedMessages, is(candidateMessages));
        } else {
            assertThat("Messages matched substring '" + candidateSubstring + "' unexpectedly", candidateMessages, is(empty()));
        }
    }

    /* JENKINS-33258 detected many calls to git rev-parse. This checks
     * those calls are not being made. The checkoutRandomBranch call
     * creates a branch whose name is unknown to the tests. This
     * checks that the branch name is not mentioned in a call to
     * git rev-parse.
     */
    private void assertRevParseNotCalled(GitClient gitClient, String unexpectedBranchName) {
        assertLoggedMessage(gitClient, "git rev-parse ", unexpectedBranchName, false);
    }
}
