package org.jenkinsci.plugins.gitclient;

import hudson.FilePath;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IGitAPI;
import hudson.util.StreamTaskListener;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.jenkinsci.plugins.gitclient.StringSharesPrefix.sharesPrefix;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
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
    public GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule secondRepo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule thirdRepo = new GitClientSampleRepoRule();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

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

    @BeforeClass
    public static void loadLocalMirror() throws Exception {
        /* Prime the local mirror cache before other tests run */
        /* Allow 2-5 second delay before priming the cache */
        /* Allow other tests a better chance to prime the cache */
        /* 2-5 second delay is small compared to execution time of this test */
        Random random = new Random();
        Thread.sleep((2 + random.nextInt(4)) * 1000L); // Wait 2-5 seconds before priming the cache
        TaskListener mirrorListener = StreamTaskListener.fromStdout();
        File tempDir = Files.createTempDirectory("PrimeGITAPITest").toFile();
        WorkspaceWithRepo cache = new WorkspaceWithRepo(tempDir, "git", mirrorListener);
        cache.localMirror();
        Util.deleteRecursive(tempDir);
    }

    @Before
    public void setUpRepositories() throws Exception {
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

        workspace = new WorkspaceWithRepo(repo.getRoot(), gitImplName, listener);

        testGitClient = workspace.getGitClient();
        testGitDir = workspace.getGitFileDir();
        cliGitCommand = workspace.getCliGitCommand();
        initializeWorkspace(workspace);
    }

    @After
    public void afterTearDown() throws Exception {
        try {
            String messages = StringUtils.join(handler.getMessages(), ";");
            assertTrue("Logging not started: " + messages, handler.containsMessageSubstring(LOGGING_STARTED));
            assertCheckoutTimeout();
            assertCloneTimeout();
            assertFetchTimeout();
            assertSubmoduleUpdateTimeout();
            assertRevParseCalls(revParseBranchName);
        } finally {
            handler.close();
        }
    }

    private void assertCheckoutTimeout() {
        if (checkoutTimeout > 0) {
            assertSubstringTimeout("git checkout", checkoutTimeout);
        }
    }

    private void assertCloneTimeout() {
        if (cloneTimeout > 0) {
            // clone_() uses "git fetch" internally, not "git clone"
            assertSubstringTimeout("git fetch", cloneTimeout);
        }
    }

    private void assertFetchTimeout() {
        if (fetchTimeout > 0) {
            assertSubstringTimeout("git fetch", fetchTimeout);
        }
    }

    private void assertSubmoduleUpdateTimeout() {
        if (submoduleUpdateTimeout > 0) {
            assertSubstringTimeout("git submodule update", submoduleUpdateTimeout);
        }
    }

    private void assertSubstringTimeout(final String substring, int expectedTimeout) {
        if (!(testGitClient instanceof CliGitAPIImpl)) { // Timeout only implemented in CliGitAPIImpl
            return;
        }
        List<String> messages = handler.getMessages();
        List<String> substringMessages = new ArrayList<>();
        List<String> substringTimeoutMessages = new ArrayList<>();
        final String messageRegEx = ".*\\b" + substring + "\\b.*"; // the expected substring
        final String timeoutRegEx = messageRegEx
                + " [#] timeout=" + expectedTimeout + "\\b.*"; // # timeout=<value>
        for (String message : messages) {
            if (message.matches(messageRegEx)) {
                substringMessages.add(message);
            }
            if (message.matches(timeoutRegEx)) {
                substringTimeoutMessages.add(message);
            }
        }
        assertThat(messages, is(not(empty())));
        assertThat(substringMessages, is(not(empty())));
        assertThat(substringTimeoutMessages, is(not(empty())));
        assertEquals(substringMessages, substringTimeoutMessages);
    }

    /* JENKINS-33258 detected many calls to git rev-parse. This checks
     * those calls are not being made. The createRevParseBranch call
     * creates a branch whose name is unknown to the tests. This
     * checks that the branch name is not mentioned in a call to
     * git rev-parse.
     */
    private void assertRevParseCalls(String branchName) {
        if (revParseBranchName == null) {
            return;
        }
        String messages = StringUtils.join(handler.getMessages(), ";");
        // Linux uses rev-parse without quotes
        assertFalse("git rev-parse called: " + messages, handler.containsMessageSubstring("rev-parse " + branchName));
        // Windows quotes the rev-parse argument
        assertFalse("git rev-parse called: " + messages, handler.containsMessageSubstring("rev-parse \"" + branchName));
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
        WorkspaceWithRepo bare = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
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
        WorkspaceWithRepo remote = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        initializeWorkspace(remote);
        remote.commitEmpty("init");
        remote.getGitClient().branch("test");
        remote.getGitClient().branch("another");

        workspace.launchCommand("git", "remote", "add", "origin", remote.getGitFileDir().getAbsolutePath());
        workspace.launchCommand("git", "fetch", "origin");
        Set<Branch> branches = testGitClient.getRemoteBranches();
        assertBranchesExist(branches, "origin/master", "origin/test", "origin/another");
        assertEquals(3, branches.size());
    }

    @Test
    public void testRemoteListTagsWithFilter() throws Exception {
        WorkspaceWithRepo remote = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        initializeWorkspace(remote);
        remote.commitEmpty("init");
        remote.tag("test");
        remote.tag("another_test");
        remote.tag("yet_another");

        workspace.launchCommand("git", "remote", "add", "origin", remote.getGitFileDir().getAbsolutePath());
        workspace.launchCommand("git", "fetch", "origin");
        Set<String> local_tags = testGitClient.getTagNames("*test");
        Set<String> tags = testGitClient.getRemoteTagNames("*test");
        assertTrue("expected tag test not listed", tags.contains("test"));
        assertTrue("expected tag another_test not listed", tags.contains("another_test"));
        assertFalse("unexpected yet_another tag listed", tags.contains("yet_another"));
    }

    @Test
    public void testRemoteListTagsWithoutFilter() throws Exception {
        WorkspaceWithRepo remote = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        initializeWorkspace(remote);
        remote.commitEmpty("init");
        remote.tag("test");
        remote.tag("another_test");
        remote.tag("yet_another");

        workspace.launchCommand("git", "remote", "add", "origin", remote.getGitFileDir().getAbsolutePath());
        workspace.launchCommand("git", "fetch",  "origin");
        Set<String> allTags = workspace.getGitClient().getRemoteTagNames(null);
        assertTrue("tag 'test' not listed", allTags.contains("test"));
        assertTrue("tag 'another_test' not listed", allTags.contains("another_test"));
        assertTrue("tag 'yet_another' not listed", allTags.contains("yet_another"));
    }

    @Issue("JENKINS-23299")
    @Test
    public void testCreateTag() throws Exception {
        final String gitDir = testGitDir.getAbsolutePath() + File.separator + ".git";
        workspace.commitEmpty("init");
        ObjectId commitId = testGitClient.revParse("HEAD");
        testGitClient.tag("test", "this is an annotated tag");

        /*
         * Spec: "test" (short tag syntax)
         * CliGit does not support this syntax for remotes.
         * JGit fully supports this syntax.
         *
         * JGit seems to have the better behavior in this case, always
         * returning the SHA1 of the commit. Most users are using
         * command line git, so the difference is retained in command
         * line git for compatibility with any legacy command line git
         * use cases which depend on returning null rather than the
         * SHA-1 of the commit to which the annotated tag points.
         */
        final String shortTagRef = "test";
        ObjectId tagHeadIdByShortRef = testGitClient.getHeadRev(gitDir, shortTagRef);
        if (testGitClient instanceof JGitAPIImpl) {
            assertEquals("annotated tag does not match commit SHA1", commitId, tagHeadIdByShortRef);
        } else {
            assertNull("annotated tag unexpectedly not null", tagHeadIdByShortRef);
        }
        assertEquals("annotated tag does not match commit SHA1", commitId, testGitClient.revParse(shortTagRef));

        /*
         * Spec: "refs/tags/test" (more specific tag syntax)
         * CliGit and JGit fully support this syntax.
         */
        final String longTagRef = "refs/tags/test";
        assertEquals("annotated tag does not match commit SHA1", commitId, testGitClient.getHeadRev(gitDir, longTagRef));
        assertEquals("annotated tag does not match commit SHA1", commitId, testGitClient.revParse(longTagRef));

        final String tagNames = workspace.launchCommand("git", "tag", "-l").trim();
        assertEquals("tag not created", "test", tagNames);

        final String tagNamesWithMessages = workspace.launchCommand("git", "tag", "-l", "-n1");
        assertTrue("unexpected tag message : " + tagNamesWithMessages, tagNamesWithMessages.contains("this is an annotated tag"));

        ObjectId invalidTagId = testGitClient.getHeadRev(gitDir, "not-a-valid-tag");
        assertNull("did not expect reference for invalid tag but got : " + invalidTagId, invalidTagId);
    }

    @Test
    public void testRevparseSHA1HEADorTag() throws Exception {
        workspace.commitEmpty("init");
        workspace.touch(testGitDir, "file1", "");
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        workspace.tag("test");
        final String sha1 = workspace.launchCommand("git", "rev-parse", "HEAD").substring(0, 40);
        assertEquals(sha1, testGitClient.revParse(sha1).name());
        assertEquals(sha1, testGitClient.revParse("HEAD").name());
        assertEquals(sha1, testGitClient.revParse("test").name());
    }

    @Test
    public void testRevparseThrowsExpectedException() throws Exception {
        workspace.commitEmpty("init");
        thrown.expect(GitException.class);
        thrown.expectMessage("unknown-to-rev-parse");
        testGitClient.revParse("unknown-to-rev-parse");
    }

    @Test
    public void testPush() throws Exception {
        workspace.commitEmpty("init");
        workspace.touch(testGitDir, "file1", "");
        workspace.tag("file1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        ObjectId sha1 = workspace.head();

        WorkspaceWithRepo remote = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        remote.initBareRepo(remote.getGitClient(), true);
        workspace.launchCommand("git", "remote", "add", "origin", remote.getGitFileDir().getAbsolutePath());

        testGitClient.push("origin", "master");
        String remoteSha1 = remote.launchCommand("git", "rev-parse", "master").substring(0, 40);
        assertEquals(sha1.name(), remoteSha1);
    }

    @Test
    public void testNotesAddFirstNote() throws Exception {
        workspace.touch(testGitDir, "file1", "");
        testGitClient.add("file1");
        workspace.commitEmpty("init");

        testGitClient.addNote("foo", "commits");
        assertEquals("foo\n", workspace.launchCommand("git", "notes", "show"));
        testGitClient.appendNote("alpha\rbravo\r\ncharlie\r\n\r\nbar\n\n\nzot\n\n", "commits");
        // cgit normalizes CR+LF aggressively
        // it appears to be collpasing CR+LF to LF, then truncating duplicate LFs down to 2
        // note that CR itself is left as is
        assertEquals("foo\n\nalpha\rbravo\ncharlie\n\nbar\n\nzot\n", workspace.launchCommand("git", "notes", "show"));
    }

    @Test
    public void testNotesAppendFirstNote() throws Exception {
        initializeWorkspace(workspace);
        workspace.touch(testGitDir, "file1", "");
        testGitClient.add("file1");
        workspace.commitEmpty("init");

        testGitClient.appendNote("foo", "commits");
        assertEquals("foo\n", workspace.launchCommand("git", "notes", "show"));
        testGitClient.appendNote("alpha\rbravo\r\ncharlie\r\n\r\nbar\n\n\nzot\n\n", "commits");
        // cgit normalizes CR+LF aggressively
        // it appears to be collpasing CR+LF to LF, then truncating duplicate LFs down to 2
        // note that CR itself is left as is
        assertEquals("foo\n\nalpha\rbravo\ncharlie\n\nbar\n\nzot\n", workspace.launchCommand("git", "notes", "show"));
    }

    @Test
    public void testPrune() throws Exception {
        // pretend that 'teamWorkspace' is a team repository and workspace1 and workspace2 are team members
        WorkspaceWithRepo teamWorkspace = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        teamWorkspace.initBareRepo(teamWorkspace.getGitClient(), true);

        WorkspaceWithRepo workspace1 = new WorkspaceWithRepo(thirdRepo.getRoot(), gitImplName, TaskListener.NULL);
        initializeWorkspace(workspace1);
        WorkspaceWithRepo workspace2 = workspace;

        workspace1.commitEmpty("c");
        workspace1.launchCommand("git", "remote", "add", "origin", teamWorkspace.getGitFileDir().getAbsolutePath());

        workspace1.launchCommand("git", "push", "origin", "master:b1");
        workspace1.launchCommand("git", "push", "origin", "master:b2");
        workspace1.launchCommand("git", "push", "origin", "master");

        workspace2.launchCommand("git", "remote", "add", "origin", teamWorkspace.getGitFileDir().getAbsolutePath());
        workspace2.launchCommand("git", "fetch", "origin");

        // at this point both ws1&ws2 have several remote tracking branches

        workspace1.launchCommand("git", "push", "origin", ":b1");
        workspace1.launchCommand("git", "push", "origin", "master:b3");

        workspace2.getGitClient().prune(new RemoteConfig(new Config(),"origin"));

        assertFalse(workspace2.exists(".git/refs/remotes/origin/b1"));
        assertTrue( workspace2.exists(".git/refs/remotes/origin/b2"));
        assertFalse(workspace2.exists(".git/refs/remotes/origin/b3"));
    }

    @Test
    public void testRevListAll() throws Exception {
        workspace.launchCommand("git", "pull", workspace.localMirror());

        final StringBuilder out = new StringBuilder();
        for (ObjectId id : testGitClient.revListAll()) {
            out.append(id.name()).append('\n');
        }
        final String all = workspace.launchCommand("git", "rev-list", "--all");
        assertEquals(all, out.toString());
    }

    @Test
    public void testRevList_() throws Exception {
        List<ObjectId> oidList = new ArrayList<>();
        workspace.launchCommand("git", "pull", workspace.localMirror());

        RevListCommand revListCommand = testGitClient.revList_();
        revListCommand.all(true);
        revListCommand.to(oidList);
        revListCommand.execute();

        final StringBuilder out = new StringBuilder();
        for (ObjectId id : oidList) {
            out.append(id.name()).append('\n');
        }
        final String all = workspace.launchCommand("git", "rev-list", "--all");
        assertEquals(all, out.toString());
    }

    @Test
    public void testRevListFirstParent() throws Exception {
        workspace.launchCommand("git", "pull", workspace.localMirror());

        for (Branch b : testGitClient.getRemoteBranches()) {
            final StringBuilder out = new StringBuilder();
            List<ObjectId> oidList = new ArrayList<>();

            RevListCommand revListCommand = testGitClient.revList_();
            revListCommand.firstParent(true);
            revListCommand.to(oidList);
            revListCommand.reference(b.getName());
            revListCommand.execute();

            for (ObjectId id : oidList) {
                out.append(id.name()).append('\n');
            }

            final String all = workspace.launchCommand("git", "rev-list", "--first-parent",  b.getName());
            assertEquals(all, out.toString());
        }
    }

    @Test
    public void testRevList() throws Exception {
        workspace.launchCommand("git", "pull", workspace.localMirror());

        for (Branch b : testGitClient.getRemoteBranches()) {
            final StringBuilder out = new StringBuilder();
            for (ObjectId id : testGitClient.revList(b.getName())) {
                out.append(id.name()).append('\n');
            }
            String all = workspace.launchCommand("git", "rev-list", b.getName());
            assertEquals(all, out.toString());
        }
    }

    @Test
    public void testMergeStrategy() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file", "content1");
        testGitClient.add("file");
        testGitClient.commit("commit1");
        testGitClient.checkout().ref("master").execute();
        testGitClient.branch("branch2");
        testGitClient.checkout().ref("branch2").execute();
        workspace.touch(testGitDir,"file", "content2");
        File f = workspace.file("file");
        testGitClient.add("file");
        testGitClient.commit("commit2");
        testGitClient.merge().setStrategy(MergeCommand.Strategy.OURS).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1")).execute();
        assertEquals("merge didn't selected OURS content", "content2", FileUtils.readFileToString(f, "UTF-8"));
    }

    @Test
    public void testMergeStrategyCorrectFail() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file", "content1");
        testGitClient.add("file");
        testGitClient.commit("commit1");
        testGitClient.checkout().ref("master").execute();
        testGitClient.branch("branch2");
        testGitClient.checkout().ref("branch2").execute();
        workspace.touch(testGitDir, "file", "content2");
        testGitClient.add("file");
        testGitClient.commit("commit2");

        thrown.expect(GitException.class);
        testGitClient.merge().setStrategy(MergeCommand.Strategy.RESOLVE).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1")).execute();
    }

    @Issue("JENKINS-12402")
    @Test
    public void testMergeFastForwardModeFF() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        final ObjectId branch1 = workspace.head();

        testGitClient.checkout().ref("master").execute();
        testGitClient.branch("branch2");
        testGitClient.checkout().ref("branch2").execute();
        workspace.touch(testGitDir, "file2", "content2");
        testGitClient.add("file2");
        testGitClient.commit("commit2");
        final ObjectId branch2 = workspace.head();

        testGitClient.checkout().ref("master").execute();

        // The first merge is a fast-forward, master moves to branch1
        testGitClient.merge().setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1")).execute();
        assertEquals("Fast-forward merge failed. master and branch1 should be the same.", workspace.head(), branch1);

        // The second merge calls for fast-forward (FF), but a merge commit will result
        // This tests that calling for FF gracefully falls back to a commit merge
        // master moves to a new commit ahead of branch1 and branch2
        testGitClient.merge().setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch2")).execute();
        // The merge commit (head) should have branch2 and branch1 as parents
        List<ObjectId> revList = testGitClient.revList("HEAD^1");
        assertEquals("Merge commit failed. branch1 should be a parent of HEAD but it isn't.",revList.get(0).name(), branch1.name());
        revList = testGitClient.revList("HEAD^2");
        assertEquals("Merge commit failed. branch2 should be a parent of HEAD but it isn't.",revList.get(0).name(), branch2.name());
    }

    @Test
    public void testMergeFastForwardModeFFOnly() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        final ObjectId branch1 = workspace.head();

        testGitClient.checkout().ref("master").execute();
        testGitClient.branch("branch2");
        testGitClient.checkout().ref("branch2").execute();
        workspace.touch(testGitDir, "file2", "content2");
        testGitClient.add("file2");
        testGitClient.commit("commit2");
        final ObjectId branch2 = workspace.head();

        testGitClient.checkout().ref("master").execute();

        // The first merge is a fast-forward only (FF_ONLY), master moves to branch1
        testGitClient.merge().setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF_ONLY).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1")).execute();
        assertEquals("Fast-forward merge failed. master and branch1 should be the same but aren't.", workspace.head(), branch1);

        // The second merge calls for fast-forward only (FF_ONLY), but a merge commit is required, hence it is expected to fail
        try {
            testGitClient.merge().setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF_ONLY).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch2")).execute();
            fail("Exception not thrown: the fast-forward only mode should have failed");
        } catch (GitException ge) {
            // expected
            assertEquals("Fast-forward merge abort failed. master and branch1 should still be the same as the merge was aborted.",workspace.head(),branch1);
        }
    }

    @Test
    public void testMergeFastForwardModeNoFF() throws Exception {
        workspace.commitEmpty("init");
        final ObjectId base = workspace.head();
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        final ObjectId branch1 = workspace.head();

        testGitClient.checkout().ref("master").execute();
        testGitClient.branch("branch2");
        testGitClient.checkout().ref("branch2").execute();
        workspace.touch(testGitDir, "file2", "content2");
        testGitClient.add("file2");
        testGitClient.commit("commit2");
        final ObjectId branch2 = workspace.head();

        testGitClient.checkout().ref("master").execute();
        final ObjectId master = workspace.head();

        // The first merge is normally a fast-forward, but we're calling for a merge commit which is expected to work
        testGitClient.merge().setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1")).execute();

        // The first merge will have base and branch1 as parents
        List<ObjectId> revList = testGitClient.revList("HEAD^1");
        assertEquals("Merge commit failed. base should be a parent of HEAD but it isn't.",revList.get(0).name(), base.name());
        revList = testGitClient.revList("HEAD^2");
        assertEquals("Merge commit failed. branch1 should be a parent of HEAD but it isn't.",revList.get(0).name(), branch1.name());

        final ObjectId base2 = workspace.head();

        // Calling for NO_FF when required is expected to work
        testGitClient.merge().setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch2")).execute();

        // The second merge will have base2 and branch2 as parents
        revList = testGitClient.revList("HEAD^1");
        assertEquals("Merge commit failed. base2 should be a parent of HEAD but it isn't.",revList.get(0).name(), base2.name());
        revList = testGitClient.revList("HEAD^2");
        assertEquals("Merge commit failed. branch2 should be a parent of HEAD but it isn't.",revList.get(0).name(), branch2.name());
    }

    @Test
    public void testMergeSquash() throws Exception {
        workspace.commitEmpty("init");

        //First commit to branch1
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");

        //Second commit to branch2
        workspace.touch(testGitDir, "file2", "content2");
        testGitClient.add("file2");
        testGitClient.commit("commit2");

        //Merge branch1 with master, squashing both commits
        testGitClient.checkout().ref("master").execute();
        testGitClient.merge().setSquash(true).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1")).execute();

        //Compare commit counts of before and after commiting the merge, should be  one due to the squashing of commits.
        final int commitCountBefore = testGitClient.revList("HEAD").size();
        testGitClient.commit("commitMerge");
        final int commitCountAfter = testGitClient.revList("HEAD").size();

        assertEquals("Squash merge failed. Should have merged only one commit.", 1, commitCountAfter - commitCountBefore);
    }

    @Test
    public void testMergeNoSquash() throws Exception {
        workspace.commitEmpty("init");

        //First commit to branch1
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");

        //Second commit to branch2
        workspace.touch(testGitDir, "file2", "content2");
        testGitClient.add("file2");
        testGitClient.commit("commit2");

        //Merge branch1 with master, without squashing commits.
        //Compare commit counts of before and after commiting the merge, should be two due to the no squashing of commits.
        testGitClient.checkout().ref("master").execute();
        final int commitCountBefore = testGitClient.revList("HEAD").size();
        testGitClient.merge().setSquash(false).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1")).execute();
        final int commitCountAfter = testGitClient.revList("HEAD").size();

        assertEquals("Squashless merge failed. Should have merged two commits.", 2, commitCountAfter - commitCountBefore);
    }

    @Test
    public void testMergeNoCommit() throws Exception {
        workspace.commitEmpty("init");

        //Create branch1 and commit a file
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");

        //Merge branch1 with master, without committing the merge.
        //Compare commit counts of before and after the merge, should be zero due to the lack of autocommit.
        testGitClient.checkout().ref("master").execute();
        final int commitCountBefore = testGitClient.revList("HEAD").size();
        testGitClient.merge().setCommit(false).setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1")).execute();
        final int commitCountAfter = testGitClient.revList("HEAD").size();

        assertEquals("No Commit merge failed. Shouldn't have committed any changes.", commitCountBefore, commitCountAfter);
    }

    @Test
    public void testMergeCommit() throws Exception {
        workspace.commitEmpty("init");

        //Create branch1 and commit a file
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");

        //Merge branch1 with master, without committing the merge.
        //Compare commit counts of before and after the merge, should be two due to the commit of the file and the commit of the merge.
        testGitClient.checkout().ref("master").execute();
        final int commitCountBefore = testGitClient.revList("HEAD").size();
        testGitClient.merge().setCommit(true).setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1")).execute();
        final int commitCountAfter = testGitClient.revList("HEAD").size();

        assertEquals("Commit merge failed. Should have committed the merge.", 2, commitCountAfter - commitCountBefore);
    }

    @Test
    public void testMergeWithMessage() throws Exception {
        workspace.commitEmpty("init");

        //Create branch1 and commit a file
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");

        //Merge branch1 into master
        testGitClient.checkout().ref("master").execute();
        final String mergeMessage = "Merge message to be tested.";
        testGitClient.merge().setMessage(mergeMessage).setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF).setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1")).execute();
        //Obtain last commit message
        String resultMessage = "";
        final List<String> content = testGitClient.showRevision(workspace.head());
        if ("gpgsig -----BEGIN PGP SIGNATURE-----".equals(content.get(6).trim())) {
            //Commit is signed so the commit message is after the signature
            for (int i = 6; i < content.size(); i++) {
                if (content.get(i).trim().equals("-----END PGP SIGNATURE-----")) {
                    resultMessage = content.get(i + 2).trim();
                    break;
                }
            }
        } else {
            resultMessage = content.get(7).trim();
        }

        assertEquals("Custom message merge failed. Should have set custom merge message.", mergeMessage, resultMessage);
    }

    @Test
    public void testRebasePassesWithoutConflict() throws Exception {
        workspace.commitEmpty("init");

        //First commit to master
        workspace.touch(testGitDir, "master_file", "master1");
        testGitClient.add("master_file");
        testGitClient.commit("commit-master1");

        //Create a feature branch and make a commit
        testGitClient.branch("feature1");
        testGitClient.checkout().ref("feature1").execute();
        workspace.touch(testGitDir, "feature_file", "feature1");
        testGitClient.add("feature_file");
        testGitClient.commit("commit-feature1");

        //Second commit to master
        testGitClient.checkout().ref("master").execute();
        workspace.touch(testGitDir, "master_file", "master2");
        testGitClient.add("master_file");
        testGitClient.commit("commit-master2");

        //Rebase feature commit onto master
        testGitClient.checkout().ref("feature1").execute();
        testGitClient.rebase().setUpstream("master").execute();

        assertThat("Should've rebased feature1 onto master", testGitClient.revList("feature1").contains(testGitClient.revParse("master")));
        assertEquals("HEAD should be on the rebased branch", testGitClient.revParse("HEAD").name(), testGitClient.revParse("feature1").name());
        assertThat("Rebased file should be present in the worktree", testGitClient.getWorkTree().child("feature_file").exists());
    }

    @Test
    public void testRebaseFailsWithConflict() throws Exception {
        workspace.commitEmpty("init");

        // First commit to master
        workspace.touch(testGitDir, "file", "master1");
        testGitClient.add("file");
        testGitClient.commit("commit-master1");

        //Create a feature branch and make a commit
        testGitClient.branch("feature1");
        testGitClient.checkout().ref("feature1").execute();
        workspace.touch(testGitDir, "file", "feature1");
        testGitClient.add("file");
        testGitClient.commit("commit-feature1");

        //Second commit to master
        testGitClient.checkout().ref("master").execute();
        workspace.touch(testGitDir, "file", "master2");
        testGitClient.add("file");
        testGitClient.commit("commit-master2");

        // Rebase feature commit onto master
        testGitClient.checkout().ref("feature1").execute();
        try {
            testGitClient.rebase().setUpstream("master").execute();
            fail("Rebase did not throw expected GitException");
        } catch (GitException ge) {
            assertEquals("HEAD not reset to the feature branch.", testGitClient.revParse("HEAD").name(), testGitClient.revParse("feature1").name());
            Status status = new org.eclipse.jgit.api.Git(new FileRepository(new File(testGitDir, ".git"))).status().call();
            assertTrue("Workspace is not clean", status.isClean());
            assertFalse("Workspace has uncommitted changes", status.hasUncommittedChanges());
            assertTrue("Workspace has conflicting changes", status.getConflicting().isEmpty());
            assertTrue("Workspace has missing changes", status.getMissing().isEmpty());
            assertTrue("Workspace has modified files", status.getModified().isEmpty());
            assertTrue("Workspace has removed files", status.getRemoved().isEmpty());
            assertTrue("Workspace has untracked files", status.getUntracked().isEmpty());
        }
    }

    /**
     * A rev-parse warning message should not break revision parsing.
     */
    @Issue("JENKINS-11177")
    @Test
    public void testJenkins11177() throws Exception {
        workspace.commitEmpty("init");
        final ObjectId base = workspace.head();
        final ObjectId master = testGitClient.revParse("master");
        assertEquals(base, master);

        /* Make reference to master ambiguous, verify it is reported ambiguous by rev-parse */
        workspace.tag("master");
        final String revParse = workspace.launchCommand("git", "rev-parse", "master");
        assertTrue("'" + revParse + "' does not contain 'ambiguous'", revParse.contains("ambiguous"));
        final ObjectId masterTag = testGitClient.revParse("refs/tags/master");
        assertEquals("masterTag != head", workspace.head(), masterTag);

        /* Get reference to ambiguous master */
        final ObjectId ambiguous = testGitClient.revParse("master");
        assertEquals("ambiguous != master", ambiguous.toString(), master.toString());

        /* Exploring JENKINS-20991 ambigous revision breaks checkout */
        workspace.touch(testGitDir, "file-master", "content-master");
        testGitClient.add("file-master");
        testGitClient.commit("commit1-master");
        final ObjectId masterTip = workspace.head();

        workspace.launchCommand("git", "branch", "branch1", masterTip.name());
        workspace.launchCommand("git", "checkout", "branch1");
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1-branch1");
        final ObjectId branch1 = workspace.head();

        /* JGit checks out the masterTag, while CliGit checks out
         * master branch.  It is risky that there are different
         * behaviors between the two implementations, but when a
         * reference is ambiguous, it is safe to assume that
         * resolution of the ambiguous reference is an implementation
         * specific detail. */
        testGitClient.checkout().ref("master").execute();
        final String messageDetails =
                ", head=" + workspace.head().name() +
                ", masterTip=" + masterTip.name() +
                ", masterTag=" + masterTag.name() +
                ", branch1=" + branch1.name();
        if (testGitClient instanceof CliGitAPIImpl) {
            assertEquals("head != master branch" + messageDetails, masterTip, workspace.head());
        } else {
            assertEquals("head != master tag" + messageDetails, masterTag, workspace.head());
        }
    }

    @Test
    public void testCloneNoCheckout() throws Exception {
        // Create a repo for cloning purpose
        WorkspaceWithRepo repoToClone = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        initializeWorkspace(repoToClone);
        repoToClone.commitEmpty("init");
        repoToClone.touch(repoToClone.getGitFileDir(), "file1", "");
        repoToClone.getGitClient().add("file1");
        repoToClone.getGitClient().commit("commit1");

        // Clone it with no checkout
        testGitClient.clone_().url(repoToClone.getGitFileDir().getAbsolutePath()).repositoryName("origin").noCheckout().execute();
        assertFalse(workspace.exists("file1"));
    }

    @Issue("JENKINS-25444")
    @Test
    public void testFetchDeleteCleans() throws Exception {
        workspace.touch(testGitDir, "file1", "old");
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        workspace.touch(testGitDir, "file1", "new");
        checkoutTimeout = 1 + random.nextInt(60 * 24);
        testGitClient.checkout().branch("other").ref(Constants.HEAD).timeout(checkoutTimeout).deleteBranchIfExist(true).execute();

        Status status = new org.eclipse.jgit.api.Git(new FileRepository(new File(testGitDir, ".git"))).status().call();

        assertTrue("Workspace must be clean", status.isClean());
    }

    @Test
    public void testDescribe() throws Exception {
        workspace.commitEmpty("first");
        workspace.launchCommand("git", "tag", "-m", "test", "t1");
        workspace.touch(testGitDir, "a", "");
        testGitClient.add("a");
        testGitClient.commit("second");
        assertThat(workspace.launchCommand("git", "describe").trim(), sharesPrefix(testGitClient.describe("HEAD")));

        workspace.launchCommand("git", "tag", "-m", "test2", "t2");
        assertThat(workspace.launchCommand("git", "describe").trim(), sharesPrefix(testGitClient.describe("HEAD")));
    }

    @Test
    public void testRevListTag() throws Exception {
        workspace.commitEmpty("c1");
        FileRepository repo = new FileRepository(new File(testGitDir, ".git"));
        Ref commitRefC1 = repo.exactRef("HEAD");
        workspace.tag("t1");
        Ref tagRefT1 = repo.findRef("t1");
        Ref head = repo.exactRef("HEAD");
        assertEquals("head != t1", head.getObjectId(), tagRefT1.getObjectId());
        workspace.commitEmpty("c2");
        Ref commitRef2 = repo.exactRef("HEAD");
        List<ObjectId> revList = testGitClient.revList("t1");
        assertTrue("c1 not in revList", revList.contains(commitRefC1.getObjectId()));
        assertEquals("wring list size: " + revList, 1, revList.size());
    }

    @Test
    public void testRevListLocalBranch() throws Exception {
        workspace.commitEmpty("c1");
        workspace.tag("t1");
        workspace.commitEmpty("c2");
        List<ObjectId> revList = testGitClient.revList("master");
        assertEquals("Wrong list size: " + revList, 2, revList.size());
    }

    @Issue("JENKINS-20153")
    @Test
    public void testCheckOutBranchNull() throws Exception {
        workspace.commitEmpty("c1");
        String sha1 = testGitClient.revParse("HEAD").name();
        workspace.commitEmpty("c2");

        testGitClient.checkoutBranch(null, sha1);

        assertEquals(workspace.head(), testGitClient.revParse(sha1));

        Ref head = new FileRepository(new File(testGitDir, ".git")).exactRef("HEAD");
        assertFalse(head.isSymbolic());
    }

    @Issue("JENKINS-18988")
    @Test
    public void testLocalCheckoutConflict() throws Exception {
        workspace.touch(testGitDir, "foo", "old");
        testGitClient.add("foo");
        testGitClient.commit("c1");
        workspace.tag("t1");

        // delete the file from git
        workspace.launchCommand("git", "rm", "foo");
        testGitClient.commit("c2");
        assertFalse(workspace.file("foo").exists());

        // now create an untracked local file
        workspace.touch(testGitDir, "foo", "new");

        // this should overwrite foo
        testGitClient.checkout().ref("t1").execute();

        assertEquals("old", FileUtils.readFileToString(workspace.file("foo"), "UTF-8"));
    }

    @Test
    public void testNoSubmodules() throws Exception {
        workspace.touch(testGitDir, "committed-file", "committed-file content " + java.util.UUID.randomUUID().toString());
        testGitClient.add("committed-file");
        testGitClient.commit("commit1");
        IGitAPI igit = (IGitAPI) testGitClient;
        igit.submoduleClean(false);
        igit.submoduleClean(true);
        igit.submoduleUpdate().recursive(false).execute();
        igit.submoduleUpdate().recursive(true).execute();
        igit.submoduleSync();
        assertTrue("committed-file missing at commit1", workspace.file("committed-file").exists());
    }

    @Test
    public void testHasSubmodules() throws Exception {
        workspace.launchCommand("git", "fetch", workspace.localMirror(), "tests/getSubmodules:t");
        testGitClient.checkout().ref("t").execute();
        assertTrue(testGitClient.hasGitModules());

        workspace.launchCommand("git", "fetch", workspace.localMirror(), "master:t2");
        testGitClient.checkout().ref("t2").execute();
        assertFalse(testGitClient.hasGitModules());
        IGitAPI igit = (IGitAPI) testGitClient;
        if (igit instanceof JGitAPIImpl) {
            thrown.expect(UnsupportedOperationException.class);
        } else if (igit instanceof  CliGitAPIImpl){
            thrown.expect(GitException.class);
            thrown.expectMessage("Could not determine remote");
            thrown.expectMessage("origin");
        }
        igit.fixSubmoduleUrls("origin", listener);
    }

    /**
     * Checks that the ChangelogCommand abort() API does not write
     * output to the destination.  Does not check that the abort() API
     * releases resources.
     */
    @Test
    public void testChangeLogAbort() throws Exception {
        final String logMessage = "changelog-abort-test-commit";
        workspace.touch(testGitDir, "file-changelog-abort", "changelog abort file contents " + java.util.UUID.randomUUID().toString());
        testGitClient.add("file-changelog-abort");
        testGitClient.commit(logMessage);
        String sha1 = testGitClient.revParse("HEAD").name();
        ChangelogCommand changelogCommand = testGitClient.changelog();
        StringWriter writer = new StringWriter();
        changelogCommand.to(writer);

        /* Abort the changelog, confirm no content was written */
        changelogCommand.abort();
        assertEquals("aborted changelog wrote data", "", writer.toString());

        /* Execute the changelog, confirm expected content was written */
        changelogCommand = testGitClient.changelog();
        changelogCommand.to(writer);
        changelogCommand.execute();
        assertTrue("No log message in " + writer.toString(), writer.toString().contains(logMessage));
        assertTrue("No SHA1 in " + writer.toString(), writer.toString().contains(sha1));
    }

    private void initializeWorkspace(WorkspaceWithRepo initWorkspace) throws Exception {
        final GitClient initGitClient = initWorkspace.getGitClient();
        final CliGitCommand initCliGitCommand = initWorkspace.getCliGitCommand();
        initGitClient.init();
        final String userName = "root";
        final String emailAddress = "root@mydomain.com";
        initCliGitCommand.run("config", "user.name", userName);
        initCliGitCommand.run("config", "user.email", emailAddress);
        initGitClient.setAuthor(userName, emailAddress);
        initGitClient.setCommitter(userName, emailAddress);
    }

    /**
     * inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue
     */
    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }

    private void withSystemLocaleReporting(String fileName, TestedCode code) throws Exception {
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
