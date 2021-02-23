package org.jenkinsci.plugins.gitclient;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Git API Tests, eventual replacement for GitAPITestCase,
 * Implemented in JUnit 4.
 */

@RunWith(Parameterized.class)
public class GitAPITest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private final TemporaryDirectoryAllocator temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();

    private int logCount = 0;
    private final Random random = new Random();
    private static final String LOGGING_STARTED = "Logging started";
    private LogHandler handler = null;
    private TaskListener listener;
    private final String gitImplName;

    private String revParseBranchName = null;

    private int checkoutTimeout = -1;
    private int cloneTimeout = -1;
    private int fetchTimeout = -1;
    private int submoduleUpdateTimeout = -1;


    WorkspaceWithRepo workspace;

    private GitClient testGitClient;
    private File testGitDir;
    private CliGitCommand cliGitCommand;

    public GitAPITest(final String gitImplName) {
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

    private Collection<String> getBranchNames(Collection<Branch> branches) {
        return branches.stream().map(Branch::getName).collect(toList());
    }

    private void assertBranchesExist(Set<Branch> branches, String... names) throws InterruptedException {
        Collection<String> branchNames = getBranchNames(branches);
        for (String name : names) {
            assertThat(branchNames, hasItem(name));
        }
    }

    @Before
    public void setUpRepositories() throws Exception {
        final File repoRoot = tempFolder.newFolder();

        revParseBranchName = null;
        checkoutTimeout = -1;
        cloneTimeout = -1;
        fetchTimeout = -1;
        submoduleUpdateTimeout = -1;

        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        listener.getLogger().println(LOGGING_STARTED);

//        workspace = new WorkspaceWithRepo(repoRoot, gitImplName, listener);
        workspace = new WorkspaceWithRepo(temporaryDirectoryAllocator.allocate(), gitImplName, listener);

        testGitClient = workspace.getGitClient();
        testGitDir = workspace.getGitFileDir();
        cliGitCommand = workspace.getCliGitCommand();
        testGitClient.init();
        final String userName = "root";
        final String emailAddress = "root@mydomain.com";
        cliGitCommand.run("config", "user.name", userName);
        cliGitCommand.run("config", "user.email", emailAddress);
        testGitClient.setAuthor(userName, emailAddress);
        testGitClient.setCommitter(userName, emailAddress);
    }

    @Test
    public void testGetRemoteUrl() throws Exception {
        workspace.launchCommand("git", "remote", "add", "origin", "https://github.com/jenkinsci/git-client-plugin.git");
        workspace.launchCommand("git", "remote", "add", "ndeloof", "git@github.com:ndeloof/git-client-plugin.git");
        String remoteUrl = workspace.getGitClient().getRemoteUrl("origin");
        assertEquals("unexepected remote URL " + remoteUrl, "https://github.com/jenkinsci/git-client-plugin.git",
                remoteUrl);
    }

    @Test
    public void testEmptyComment() throws Exception {
        workspace.commitEmpty("init-empty-comment-to-tag-fails-on-windows");
        if (isWindows()) {
            testGitClient.tag("non-empty-comment", "empty-tag-comment-fails-on-windows");
        } else {
            testGitClient.tag("empty-comment", "");
        }
    }

    @Test
    public void testCreateBranch() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("test");
        String branches = workspace.launchCommand("git", "branch", "-l");
        assertTrue("master branch not listed", branches.contains("master"));
        assertTrue("test branch not listed", branches.contains("test"));
    }

    @Test
    public void testDeleteBranch() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("test");
        testGitClient.deleteBranch("test");
        String branches = workspace.launchCommand("git", "branch", "-l");
        assertFalse("deleted test branch still present", branches.contains("test"));
        try {
            testGitClient.deleteBranch("test");
            assertTrue("cgit did not throw an exception", workspace.getGitClient() instanceof JGitAPIImpl);
        } catch (GitException ge) {
            assertEquals("Could not delete branch test", ge.getMessage());
        }
    }

    @Test
    public void testDeleteTag() throws Exception {
        workspace.commitEmpty("init");
        workspace.tag("test");
        workspace.tag("another");
        testGitClient.deleteTag("test");
        String tags = workspace.launchCommand("git", "tag");
        assertFalse("deleted test tag still present", tags.contains("test"));
        assertTrue("expected tag not listed", tags.contains("another"));
        try {
            testGitClient.deleteTag("test");
            assertTrue("cgit did not throw an exception", workspace.getGitClient() instanceof JGitAPIImpl);
        } catch (GitException ge) {
            assertEquals("Could not delete tag test", ge.getMessage());
        }
    }

    @Test
    public void testListTagsWithFilter() throws Exception {
        workspace.commitEmpty("init");
        workspace.tag("test");
        workspace.tag("another_test");
        workspace.tag("yet_another");
        Set<String> tags = testGitClient.getTagNames("*test");
        assertTrue("expected tag test not listed", tags.contains("test"));
        assertTrue("expected tag another_tag not listed", tags.contains("another_test"));
        assertFalse("unexpected tag yet_another listed", tags.contains("yet_another"));
    }

    @Test
    public void testListTagsWithoutFilter() throws Exception {
        workspace.commitEmpty("init");
        workspace.tag("test");
        workspace.tag("another_test");
        workspace.tag("yet_another");
        Set<String> allTags = testGitClient.getTagNames(null);
        assertTrue("tag 'test' not listed", allTags.contains("test"));
        assertTrue("tag 'another_test' not listed", allTags.contains("another_test"));
        assertTrue("tag 'yet_another' not listed", allTags.contains("yet_another"));
    }

    @Issue("JENKINS-37794")
    @Test
    public void testGetTagNamesSupportsSlashesInTagNames() throws Exception {
        workspace.commitEmpty("init-getTagNames-supports-slashes");
        testGitClient.tag("no-slash", "Tag without a /");
        Set<String> tags = testGitClient.getTagNames(null);
        assertThat(tags, hasItem("no-slash"));
        assertThat(tags, not(hasItem("slashed/sample")));
        assertThat(tags, not(hasItem("slashed/sample-with-short-comment")));

        testGitClient.tag("slashed/sample", "Tag slashed/sample includes a /");
        testGitClient.tag("slashed/sample-with-short-comment", "short comment");

        for (String matchPattern : Arrays.asList("n*", "no-*", "*-slash", "*/sl*sa*", "*/sl*/sa*")) {
            Set<String> latestTags = testGitClient.getTagNames(matchPattern);
            assertThat(tags, hasItem("no-slash"));
            assertThat(latestTags, not(hasItem("slashed/sample")));
            assertThat(latestTags, not(hasItem("slashed/sample-with-short-comment")));
        }

        for (String matchPattern : Arrays.asList("s*", "slashed*", "sl*sa*", "slashed/*", "sl*/sa*", "slashed/sa*")) {
            Set<String> latestTags = testGitClient.getTagNames(matchPattern);
            assertThat(latestTags, hasItem("slashed/sample"));
            assertThat(latestTags, hasItem("slashed/sample-with-short-comment"));
        }
    }

    @Test
    public void testListBranchesContainingRef() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("test");
        testGitClient.branch("another");
        Set<Branch> branches = testGitClient.getBranches();
        assertBranchesExist(branches, "master", "test", "another");
        assertEquals(3, branches.size());
    }

    @Test
    public void testListTagsStarFilter() throws Exception {
        workspace.commitEmpty("init");
        workspace.tag("test");
        workspace.tag("another_test");
        workspace.tag("yet_another");
        Set<String> allTags = testGitClient.getTagNames("*");
        assertTrue("tag 'test' not listed", allTags.contains("test"));
        assertTrue("tag 'another_test' not listed", allTags.contains("another_test"));
        assertTrue("tag 'yet_another' not listed", allTags.contains("yet_another"));
    }

    @Test
    public void testTagExists() throws Exception {
        workspace.commitEmpty("init");
        workspace.tag("test");
        assertTrue(testGitClient.tagExists("test"));
        assertFalse(testGitClient.tagExists("unknown"));
    }

    @Test
    public void testGetTagMessage() throws Exception {
        workspace.commitEmpty("init");
        workspace.launchCommand("git", "tag", "test", "-m", "this-is-a-test");
        assertEquals("this-is-a-test", testGitClient.getTagMessage("test"));
    }

    @Test
    public void testGetTagMessageMultiLine() throws Exception {
        workspace.commitEmpty("init");
        workspace.launchCommand("git", "tag", "test", "-m", "test 123!\n* multi-line tag message\n padded ");

        // Leading four spaces from each line should be stripped,
        // but not the explicit single space before "padded",
        // and the final errant space at the end should be trimmed
        assertEquals("test 123!\n* multi-line tag message\n padded", testGitClient.getTagMessage("test"));
    }

    @Test
    public void testCreateRef() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.ref("refs/testing/testref");
        assertTrue("test ref not created", workspace.launchCommand("git", "show-ref").contains("refs/testing/testref"));
    }

    @Test
    public void testDeleteRef() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.ref("refs/testing/testref");
        testGitClient.ref("refs/testing/anotherref");
        testGitClient.deleteRef("refs/testing/testref");
        String refs = workspace.launchCommand("git", "show-ref");
        assertFalse("deleted test tag still present", refs.contains("refs/testing/testref"));
        assertTrue("expected tag not listed", refs.contains("refs/testing/anotherref"));
        testGitClient.deleteRef("refs/testing/testref"); //Double-deletes do nothing.
    }

    @Test
    public void testListRefsWithPrefix() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.ref("refs/testing/testref");
        testGitClient.ref("refs/testing/nested/anotherref");
        testGitClient.ref("refs/testing/nested/yetanotherref");
        Set<String> refs = testGitClient.getRefNames("refs/testing/nested/");
        assertFalse("ref testref listed", refs.contains("refs/testing/testref"));
        assertTrue("ref anotherref not listed", refs.contains("refs/testing/nested/anotherref"));
        assertTrue("ref yetanotherref not listed", refs.contains("refs/testing/nested/yetanotherref"));
    }

    @Test
    public void testListRefsWithoutPrefix() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.ref("refs/testing/testref");
        testGitClient.ref("refs/testing/nested/anotherref");
        testGitClient.ref("refs/testing/nested/yetanotherref");
        Set<String> allRefs = testGitClient.getRefNames("");
        assertTrue("ref testref not listed", allRefs.contains("refs/testing/testref"));
        assertTrue("ref anotherref not listed", allRefs.contains("refs/testing/nested/anotherref"));
        assertTrue("ref yetanotherref not listed", allRefs.contains("refs/testing/nested/yetanotherref"));
    }

    @Test
    public void testRefExists() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.ref("refs/testing/testref");
        assertTrue(testGitClient.refExists("refs/testing/testref"));
        assertFalse(testGitClient.refExists("refs/testing/testref_notfound"));
        assertFalse(testGitClient.refExists("refs/testing2/yetanother"));
    }

    @Test
    public void testHasGitRepoWithValidGitRepo() throws Exception {
        assertTrue("Valid Git repo reported as invalid", testGitClient.hasGitRepo());
    }

    @Test
    public void testCleanWithParameter() throws Exception {
        workspace.commitEmpty("init");

        final String dirName1 = "dir1";
        final String fileName1 = dirName1 + File.separator + "fileName1";
        final String fileName2 = "fileName2";
        assertTrue("Did not create dir " + dirName1, workspace.file(dirName1).mkdir());
        workspace.touch(workspace.getGitFileDir(), fileName1, "");
        workspace.touch(workspace.getGitFileDir(), fileName2, "");

        final String dirName3 = "dir-with-submodule";
        File submodule = workspace.file(dirName3);
        assertTrue("Did not create dir " + dirName3, submodule.mkdir());
        WorkspaceWithRepo workspace1 = new WorkspaceWithRepo(submodule, gitImplName, TaskListener.NULL);
        workspace1.getGitClient().init();
        final String userName = "root";
        final String emailAddress = "root@mydomain.com";
        workspace1.getCliGitCommand().run("config", "user.name", userName);
        workspace1.getCliGitCommand().run("config", "user.email", emailAddress);
        workspace1.getGitClient().setAuthor(userName, emailAddress);
        workspace1.getGitClient().setCommitter(userName, emailAddress);
        workspace1.commitEmpty("init");

        testGitClient.clean();
        assertFalse(workspace.exists(dirName1));
        assertFalse(workspace.exists(fileName1));
        assertFalse(workspace.exists(fileName2));
        assertTrue(workspace.exists(dirName3));

        testGitClient.clean(true);
        assertFalse(workspace.exists(dirName3));

    }

    @Issue({"JENKINS-20410", "JENKINS-27910", "JENKINS-22434"})
    @Test
    public void testClean() throws Exception {
        workspace.commitEmpty("init");

        /* String starts with a surrogate character, mathematical
         * double struck small t as the first character of the file
         * name. The last three characters of the file name are three
         * different forms of the a-with-ring character. Refer to
         * http://unicode.org/reports/tr15/#Detecting_Normalization_Forms
         * for the source of those example characters.
         */
        final String fileName = "\uD835\uDD65-\u5c4f\u5e55\u622a\u56fe-\u0041\u030a-\u00c5-\u212b-fileName.xml";
        workspace.touch(testGitDir, fileName, "content " + fileName);
        withSystemLocaleReporting(fileName, () -> {
            testGitClient.add(fileName);
            testGitClient.commit(fileName);
        });

        /* JENKINS-27910 reported that certain cyrillic file names
         * failed to delete if the encoding was not UTF-8.
         */
        final String fileNameSwim =
                "\u00d0\u00bf\u00d0\u00bb\u00d0\u00b0\u00d0\u00b2\u00d0\u00b0\u00d0\u00bd\u00d0\u00b8\u00d0\u00b5-swim.png";
        workspace.touch(testGitDir, fileNameSwim, "content " + fileNameSwim);
        withSystemLocaleReporting(fileNameSwim, () -> {
            testGitClient.add(fileNameSwim);
            testGitClient.commit(fileNameSwim);
        });

        final String fileNameFace = "\u00d0\u00bb\u00d0\u00b8\u00d1\u2020\u00d0\u00be-face.png";
        workspace.touch(testGitDir, fileNameFace, "content " + fileNameFace);
        withSystemLocaleReporting(fileNameFace, () -> {
            testGitClient.add(fileNameFace);
            testGitClient.commit(fileNameFace);
        });

        workspace.touch(testGitDir, ".gitignore", ".test");
        testGitClient.add(".gitignore");
        testGitClient.commit("ignore");

        final String dirName1 = "\u5c4f\u5e55\u622a\u56fe-dir-not-added";
        final String fileName1 = dirName1 + File.separator + "\u5c4f\u5e55\u622a\u56fe-fileName1-not-added.xml";
        final String fileName2 = ".test-\u00f8\u00e4\u00fc\u00f6-fileName2-not-added";
        assertTrue("Did not create dir " + dirName1, workspace.file(dirName1).mkdir());
        workspace.touch(testGitDir, fileName1, "");
        workspace.touch(testGitDir, fileName2, "");
        workspace.touch(testGitDir, fileName, "new content");

        testGitClient.clean();
        assertFalse(workspace.exists(dirName1));
        assertFalse(workspace.exists(fileName1));
        assertFalse(workspace.exists(fileName2));
        assertEquals("content " + fileName, workspace.contentOf(fileName));
        assertEquals("content " + fileNameFace, workspace.contentOf(fileNameFace));
        assertEquals("content " + fileNameSwim, workspace.contentOf(fileNameSwim));
        String status = workspace.launchCommand("git", "status");
        assertTrue("unexpected status " + status,
                status.contains("working directory clean") || status.contains("working tree clean"));

        /* A few poorly placed tests of hudson.FilePath - testing JENKINS-22434 */
        FilePath fp = new FilePath(workspace.file(fileName));
        assertTrue(fp + " missing", fp.exists());

        assertTrue("mkdir " + dirName1 + " failed", workspace.file(dirName1).mkdir());
        assertTrue("dir " + dirName1 + " missing", workspace.file(dirName1).isDirectory());
        FilePath dir1 = new FilePath(workspace.file(dirName1));
        workspace.touch(testGitDir, fileName1, "");
        assertTrue("Did not create file " + fileName1, workspace.file(fileName1).exists());

        assertTrue(dir1 + " missing", dir1.exists());
        dir1.deleteRecursive(); /* Fails on Linux JDK 7 with LANG=C, ok with LANG=en_US.UTF-8 */
        /* Java reports "Malformed input or input contains unmappable characters" */
        assertFalse("Did not delete file " + fileName1, workspace.file(fileName1).exists());
        assertFalse(dir1 + " not deleted", dir1.exists());

        workspace.touch(testGitDir, fileName2, "");
        FilePath fp2 = new FilePath(workspace.file(fileName2));

        assertTrue(fp2 + " missing", fp2.exists());
        fp2.delete();
        assertFalse(fp2 + " not deleted", fp2.exists());

        String dirContents = Arrays.toString((new File(testGitDir.getAbsolutePath())).listFiles());
        String finalStatus = workspace.launchCommand("git", "status");
        assertTrue("unexpected final status " + finalStatus + " dir contents: " + dirContents,
                finalStatus.contains("working directory clean") || finalStatus.contains("working tree clean"));

    }

    @Test
    public void testPushTags() throws Exception {
        /* Working Repo with commit */
        final String fileName1 = "file1";
        workspace.touch(testGitDir, fileName1, fileName1 + " content " + java.util.UUID.randomUUID().toString());
        testGitClient.add(fileName1);
        testGitClient.commit("commit1");
        ObjectId commit1 = workspace.head();

        /* Clone working repo to bare repo */
        WorkspaceWithRepo bare = new WorkspaceWithRepo(temporaryDirectoryAllocator.allocate(), gitImplName, TaskListener.NULL);
        bare.initBareRepo(testGitClient, true);
        testGitClient.setRemoteUrl("origin", bare.getGitFileDir().getAbsolutePath());
        Set<Branch> remoteBranchesEmpty = testGitClient.getRemoteBranches();
        assertThat(remoteBranchesEmpty, is(empty()));
//        testGitClient.push().ref("master").to(new URIish("origin")).execute();
        testGitClient.push("origin", "master");
        ObjectId bareCommit1 = bare.getGitClient().getHeadRev(bare.getGitFileDir().getAbsolutePath(), "master");
        assertEquals("bare != working", commit1, bareCommit1);
        assertEquals(commit1, bare.getGitClient().getHeadRev(bare.getGitFileDir().getAbsolutePath(), "refs/heads/master"));

        /* Add tag1 to working repo without pushing it to bare repo */
        workspace.tag("tag1");
        assertTrue("tag1 wasn't created", testGitClient.tagExists("tag1"));
        assertEquals("tag1 points to wrong commit", commit1, testGitClient.revParse("tag1"));
        testGitClient.push().ref("master").to(new URIish(bare.getGitFileDir().getAbsolutePath())).tags(false).execute();
        assertFalse("tag1 pushed unexpectedly", bare.launchCommand("git", "tag").contains("tag1"));

        /* Push tag1 to bare repo */
        testGitClient.push().ref("master").to(new URIish(bare.getGitFileDir().getAbsolutePath())).tags(true).execute();
        assertTrue("tag1 not pushed", bare.launchCommand("git", "tag").contains("tag1"));

        /* Create a new commit, move tag1 to that commit, attempt push */
        workspace.touch(testGitDir, fileName1, fileName1 + " content " + java.util.UUID.randomUUID().toString());
        testGitClient.add(fileName1);
        testGitClient.commit("commit2");
        ObjectId commit2 = workspace.head();
        workspace.tag("tag1", true); /* Tag already exists, move from commit1 to commit2 */
        assertTrue("tag1 wasn't created", testGitClient.tagExists("tag1"));
        assertEquals("tag1 points to wrong commit", commit2, testGitClient.revParse("tag1"));
        try {
            testGitClient.push().ref("master").to(new URIish(bare.getGitFileDir().getAbsolutePath())).tags(true).execute();
            /* JGit does not throw exception updating existing tag - ugh */
            /* CliGit before 1.8 does not throw exception updating existing tag - ugh */
            if (testGitClient instanceof CliGitAPIImpl && workspace.cgit().isAtLeastVersion(1,8,0,0)) {
                fail("Modern CLI git should throw exception pushing a change to existing tag");
            }
        } catch (GitException ge) {
            assertThat(ge.getMessage(), containsString("already exists"));
        }

        try {
            testGitClient.push().ref("master").to(new URIish(bare.getGitFileDir().getAbsolutePath())).tags(true).force(false).execute();
            /* JGit does not throw exception updating existing tag - ugh */
            /* CliGit before 1.8 does not throw exception updating existing tag - ugh */
            if (testGitClient instanceof CliGitAPIImpl && workspace.cgit().isAtLeastVersion(1,8,0,0)) {
                fail("Modern CLI git should throw exception pushing a change to existing tag");
            }
        } catch (GitException ge) {
            assertThat(ge.getMessage(), containsString("already exists"));
        }
        testGitClient.push().ref("master").to(new URIish(bare.getGitFileDir().getAbsolutePath())).tags(true).force(true).execute();

        /* Add tag to working repo without pushing it to the bare
         * repo, tests the default behavior when tags() is not added
         * to PushCommand.
         */
        workspace.tag("tag3");
        assertTrue("tag3 wasn't created", testGitClient.tagExists("tag3"));
        testGitClient.push().ref("master").to(new URIish(bare.getGitFileDir().getAbsolutePath())).execute();
        assertFalse("tag3 was pushed", bare.launchCommand("git", "tag").contains("tag3"));

        /* Add another tag to working repo and push tags to the bare repo */
        final String fileName2 = "file2";
        workspace.touch(testGitDir, fileName2, fileName2 + " content " + java.util.UUID.randomUUID().toString());
        testGitClient.add(fileName2);
        testGitClient.commit("commit2");
        workspace.tag("tag2");
        assertTrue("tag2 wasn't created", testGitClient.tagExists("tag2"));
        testGitClient.push().ref("master").to(new URIish(bare.getGitFileDir().getAbsolutePath())).tags(true).execute();
        assertTrue("tag1 wasn't pushed", bare.launchCommand("git", "tag").contains("tag1"));
        assertTrue("tag2 wasn't pushed", bare.launchCommand("git", "tag").contains("tag2"));
        assertTrue("tag3 wasn't pushed", bare.launchCommand("git", "tag").contains("tag3"));
    }

    @Issue("JENKINS-34309")
    @Test
    public void testListBranches() throws Exception {
        Set<Branch> branches = testGitClient.getBranches();
        assertEquals(0, branches.size()); // empty repo should have 0 branches
        workspace.commitEmpty("init");

        testGitClient.branch("test");
        workspace.touch(testGitDir, "test-branch.txt", "");
        testGitClient.add("test-branch.txt");
        // JGit commit doesn't end commit message with Ctrl-M, even when passed
        final String testBranchCommitMessage = "test branch commit ends in Ctrl-M";
        workspace.jgit().commit(testBranchCommitMessage + "\r");

        testGitClient.branch("another");
        workspace.touch(testGitDir, "another-branch.txt", "");
        testGitClient.add("another-branch.txt");
        // CliGit commit doesn't end commit message with Ctrl-M, even when passed
        final String anotherBranchCommitMessage = "test branch commit ends in Ctrl-M";
        workspace.cgit().commit(anotherBranchCommitMessage + "\r");

        branches = testGitClient.getBranches();
        assertBranchesExist(branches, "master", "test", "another");
        assertEquals(3, branches.size());
        String output = workspace.launchCommand("git", "branch", "-v", "--no-abbrev");
        assertTrue("git branch -v --no-abbrev missing test commit msg: '" + output + "'", output.contains(testBranchCommitMessage));
        assertTrue("git branch -v --no-abbrev missing another commit msg: '" + output + "'", output.contains(anotherBranchCommitMessage));
        if (workspace.cgit().isAtLeastVersion(2, 13, 0, 0) && !workspace.cgit().isAtLeastVersion(2, 30, 0, 0)) {
            assertTrue("git branch -v --no-abbrev missing Ctrl-M: '" + output + "'", output.contains("\r"));
            assertTrue("git branch -v --no-abbrev missing test commit msg Ctrl-M: '" + output + "'", output.contains(testBranchCommitMessage + "\r"));
            assertTrue("git branch -v --no-abbrev missing another commit msg Ctrl-M: '" + output + "'", output.contains(anotherBranchCommitMessage + "\r"));
        } else {
            assertFalse("git branch -v --no-abbrev contains Ctrl-M: '" + output + "'", output.contains("\r"));
            assertFalse("git branch -v --no-abbrev contains test commit msg Ctrl-M: '" + output + "'", output.contains(testBranchCommitMessage + "\r"));
            assertFalse("git branch -v --no-abbrev contains another commit msg Ctrl-M: '" + output + "'", output.contains(anotherBranchCommitMessage + "\r"));
        }
    }

    @Test
    public void testListRemoteBranches() throws Exception {
        WorkspaceWithRepo remote = new WorkspaceWithRepo(temporaryDirectoryAllocator.allocate(), gitImplName, TaskListener.NULL);
        remote.getGitClient().init();
        final String userName = "root";
        final String emailAddress = "root@mydomain.com";
        remote.getCliGitCommand().run("config", "user.name", userName);
        remote.getCliGitCommand().run("config", "user.email", emailAddress);
        remote.getGitClient().setAuthor(userName, emailAddress);
        remote.getGitClient().setCommitter(userName, emailAddress);

        remote.commitEmpty("init");
        remote.getGitClient().branch("test");
        remote.getGitClient().branch("another");

        workspace.launchCommand("git", "remote", "add", "origin", remote.getGitFileDir().getAbsolutePath());
        workspace.launchCommand("git", "fetch", "origin");
        Set<Branch> branches = testGitClient.getRemoteBranches();
        assertBranchesExist(branches, "origin/master", "origin/test", "origin/another");
        assertEquals(3, branches.size());
    }

    /**
     * inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue
     */
    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }

    private void withSystemLocaleReporting(String fileName, GitAPITestCase.TestedCode code) throws Exception {
        try {
            code.run();
        } catch (GitException ge) {
            // Exception message should contain the actual file name.
            // It may just contain ? for characters that are not encoded correctly due to the system locale.
            // If such a mangled file name is seen instead, throw a clear exception to indicate the root cause.
            assertTrue("System locale does not support filename '" + fileName + "'", ge.getMessage().contains("?"));
            // Rethrow exception for all other issues.
            throw ge;
        }
    }

    @FunctionalInterface
    interface TestedCode {
        void run() throws Exception;
    }

}
