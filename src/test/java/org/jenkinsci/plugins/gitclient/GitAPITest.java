package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IGitAPI;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.Issue;

/**
 * Git API tests implemented in JUnit 5.
 */
@ParameterizedClass(name = "{0}")
@MethodSource("gitObjects")
class GitAPITest {

    @RegisterExtension
    private final GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    @RegisterExtension
    private final GitClientSampleRepoRule secondRepo = new GitClientSampleRepoRule();

    @RegisterExtension
    private final GitClientSampleRepoRule thirdRepo = new GitClientSampleRepoRule();

    private int logCount = 0;
    private final Random random = new Random();
    private static final String LOGGING_STARTED = "Logging started";
    private static final String DEFAULT_MIRROR_BRANCH_NAME = "mast" + "er";
    private LogHandler handler = null;
    private TaskListener listener;

    @Parameter(0)
    private String gitImplName;

    /**
     * Tests that need the default branch name can use this variable.
     */
    private static String defaultBranchName = "mast" + "er"; // Intentionally split string

    private int checkoutTimeout = -1;

    private WorkspaceWithRepo workspace;

    private GitClient testGitClient;
    private File testGitDir;

    public static EnvVars getConfigNoSystemEnvVars() {
        EnvVars envVars = new EnvVars();
        envVars.put("GIT_CONFIG_NOSYSTEM", "1");
        return envVars;
    }

    static List<Arguments> gitObjects() {
        List<Arguments> arguments = new ArrayList<>();
        String[] gitImplNames = {"git", "jgit", "jgitapache"};
        for (String gitImplName : gitImplNames) {
            Arguments item = Arguments.of(gitImplName);
            arguments.add(item);
        }
        return arguments;
    }

    @BeforeAll
    static void loadLocalMirror() throws Exception {
        SystemReader.getInstance().getUserConfig().clear();
        /* Prime the local mirror cache before other tests run */
        /* Allow 2-6 second delay before priming the cache */
        /* Allow other tests a better chance to prime the cache */
        /* 2-6 second delay is small compared to execution time of this test */
        Random random = new Random();
        Thread.sleep(2000L + random.nextInt(4000)); // Wait 2-6 seconds before priming the cache
        TaskListener mirrorListener = StreamTaskListener.fromStdout();
        File tempDir = Files.createTempDirectory("PrimeGitAPITest").toFile();
        WorkspaceWithRepo cache = new WorkspaceWithRepo(tempDir, "git", mirrorListener);
        cache.localMirror();
        Util.deleteRecursive(tempDir);
    }

    /**
     * Determine the global default branch name.
     * Command line git is moving towards more inclusive naming.
     * Git 2.32.0 honors the configuration variable `init.defaultBranch` and uses it for the name of the initial branch.
     * This method reads the global configuration and uses it to set the value of `defaultBranchName`.
     */
    @BeforeAll
    static void computeDefaultBranchName() throws Exception {
        File configDir = Files.createTempDirectory("readGitConfig").toFile();
        CliGitCommand getDefaultBranchNameCmd =
                new CliGitCommand(Git.with(TaskListener.NULL, getConfigNoSystemEnvVars())
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
    void setUpRepositories() throws Exception {
        checkoutTimeout = -1;

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
        workspace.initializeWorkspace();
    }

    @AfterEach
    void afterTearDown() {
        try {
            String messages = StringUtils.join(handler.getMessages(), ";");
            assertTrue(handler.containsMessageSubstring(LOGGING_STARTED), "Logging not started: " + messages);
            assertCheckoutTimeout();
        } finally {
            handler.close();
        }
    }

    private void assertCheckoutTimeout() {
        if (checkoutTimeout > 0) {
            assertSubstringTimeout("git checkout", checkoutTimeout);
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
        final String timeoutRegEx = messageRegEx + " [#] timeout=" + expectedTimeout + "\\b.*"; // # timeout=<value>
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

    private Collection<String> getBranchNames(Collection<Branch> branches) {
        return branches.stream().map(Branch::getName).toList();
    }

    private void assertBranchesExist(Set<Branch> branches, String... names) {
        Collection<String> branchNames = getBranchNames(branches);
        for (String name : names) {
            assertThat(branchNames, hasItem(name));
        }
    }

    @Test
    void testGetRemoteUrl() throws Exception {
        workspace.launchCommand("git", "remote", "add", "origin", "https://github.com/jenkinsci/git-client-plugin.git");
        workspace.launchCommand("git", "remote", "add", "ndeloof", "git@github.com:ndeloof/git-client-plugin.git");
        String remoteUrl = workspace.getGitClient().getRemoteUrl("origin");
        assertEquals(
                "https://github.com/jenkinsci/git-client-plugin.git", remoteUrl, "unexpected remote URL " + remoteUrl);
    }

    @Test
    void testEmptyComment() throws Exception {
        workspace.commitEmpty("init-empty-comment-to-tag-fails-on-windows");
        if (isWindows()) {
            testGitClient.tag("non-empty-comment", "empty-tag-comment-fails-on-windows");
        } else {
            testGitClient.tag("empty-comment", "");
        }
    }

    @Test
    void testCreateBranch() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("test");
        String branches = workspace.launchCommand("git", "branch", "-l");
        assertTrue(branches.contains(defaultBranchName), "default branch not listed");
        assertTrue(branches.contains("test"), "test branch not listed");
    }

    @Test
    void testDeleteBranch() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("test");
        testGitClient.deleteBranch("test");
        String branches = workspace.launchCommand("git", "branch", "-l");
        assertFalse(branches.contains("test"), "deleted test branch still present");

        if (testGitClient instanceof JGitAPIImpl) {
            // JGit does not throw an exception
            testGitClient.deleteBranch("test");
        } else {
            Exception exception = assertThrows(GitException.class, () -> testGitClient.deleteBranch("test"));
            assertThat(exception.getMessage(), is("Could not delete branch test"));
        }
    }

    @Test
    void testDeleteTag() throws Exception {
        workspace.commitEmpty("init");
        workspace.tag("test");
        workspace.tag("another");
        testGitClient.deleteTag("test");
        String tags = workspace.launchCommand("git", "tag");
        assertFalse(tags.contains("test"), "deleted test tag still present");
        assertTrue(tags.contains("another"), "expected tag not listed");
        if (testGitClient instanceof JGitAPIImpl) {
            // JGit does not throw an exception
            testGitClient.deleteTag("test");
        } else {
            Exception exception = assertThrows(GitException.class, () -> testGitClient.deleteTag("test"));
            assertThat(exception.getMessage(), is("Could not delete tag test"));
        }
    }

    @Test
    void testListTagsWithFilter() throws Exception {
        workspace.commitEmpty("init");
        workspace.tag("test");
        workspace.tag("another_test");
        workspace.tag("yet_another");
        Set<String> tags = testGitClient.getTagNames("*test");
        assertTrue(tags.contains("test"), "expected tag test not listed");
        assertTrue(tags.contains("another_test"), "expected tag another_tag not listed");
        assertFalse(tags.contains("yet_another"), "unexpected tag yet_another listed");
    }

    @Test
    void testListTagsWithoutFilter() throws Exception {
        workspace.commitEmpty("init");
        workspace.tag("test");
        workspace.tag("another_test");
        workspace.tag("yet_another");
        Set<String> allTags = testGitClient.getTagNames(null);
        assertTrue(allTags.contains("test"), "tag 'test' not listed");
        assertTrue(allTags.contains("another_test"), "tag 'another_test' not listed");
        assertTrue(allTags.contains("yet_another"), "tag 'yet_another' not listed");
    }

    @Issue("JENKINS-37794")
    @Test
    void testGetTagNamesSupportsSlashesInTagNames() throws Exception {
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
    void testListBranchesContainingRef() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("test");
        testGitClient.branch("another");
        Set<Branch> branches = testGitClient.getBranches();
        assertBranchesExist(branches, defaultBranchName, "test", "another");
        assertEquals(3, branches.size());
    }

    @Test
    void testListTagsStarFilter() throws Exception {
        workspace.commitEmpty("init");
        workspace.tag("test");
        workspace.tag("another_test");
        workspace.tag("yet_another");
        Set<String> allTags = testGitClient.getTagNames("*");
        assertTrue(allTags.contains("test"), "tag 'test' not listed");
        assertTrue(allTags.contains("another_test"), "tag 'another_test' not listed");
        assertTrue(allTags.contains("yet_another"), "tag 'yet_another' not listed");
    }

    @Test
    void testTagExists() throws Exception {
        workspace.commitEmpty("init");
        workspace.tag("test");
        assertTrue(testGitClient.tagExists("test"));
        assertFalse(testGitClient.tagExists("unknown"));
    }

    @Test
    void testGetTagMessage() throws Exception {
        workspace.commitEmpty("init");
        workspace.launchCommand("git", "tag", "test", "-m", "this-is-a-test");
        assertEquals("this-is-a-test", testGitClient.getTagMessage("test"));
    }

    @Test
    void testGetTagMessageMultiLine() throws Exception {
        workspace.commitEmpty("init");
        workspace.launchCommand("git", "tag", "test", "-m", "test 123!\n* multi-line tag message\n padded ");

        // Leading four spaces from each line should be stripped,
        // but not the explicit single space before "padded",
        // and the final errant space at the end should be trimmed
        assertEquals("test 123!\n* multi-line tag message\n padded", testGitClient.getTagMessage("test"));
    }

    @Test
    void testCreateRef() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.ref("refs/testing/testref");
        assertTrue(workspace.launchCommand("git", "show-ref").contains("refs/testing/testref"), "test ref not created");
    }

    @Test
    void testDeleteRef() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.ref("refs/testing/testref");
        testGitClient.ref("refs/testing/anotherref");
        testGitClient.deleteRef("refs/testing/testref");
        String refs = workspace.launchCommand("git", "show-ref");
        assertFalse(refs.contains("refs/testing/testref"), "deleted test tag still present");
        assertTrue(refs.contains("refs/testing/anotherref"), "expected tag not listed");
        testGitClient.deleteRef("refs/testing/testref"); // Double-deletes do nothing.
    }

    @Test
    void testListRefsWithPrefix() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.ref("refs/testing/testref");
        testGitClient.ref("refs/testing/nested/anotherref");
        testGitClient.ref("refs/testing/nested/yetanotherref");
        Set<String> refs = testGitClient.getRefNames("refs/testing/nested/");
        assertFalse(refs.contains("refs/testing/testref"), "ref testref listed");
        assertTrue(refs.contains("refs/testing/nested/anotherref"), "ref anotherref not listed");
        assertTrue(refs.contains("refs/testing/nested/yetanotherref"), "ref yetanotherref not listed");
    }

    @Test
    void testListRefsWithoutPrefix() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.ref("refs/testing/testref");
        testGitClient.ref("refs/testing/nested/anotherref");
        testGitClient.ref("refs/testing/nested/yetanotherref");
        Set<String> allRefs = testGitClient.getRefNames("");
        assertTrue(allRefs.contains("refs/testing/testref"), "ref testref not listed");
        assertTrue(allRefs.contains("refs/testing/nested/anotherref"), "ref anotherref not listed");
        assertTrue(allRefs.contains("refs/testing/nested/yetanotherref"), "ref yetanotherref not listed");
    }

    @Test
    void testRefExists() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.ref("refs/testing/testref");
        assertTrue(testGitClient.refExists("refs/testing/testref"));
        assertFalse(testGitClient.refExists("refs/testing/testref_notfound"));
        assertFalse(testGitClient.refExists("refs/testing2/yetanother"));
    }

    @Test
    void testHasGitRepoWithValidGitRepo() throws Exception {
        assertTrue(testGitClient.hasGitRepo(), "Valid Git repo reported as invalid");
    }

    @Test
    void testCleanWithParameter() throws Exception {
        workspace.commitEmpty("init");

        final String dirName1 = "dir1";
        final String fileName1 = dirName1 + File.separator + "fileName1";
        final String fileName2 = "fileName2";
        assertTrue(workspace.file(dirName1).mkdir(), "Did not create dir " + dirName1);
        workspace.touch(workspace.getGitFileDir(), fileName1, "");
        workspace.touch(workspace.getGitFileDir(), fileName2, "");

        final String dirName3 = "dir-with-submodule";
        File submodule = workspace.file(dirName3);
        assertTrue(submodule.mkdir(), "Did not create dir " + dirName3);
        WorkspaceWithRepo workspace1 = new WorkspaceWithRepo(submodule, gitImplName, TaskListener.NULL);
        workspace1.initializeWorkspace();
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
    void testClean() throws Exception {
        workspace.commitEmpty("init");

        /* String starts with a surrogate character, mathematical
         * double struck small t as the first character of the file
         * name. The last three characters of the file name are three
         * different forms of the a-with-ring character. Refer to
         * https://unicode.org/reports/tr15/#Detecting_Normalization_Forms
         * for the source of those example characters.
         */
        final String fileName = "\uD835\uDD65-\u5c4f\u5e55\u622a\u56fe-\u0041\u030a-\u00c5-\u212b-fileName.xml";
        workspace.touch(testGitDir, fileName, "content " + fileName);
        testGitClient.add(fileName);
        testGitClient.commit(fileName);

        /* JENKINS-27910 reported that certain cyrillic file names
         * failed to delete if the encoding was not UTF-8.
         */
        final String fileNameSwim =
                "\u00d0\u00bf\u00d0\u00bb\u00d0\u00b0\u00d0\u00b2\u00d0\u00b0\u00d0\u00bd\u00d0\u00b8\u00d0\u00b5-swim.png";
        workspace.touch(testGitDir, fileNameSwim, "content " + fileNameSwim);
        testGitClient.add(fileNameSwim);
        testGitClient.commit(fileNameSwim);

        final String fileNameFace = "\u00d0\u00bb\u00d0\u00b8\u00d1\u2020\u00d0\u00be-face.png";
        workspace.touch(testGitDir, fileNameFace, "content " + fileNameFace);
        testGitClient.add(fileNameFace);
        testGitClient.commit(fileNameFace);

        workspace.touch(testGitDir, ".gitignore", ".test");
        testGitClient.add(".gitignore");
        testGitClient.commit("ignore");

        final String dirName1 = "\u5c4f\u5e55\u622a\u56fe-dir-not-added";
        final String fileName1 = dirName1 + File.separator + "\u5c4f\u5e55\u622a\u56fe-fileName1-not-added.xml";
        final String fileName2 = ".test-\u00f8\u00e4\u00fc\u00f6-fileName2-not-added";
        assertTrue(workspace.file(dirName1).mkdir(), "Did not create dir " + dirName1);
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
        assertTrue(
                status.contains("working directory clean") || status.contains("working tree clean"),
                "unexpected status " + status);

        /* A few poorly placed tests of hudson.FilePath - testing JENKINS-22434 */
        FilePath fp = new FilePath(workspace.file(fileName));
        assertTrue(fp.exists(), fp + " missing");

        assertTrue(workspace.file(dirName1).mkdir(), "mkdir " + dirName1 + " failed");
        assertTrue(workspace.file(dirName1).isDirectory(), "dir " + dirName1 + " missing");
        FilePath dir1 = new FilePath(workspace.file(dirName1));
        workspace.touch(testGitDir, fileName1, "");
        assertTrue(workspace.file(fileName1).exists(), "Did not create file " + fileName1);

        assertTrue(dir1.exists(), dir1 + " missing");
        dir1.deleteRecursive(); /* Fails on Linux JDK 7 with LANG=C, ok with LANG=en_US.UTF-8 */
        /* Java reports "Malformed input or input contains unmappable characters" */
        assertFalse(workspace.file(fileName1).exists(), "Did not delete file " + fileName1);
        assertFalse(dir1.exists(), dir1 + " not deleted");

        workspace.touch(testGitDir, fileName2, "");
        FilePath fp2 = new FilePath(workspace.file(fileName2));

        assertTrue(fp2.exists(), fp2 + " missing");
        fp2.delete();
        assertFalse(fp2.exists(), fp2 + " not deleted");

        String dirContents = Arrays.toString((new File(testGitDir.getAbsolutePath())).listFiles());
        String finalStatus = workspace.launchCommand("git", "status");
        assertTrue(
                finalStatus.contains("working directory clean") || finalStatus.contains("working tree clean"),
                "unexpected final status " + finalStatus + " dir contents: " + dirContents);
    }

    @Test
    void testPushTags() throws Exception {
        /* Working Repo with commit */
        final String fileName1 = "file1";
        workspace.touch(testGitDir, fileName1, fileName1 + " content " + java.util.UUID.randomUUID());
        testGitClient.add(fileName1);
        testGitClient.commit("commit1");
        ObjectId commit1 = workspace.head();

        /* Clone working repo to bare repo */
        WorkspaceWithRepo bare = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        bare.initBareRepo(testGitClient, true);
        testGitClient.setRemoteUrl("origin", bare.getGitFileDir().getAbsolutePath());
        Set<Branch> remoteBranchesEmpty = testGitClient.getRemoteBranches();
        assertThat(remoteBranchesEmpty, is(empty()));
        //        testGitClient.push().ref(defaultBranchName).to(new URIish("origin")).execute();
        testGitClient.push("origin", defaultBranchName);
        ObjectId bareCommit1 =
                bare.getGitClient().getHeadRev(bare.getGitFileDir().getAbsolutePath(), defaultBranchName);
        assertEquals(commit1, bareCommit1, "bare != working");
        assertEquals(
                commit1,
                bare.getGitClient()
                        .getHeadRev(bare.getGitFileDir().getAbsolutePath(), "refs/heads/" + defaultBranchName));

        /* Add tag1 to working repo without pushing it to bare repo */
        workspace.tag("tag1");
        assertTrue(testGitClient.tagExists("tag1"), "tag1 wasn't created");
        assertEquals(commit1, testGitClient.revParse("tag1"), "tag1 points to wrong commit");
        testGitClient
                .push()
                .ref(defaultBranchName)
                .to(new URIish(bare.getGitFileDir().getAbsolutePath()))
                .tags(false)
                .execute();
        assertFalse(bare.launchCommand("git", "tag").contains("tag1"), "tag1 pushed unexpectedly");

        /* Push tag1 to bare repo */
        testGitClient
                .push()
                .ref(defaultBranchName)
                .to(new URIish(bare.getGitFileDir().getAbsolutePath()))
                .tags(true)
                .execute();
        assertTrue(bare.launchCommand("git", "tag").contains("tag1"), "tag1 not pushed");

        /* Create a new commit, move tag1 to that commit, attempt push */
        workspace.touch(testGitDir, fileName1, fileName1 + " content " + java.util.UUID.randomUUID());
        testGitClient.add(fileName1);
        testGitClient.commit("commit2");
        ObjectId commit2 = workspace.head();
        workspace.tag("tag1", true); /* Tag already exists, move from commit1 to commit2 */
        assertTrue(testGitClient.tagExists("tag1"), "tag1 wasn't created");
        assertEquals(commit2, testGitClient.revParse("tag1"), "tag1 points to wrong commit");
        if (testGitClient instanceof CliGitAPIImpl) {
            // Modern CLI git should throw exception pushing a change to existing tag
            Exception exception = assertThrows(GitException.class, () -> testGitClient
                    .push()
                    .ref(defaultBranchName)
                    .to(new URIish(bare.getGitFileDir().getAbsolutePath()))
                    .tags(true)
                    .execute());
            assertThat(exception.getMessage(), containsString("already exists"));
        } else {
            testGitClient
                    .push()
                    .ref(defaultBranchName)
                    .to(new URIish(bare.getGitFileDir().getAbsolutePath()))
                    .tags(true)
                    .execute();
        }

        if (testGitClient instanceof CliGitAPIImpl) {
            /* CliGit throws exception updating existing tag */
            Exception exception = assertThrows(GitException.class, () -> testGitClient
                    .push()
                    .ref(defaultBranchName)
                    .to(new URIish(bare.getGitFileDir().getAbsolutePath()))
                    .tags(true)
                    .force(false)
                    .execute());
            assertThat(exception.getMessage(), containsString("already exists"));
        } else {
            /* JGit does not throw exception updating existing tag - ugh */
            testGitClient
                    .push()
                    .ref(defaultBranchName)
                    .to(new URIish(bare.getGitFileDir().getAbsolutePath()))
                    .tags(true)
                    .force(false)
                    .execute();
        }

        testGitClient
                .push()
                .ref(defaultBranchName)
                .to(new URIish(bare.getGitFileDir().getAbsolutePath()))
                .tags(true)
                .force()
                .execute();

        /* Add tag to working repo without pushing it to the bare
         * repo, tests the default behavior when tags() is not added
         * to PushCommand.
         */
        workspace.tag("tag3");
        assertTrue(testGitClient.tagExists("tag3"), "tag3 wasn't created");
        testGitClient
                .push()
                .ref(defaultBranchName)
                .to(new URIish(bare.getGitFileDir().getAbsolutePath()))
                .execute();
        assertFalse(bare.launchCommand("git", "tag").contains("tag3"), "tag3 was pushed");

        /* Add another tag to working repo and push tags to the bare repo */
        final String fileName2 = "file2";
        workspace.touch(testGitDir, fileName2, fileName2 + " content " + java.util.UUID.randomUUID());
        testGitClient.add(fileName2);
        testGitClient.commit("commit2");
        workspace.tag("tag2");
        assertTrue(testGitClient.tagExists("tag2"), "tag2 wasn't created");
        testGitClient
                .push()
                .ref(defaultBranchName)
                .to(new URIish(bare.getGitFileDir().getAbsolutePath()))
                .tags(true)
                .execute();
        assertTrue(bare.launchCommand("git", "tag").contains("tag1"), "tag1 wasn't pushed");
        assertTrue(bare.launchCommand("git", "tag").contains("tag2"), "tag2 wasn't pushed");
        assertTrue(bare.launchCommand("git", "tag").contains("tag3"), "tag3 wasn't pushed");
    }

    @Issue("JENKINS-34309")
    @Test
    void testListBranches() throws Exception {
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
        assertBranchesExist(branches, defaultBranchName, "test", "another");
        assertEquals(3, branches.size());
        String output = workspace.launchCommand("git", "branch", "-v", "--no-abbrev");
        assertTrue(
                output.contains(testBranchCommitMessage),
                "git branch -v --no-abbrev missing test commit msg: '" + output + "'");
        assertTrue(
                output.contains(anotherBranchCommitMessage),
                "git branch -v --no-abbrev missing another commit msg: '" + output + "'");
        if (workspace.cgit().isAtLeastVersion(2, 13, 0, 0) && !workspace.cgit().isAtLeastVersion(2, 30, 0, 0)) {
            assertTrue(output.contains("\r"), "git branch -v --no-abbrev missing Ctrl-M: '" + output + "'");
            assertTrue(
                    output.contains(testBranchCommitMessage + "\r"),
                    "git branch -v --no-abbrev missing test commit msg Ctrl-M: '" + output + "'");
            assertTrue(
                    output.contains(anotherBranchCommitMessage + "\r"),
                    "git branch -v --no-abbrev missing another commit msg Ctrl-M: '" + output + "'");
        } else {
            assertFalse(output.contains("\r"), "git branch -v --no-abbrev contains Ctrl-M: '" + output + "'");
            assertFalse(
                    output.contains(testBranchCommitMessage + "\r"),
                    "git branch -v --no-abbrev contains test commit msg Ctrl-M: '" + output + "'");
            assertFalse(
                    output.contains(anotherBranchCommitMessage + "\r"),
                    "git branch -v --no-abbrev contains another commit msg Ctrl-M: '" + output + "'");
        }
    }

    @Test
    void testListRemoteBranches() throws Exception {
        WorkspaceWithRepo remote = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        remote.initializeWorkspace();
        remote.commitEmpty("init");
        remote.getGitClient().branch("test");
        remote.getGitClient().branch("another");

        workspace.launchCommand(
                "git", "remote", "add", "origin", remote.getGitFileDir().getAbsolutePath());
        workspace.launchCommand("git", "fetch", "origin");
        Set<Branch> branches = testGitClient.getRemoteBranches();
        assertBranchesExist(branches, "origin/" + defaultBranchName, "origin/test", "origin/another");
        int branchCount = 3;
        if (workspace.cgit().isAtLeastVersion(2, 48, 0, 0)) {
            /* Fetch from CLI git 2.48.0 and later return origin/HEAD
             *
             * https://github.blog/open-source/git/highlights-from-git-2-48/ says:
             *
             * With Git 2.48, if the remote has a default branch but refs/remotes/origin/HEAD
             * is missing locally, then a fetch will update it.
             *
             * This test was unintentionally testing the behavior of CLI git before
             * 2.48.0.  Other tests in GitClientFetchTest were testing that origin/HEAD
             * was reported as a branch.
             */
            assertBranchesExist(branches, "origin/HEAD");
            branchCount = 4;
        }
        assertEquals(
                branchCount,
                branches.size(),
                "Wrong branch count, found " + branches.size() + " branches: " + branches);
    }

    @Test
    void testRemoteListTagsWithFilter() throws Exception {
        WorkspaceWithRepo remote = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        remote.initializeWorkspace();
        remote.commitEmpty("init");
        remote.tag("test");
        remote.tag("another_test");
        remote.tag("yet_another");

        workspace.launchCommand(
                "git", "remote", "add", "origin", remote.getGitFileDir().getAbsolutePath());
        workspace.launchCommand("git", "fetch", "origin");
        Set<String> local_tags = testGitClient.getTagNames("*test");
        Set<String> tags = testGitClient.getRemoteTagNames("*test");
        assertTrue(tags.contains("test"), "expected tag test not listed");
        assertTrue(tags.contains("another_test"), "expected tag another_test not listed");
        assertFalse(tags.contains("yet_another"), "unexpected yet_another tag listed");
    }

    @Test
    void testRemoteListTagsWithoutFilter() throws Exception {
        WorkspaceWithRepo remote = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        remote.initializeWorkspace();
        remote.commitEmpty("init");
        remote.tag("test");
        remote.tag("another_test");
        remote.tag("yet_another");

        workspace.launchCommand(
                "git", "remote", "add", "origin", remote.getGitFileDir().getAbsolutePath());
        workspace.launchCommand("git", "fetch", "origin");
        Set<String> allTags = workspace.getGitClient().getRemoteTagNames(null);
        assertTrue(allTags.contains("test"), "tag 'test' not listed");
        assertTrue(allTags.contains("another_test"), "tag 'another_test' not listed");
        assertTrue(allTags.contains("yet_another"), "tag 'yet_another' not listed");
    }

    @Issue("JENKINS-23299")
    @Test
    void testCreateTag() throws Exception {
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
            assertEquals(commitId, tagHeadIdByShortRef, "annotated tag does not match commit SHA1");
        } else {
            assertNull(tagHeadIdByShortRef, "annotated tag unexpectedly not null");
        }
        assertEquals(commitId, testGitClient.revParse(shortTagRef), "annotated tag does not match commit SHA1");

        /*
         * Spec: "refs/tags/test" (more specific tag syntax)
         * CliGit and JGit fully support this syntax.
         */
        final String longTagRef = "refs/tags/test";
        assertEquals(
                commitId, testGitClient.getHeadRev(gitDir, longTagRef), "annotated tag does not match commit SHA1");
        assertEquals(commitId, testGitClient.revParse(longTagRef), "annotated tag does not match commit SHA1");

        final String tagNames = workspace.launchCommand("git", "tag", "-l").trim();
        assertEquals("test", tagNames, "tag not created");

        final String tagNamesWithMessages = workspace.launchCommand("git", "tag", "-l", "-n1");
        assertTrue(
                tagNamesWithMessages.contains("this is an annotated tag"),
                "unexpected tag message : " + tagNamesWithMessages);

        ObjectId invalidTagId = testGitClient.getHeadRev(gitDir, "not-a-valid-tag");
        assertNull(invalidTagId, "did not expect reference for invalid tag but got : " + invalidTagId);
    }

    @Test
    void testRevparseSHA1HEADorTag() throws Exception {
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
    void testRevparseThrowsExpectedException() throws Exception {
        workspace.commitEmpty("init");
        final GitException ex = assertThrows(GitException.class, () -> testGitClient.revParse("unknown-to-rev-parse"));
        assertThat(ex.getMessage(), containsString("unknown-to-rev-parse"));
    }

    @Test
    void testPush() throws Exception {
        workspace.commitEmpty("init");
        workspace.touch(testGitDir, "file1", "");
        workspace.tag("file1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        ObjectId sha1 = workspace.head();

        WorkspaceWithRepo remote = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        remote.initBareRepo(remote.getGitClient(), true);
        workspace.launchCommand(
                "git", "remote", "add", "origin", remote.getGitFileDir().getAbsolutePath());

        testGitClient.push("origin", defaultBranchName);
        String remoteSha1 =
                remote.launchCommand("git", "rev-parse", defaultBranchName).substring(0, 40);
        assertEquals(sha1.name(), remoteSha1);
    }

    @Test
    void testNotesAddFirstNote() throws Exception {
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
    void testNotesAppendFirstNote() throws Exception {
        workspace.initializeWorkspace();
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
    void testPrune() throws Exception {
        // pretend that 'teamWorkspace' is a team repository and workspace1 and workspace2 are team members
        WorkspaceWithRepo teamWorkspace = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        teamWorkspace.initBareRepo(teamWorkspace.getGitClient(), true);

        WorkspaceWithRepo workspace1 = new WorkspaceWithRepo(thirdRepo.getRoot(), gitImplName, TaskListener.NULL);
        workspace1.initializeWorkspace();
        WorkspaceWithRepo workspace2 = workspace;

        workspace1.commitEmpty("c");
        workspace1.launchCommand(
                "git", "remote", "add", "origin", teamWorkspace.getGitFileDir().getAbsolutePath());

        workspace1.launchCommand("git", "push", "origin", defaultBranchName + ":b1");
        workspace1.launchCommand("git", "push", "origin", defaultBranchName + ":b2");
        workspace1.launchCommand("git", "push", "origin", defaultBranchName);

        workspace2.launchCommand(
                "git", "remote", "add", "origin", teamWorkspace.getGitFileDir().getAbsolutePath());
        workspace2.launchCommand("git", "fetch", "origin");

        // at this point both ws1&ws2 have several remote tracking branches

        workspace1.launchCommand("git", "push", "origin", ":b1");
        workspace1.launchCommand("git", "push", "origin", defaultBranchName + ":b3");

        workspace2.getGitClient().prune(new RemoteConfig(new Config(), "origin"));

        assertFalse(workspace2.exists(".git/refs/remotes/origin/b1"));
        assertTrue(workspace2.exists(".git/refs/remotes/origin/b2"));
        assertFalse(workspace2.exists(".git/refs/remotes/origin/b3"));
    }

    @Test
    void testRevListAll() throws Exception {
        workspace.launchCommand("git", "pull", workspace.localMirror());

        final StringBuilder out = new StringBuilder();
        for (ObjectId id : testGitClient.revListAll()) {
            out.append(id.name()).append('\n');
        }
        final String all = workspace.launchCommand("git", "rev-list", "--all");
        assertEquals(all, out.toString());
    }

    @Test
    void testRevList_() throws Exception {
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
    void testRevListFirstParent() throws Exception {
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

            final String all = workspace.launchCommand("git", "rev-list", "--first-parent", b.getName());
            assertEquals(all, out.toString());
        }
    }

    @Test
    void testRevList() throws Exception {
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
    void testMergeStrategy() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file", "content1");
        testGitClient.add("file");
        testGitClient.commit("commit1");
        testGitClient.checkout().ref(defaultBranchName).execute();
        testGitClient.branch("branch2");
        testGitClient.checkout().ref("branch2").execute();
        workspace.touch(testGitDir, "file", "content2");
        File f = workspace.file("file");
        testGitClient.add("file");
        testGitClient.commit("commit2");
        testGitClient
                .merge()
                .setStrategy(MergeCommand.Strategy.OURS)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1"))
                .execute();
        assertEquals(
                "content2", Files.readString(f.toPath(), StandardCharsets.UTF_8), "merge didn't selected OURS content");
    }

    @Test
    void testMergeStrategyCorrectFail() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file", "content1");
        testGitClient.add("file");
        testGitClient.commit("commit1");
        testGitClient.checkout().ref(defaultBranchName).execute();
        testGitClient.branch("branch2");
        testGitClient.checkout().ref("branch2").execute();
        workspace.touch(testGitDir, "file", "content2");
        testGitClient.add("file");
        testGitClient.commit("commit2");

        assertThrows(GitException.class, () -> testGitClient
                .merge()
                .setStrategy(MergeCommand.Strategy.RESOLVE)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1"))
                .execute());
    }

    @Issue("JENKINS-12402")
    @Test
    void testMergeFastForwardModeFF() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        final ObjectId branch1 = workspace.head();

        testGitClient.checkout().ref(defaultBranchName).execute();
        testGitClient.branch("branch2");
        testGitClient.checkout().ref("branch2").execute();
        workspace.touch(testGitDir, "file2", "content2");
        testGitClient.add("file2");
        testGitClient.commit("commit2");
        final ObjectId branch2 = workspace.head();

        testGitClient.checkout().ref(defaultBranchName).execute();

        // The first merge is a fast-forward, default branch moves to branch1
        testGitClient
                .merge()
                .setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1"))
                .execute();
        assertEquals(
                workspace.head(), branch1, "Fast-forward merge failed. default branch and branch1 should be the same.");

        // The second merge calls for fast-forward (FF), but a merge commit will result
        // This tests that calling for FF gracefully falls back to a commit merge
        // default branch moves to a new commit ahead of branch1 and branch2
        testGitClient
                .merge()
                .setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch2"))
                .execute();
        // The merge commit (head) should have branch2 and branch1 as parents
        List<ObjectId> revList = testGitClient.revList("HEAD^1");
        assertEquals(
                revList.get(0).name(),
                branch1.name(),
                "Merge commit failed. branch1 should be a parent of HEAD but it isn't.");
        revList = testGitClient.revList("HEAD^2");
        assertEquals(
                revList.get(0).name(),
                branch2.name(),
                "Merge commit failed. branch2 should be a parent of HEAD but it isn't.");
    }

    @Test
    void testMergeFastForwardModeFFOnly() throws Exception {
        workspace.commitEmpty("init");
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        final ObjectId branch1 = workspace.head();

        testGitClient.checkout().ref(defaultBranchName).execute();
        testGitClient.branch("branch2");
        testGitClient.checkout().ref("branch2").execute();
        workspace.touch(testGitDir, "file2", "content2");
        testGitClient.add("file2");
        testGitClient.commit("commit2");
        final ObjectId branch2 = workspace.head();

        testGitClient.checkout().ref(defaultBranchName).execute();

        // The first merge is a fast-forward only (FF_ONLY), default branch moves to branch1
        testGitClient
                .merge()
                .setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF_ONLY)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1"))
                .execute();
        assertEquals(
                workspace.head(),
                branch1,
                "Fast-forward merge failed. Default branch and branch1 should be the same but aren't.");

        // The second merge calls for fast-forward only (FF_ONLY), but a merge commit is required, hence it is expected
        // to fail
        assertThrows(GitException.class, () -> testGitClient
                .merge()
                .setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF_ONLY)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch2"))
                .execute());
        assertEquals(
                workspace.head(),
                branch1,
                "Fast-forward merge abort failed. Default branch and branch1 should still be the same as the merge was aborted.");
    }

    @Test
    void testMergeFastForwardModeNoFF() throws Exception {
        workspace.commitEmpty("init");
        final ObjectId base = workspace.head();
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        final ObjectId branch1 = workspace.head();

        testGitClient.checkout().ref(defaultBranchName).execute();
        testGitClient.branch("branch2");
        testGitClient.checkout().ref("branch2").execute();
        workspace.touch(testGitDir, "file2", "content2");
        testGitClient.add("file2");
        testGitClient.commit("commit2");
        final ObjectId branch2 = workspace.head();

        testGitClient.checkout().ref(defaultBranchName).execute();

        // The first merge is normally a fast-forward, but we're calling for a merge commit which is expected to work
        testGitClient
                .merge()
                .setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1"))
                .execute();

        // The first merge will have base and branch1 as parents
        List<ObjectId> revList = testGitClient.revList("HEAD^1");
        assertEquals(
                revList.get(0).name(),
                base.name(),
                "Merge commit failed. base should be a parent of HEAD but it isn't.");
        revList = testGitClient.revList("HEAD^2");
        assertEquals(
                revList.get(0).name(),
                branch1.name(),
                "Merge commit failed. branch1 should be a parent of HEAD but it isn't.");

        final ObjectId base2 = workspace.head();

        // Calling for NO_FF when required is expected to work
        testGitClient
                .merge()
                .setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch2"))
                .execute();

        // The second merge will have base2 and branch2 as parents
        revList = testGitClient.revList("HEAD^1");
        assertEquals(
                revList.get(0).name(),
                base2.name(),
                "Merge commit failed. base2 should be a parent of HEAD but it isn't.");
        revList = testGitClient.revList("HEAD^2");
        assertEquals(
                revList.get(0).name(),
                branch2.name(),
                "Merge commit failed. branch2 should be a parent of HEAD but it isn't.");
    }

    @Test
    void testMergeSquash() throws Exception {
        workspace.commitEmpty("init");

        // First commit to branch1
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");

        // Second commit to branch2
        workspace.touch(testGitDir, "file2", "content2");
        testGitClient.add("file2");
        testGitClient.commit("commit2");

        // Merge branch1 with default branch, squashing both commits
        testGitClient.checkout().ref(defaultBranchName).execute();
        testGitClient
                .merge()
                .setSquash(true)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1"))
                .execute();

        // Compare commit counts of before and after committing the merge, should be  one due to the squashing of
        // commits.
        final int commitCountBefore = testGitClient.revList("HEAD").size();
        testGitClient.commit("commitMerge");
        final int commitCountAfter = testGitClient.revList("HEAD").size();

        assertEquals(
                1, commitCountAfter - commitCountBefore, "Squash merge failed. Should have merged only one commit.");
    }

    @Test
    void testMergeNoSquash() throws Exception {
        workspace.commitEmpty("init");

        // First commit to branch1
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");

        // Second commit to branch2
        workspace.touch(testGitDir, "file2", "content2");
        testGitClient.add("file2");
        testGitClient.commit("commit2");

        // Merge branch1 with default branch, without squashing commits.
        // Compare commit counts of before and after committing the merge, should be two due to the no squashing of
        // commits.
        testGitClient.checkout().ref(defaultBranchName).execute();
        final int commitCountBefore = testGitClient.revList("HEAD").size();
        testGitClient
                .merge()
                .setSquash(false)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1"))
                .execute();
        final int commitCountAfter = testGitClient.revList("HEAD").size();

        assertEquals(
                2, commitCountAfter - commitCountBefore, "Squashless merge failed. Should have merged two commits.");
    }

    @Test
    void testMergeNoCommit() throws Exception {
        workspace.commitEmpty("init");

        // Create branch1 and commit a file
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");

        // Merge branch1 with default branch, without committing the merge.
        // Compare commit counts of before and after the merge, should be zero due to the lack of autocommit.
        testGitClient.checkout().ref(defaultBranchName).execute();
        final int commitCountBefore = testGitClient.revList("HEAD").size();
        testGitClient
                .merge()
                .setCommit(false)
                .setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1"))
                .execute();
        final int commitCountAfter = testGitClient.revList("HEAD").size();

        assertEquals(
                commitCountBefore, commitCountAfter, "No Commit merge failed. Shouldn't have committed any changes.");
    }

    @Test
    void testMergeCommit() throws Exception {
        workspace.commitEmpty("init");

        // Create branch1 and commit a file
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");

        // Merge branch1 with default branch, without committing the merge.
        // Compare commit counts of before and after the merge, should be two due to the commit of the file and the
        // commit of the merge.
        testGitClient.checkout().ref(defaultBranchName).execute();
        final int commitCountBefore = testGitClient.revList("HEAD").size();
        testGitClient
                .merge()
                .setCommit(true)
                .setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1"))
                .execute();
        final int commitCountAfter = testGitClient.revList("HEAD").size();

        assertEquals(2, commitCountAfter - commitCountBefore, "Commit merge failed. Should have committed the merge.");
    }

    @Test
    void testMergeWithMessage() throws Exception {
        workspace.commitEmpty("init");

        // Create branch1 and commit a file
        testGitClient.branch("branch1");
        testGitClient.checkout().ref("branch1").execute();
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1");

        // Merge branch1 into default branch
        testGitClient.checkout().ref(defaultBranchName).execute();
        final String mergeMessage = "Merge message to be tested.";
        testGitClient
                .merge()
                .setMessage(mergeMessage)
                .setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF)
                .setRevisionToMerge(testGitClient.getHeadRev(testGitDir.getAbsolutePath(), "branch1"))
                .execute();
        // Obtain last commit message
        String resultMessage = "";
        final List<String> content = testGitClient.showRevision(workspace.head());
        if ("gpgsig -----BEGIN PGP SIGNATURE-----".equals(content.get(6).trim())) {
            // Commit is signed so the commit message is after the signature
            for (int i = 6; i < content.size(); i++) {
                if (content.get(i).trim().equals("-----END PGP SIGNATURE-----")) {
                    resultMessage = content.get(i + 2).trim();
                    break;
                }
            }
        } else {
            resultMessage = content.get(7).trim();
        }

        assertEquals(mergeMessage, resultMessage, "Custom message merge failed. Should have set custom merge message.");
    }

    @Test
    void testRebasePassesWithoutConflict() throws Exception {
        workspace.commitEmpty("init");

        // First commit to default branch
        workspace.touch(testGitDir, "default_branch_file", "default_branch1");
        testGitClient.add("default_branch_file");
        testGitClient.commit("commit-default_branch1");

        // Create a feature branch and make a commit
        testGitClient.branch("feature1");
        testGitClient.checkout().ref("feature1").execute();
        workspace.touch(testGitDir, "feature_file", "feature1");
        testGitClient.add("feature_file");
        testGitClient.commit("commit-feature1");

        // Second commit to default branch
        testGitClient.checkout().ref(defaultBranchName).execute();
        workspace.touch(testGitDir, "default_branch_file", "default_branch2");
        testGitClient.add("default_branch_file");
        testGitClient.commit("commit-default_branch2");

        // Rebase feature commit onto default branch
        testGitClient.checkout().ref("feature1").execute();
        testGitClient.rebase().setUpstream(defaultBranchName).execute();

        assertThat(
                "Should've rebased feature1 onto default branch",
                testGitClient.revList("feature1").contains(testGitClient.revParse(defaultBranchName)));
        assertEquals(
                testGitClient.revParse("HEAD").name(),
                testGitClient.revParse("feature1").name(),
                "HEAD should be on the rebased branch");
        assertThat(
                "Rebased file should be present in the worktree",
                testGitClient.getWorkTree().child("feature_file").exists());
    }

    @Test
    void testRebaseFailsWithConflict() throws Exception {
        workspace.commitEmpty("init");

        // First commit to default branch
        workspace.touch(testGitDir, "file", "default_branch1");
        testGitClient.add("file");
        testGitClient.commit("commit-default-branch1");

        // Create a feature branch and make a commit
        testGitClient.branch("feature1");
        testGitClient.checkout().ref("feature1").execute();
        workspace.touch(testGitDir, "file", "feature1");
        testGitClient.add("file");
        testGitClient.commit("commit-feature1");

        // Second commit to default branch
        testGitClient.checkout().ref(defaultBranchName).execute();
        workspace.touch(testGitDir, "file", "default-branch2");
        testGitClient.add("file");
        testGitClient.commit("commit-default-branch2");

        // Rebase feature commit onto default branch
        testGitClient.checkout().ref("feature1").execute();
        Exception exception = assertThrows(
                GitException.class,
                () -> testGitClient.rebase().setUpstream(defaultBranchName).execute());
        assertThat(
                exception.getMessage(),
                anyOf(
                        containsString("Failed to rebase " + defaultBranchName),
                        containsString("Could not rebase " + defaultBranchName)));
        assertEquals(
                testGitClient.revParse("HEAD").name(),
                testGitClient.revParse("feature1").name(),
                "HEAD not reset to the feature branch.");
        Status status = new org.eclipse.jgit.api.Git(new FileRepository(new File(testGitDir, ".git")))
                .status()
                .call();
        assertTrue(status.isClean(), "Workspace is not clean");
        assertFalse(status.hasUncommittedChanges(), "Workspace has uncommitted changes");
        assertTrue(status.getConflicting().isEmpty(), "Workspace has conflicting changes");
        assertTrue(status.getMissing().isEmpty(), "Workspace has missing changes");
        assertTrue(status.getModified().isEmpty(), "Workspace has modified files");
        assertTrue(status.getRemoved().isEmpty(), "Workspace has removed files");
        assertTrue(status.getUntracked().isEmpty(), "Workspace has untracked files");
    }

    /**
     * A rev-parse warning message should not break revision parsing.
     */
    @Issue("JENKINS-11177")
    @Test
    void testJenkins11177() throws Exception {
        workspace.commitEmpty("init");
        final ObjectId base = workspace.head();
        final ObjectId defaultBranchObjectId = testGitClient.revParse(defaultBranchName);
        assertEquals(base, defaultBranchObjectId);

        /* Make reference to default branch ambiguous, verify it is reported ambiguous by rev-parse */
        workspace.tag(defaultBranchName);
        final String revParse = workspace.launchCommand("git", "rev-parse", defaultBranchName);
        assertTrue(revParse.contains("ambiguous"), "'" + revParse + "' does not contain 'ambiguous'");
        final ObjectId ambiguousTag = testGitClient.revParse("refs/tags/" + defaultBranchName);
        assertEquals(workspace.head(), ambiguousTag, "ambiguousTag != head");

        /* Get reference to ambiguous branch name */
        final ObjectId ambiguous = testGitClient.revParse(defaultBranchName);
        assertEquals(ambiguous.toString(), defaultBranchObjectId.toString(), "ambiguous != default branch");

        /* Exploring JENKINS-20991 ambiguous revision breaks checkout */
        workspace.touch(testGitDir, "file-default-branch", "content-default-branch");
        testGitClient.add("file-default-branch");
        testGitClient.commit("commit1-default-branch");
        final ObjectId defaultBranchTip = workspace.head();

        workspace.launchCommand("git", "branch", "branch1", defaultBranchTip.name());
        workspace.launchCommand("git", "checkout", "branch1");
        workspace.touch(testGitDir, "file1", "content1");
        testGitClient.add("file1");
        testGitClient.commit("commit1-branch1");
        final ObjectId branch1 = workspace.head();

        /* JGit checks out the tag, while CliGit checks out
         * the branch.  It is risky that there are different
         * behaviors between the two implementations, but when a
         * reference is ambiguous, it is safe to assume that
         * resolution of the ambiguous reference is an implementation
         * specific detail. */
        testGitClient.checkout().ref(defaultBranchName).execute();
        final String messageDetails = ", head=" + workspace.head().name() + ", defaultBranchTip="
                + defaultBranchTip.name() + ", ambiguousTag="
                + ambiguousTag.name() + ", branch1="
                + branch1.name();
        if (testGitClient instanceof CliGitAPIImpl) {
            assertEquals(defaultBranchTip, workspace.head(), "head != default branch" + messageDetails);
        } else {
            assertEquals(ambiguousTag, workspace.head(), "head != ambiguous tag" + messageDetails);
        }
    }

    @Test
    void testCloneNoCheckout() throws Exception {
        // Create a repo for cloning purpose
        WorkspaceWithRepo repoToClone = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        repoToClone.initializeWorkspace();
        repoToClone.commitEmpty("init");
        repoToClone.touch(repoToClone.getGitFileDir(), "file1", "");
        repoToClone.getGitClient().add("file1");
        repoToClone.getGitClient().commit("commit1");

        // Clone it with no checkout
        testGitClient
                .clone_()
                .url(repoToClone.getGitFileDir().getAbsolutePath())
                .repositoryName("origin")
                .noCheckout()
                .execute();
        assertFalse(workspace.exists("file1"));
    }

    @Issue("JENKINS-25444")
    @Test
    void testFetchDeleteCleans() throws Exception {
        workspace.touch(testGitDir, "file1", "old");
        testGitClient.add("file1");
        testGitClient.commit("commit1");
        workspace.touch(testGitDir, "file1", "new");
        checkoutTimeout = 1 + random.nextInt(60 * 24);
        testGitClient
                .checkout()
                .branch("other")
                .ref(Constants.HEAD)
                .timeout(checkoutTimeout)
                .deleteBranchIfExist(true)
                .execute();

        Status status = new org.eclipse.jgit.api.Git(new FileRepository(new File(testGitDir, ".git")))
                .status()
                .call();

        assertTrue(status.isClean(), "Workspace must be clean");
    }

    @Test
    void testRevListTag() throws Exception {
        workspace.commitEmpty("c1");
        FileRepository repo = new FileRepository(new File(testGitDir, ".git"));
        Ref commitRefC1 = repo.exactRef("HEAD");
        workspace.tag("t1");
        Ref tagRefT1 = repo.findRef("t1");
        Ref head = repo.exactRef("HEAD");
        assertEquals(head.getObjectId(), tagRefT1.getObjectId(), "head != t1");
        workspace.commitEmpty("c2");
        Ref commitRef2 = repo.exactRef("HEAD");
        List<ObjectId> revList = testGitClient.revList("t1");
        assertTrue(revList.contains(commitRefC1.getObjectId()), "c1 not in revList");
        assertEquals(1, revList.size(), "wring list size: " + revList);
    }

    @Test
    void testRevListLocalBranch() throws Exception {
        workspace.commitEmpty("c1");
        workspace.tag("t1");
        workspace.commitEmpty("c2");
        List<ObjectId> revList = testGitClient.revList(defaultBranchName);
        assertEquals(2, revList.size(), "Wrong list size: " + revList);
    }

    @Issue("JENKINS-20153")
    @Test
    void testCheckOutBranchNull() throws Exception {
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
    void testLocalCheckoutConflict() throws Exception {
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

        assertEquals("old", Files.readString(workspace.file("foo").toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void testNoSubmodules() throws Exception {
        workspace.touch(testGitDir, "committed-file", "committed-file content " + java.util.UUID.randomUUID());
        testGitClient.add("committed-file");
        testGitClient.commit("commit1");
        IGitAPI igit = (IGitAPI) testGitClient;
        igit.submoduleClean(false);
        igit.submoduleClean(true);
        igit.submoduleUpdate().recursive(false).execute();
        igit.submoduleUpdate().recursive(true).execute();
        igit.submoduleSync();
        assertTrue(workspace.file("committed-file").exists(), "committed-file missing at commit1");
    }

    @Test
    void testHasSubmodules() throws Exception {
        workspace.launchCommand("git", "fetch", workspace.localMirror(), "tests/getSubmodules:t");
        testGitClient.checkout().ref("t").execute();
        assertTrue(testGitClient.hasGitModules());

        workspace.launchCommand("git", "fetch", workspace.localMirror(), DEFAULT_MIRROR_BRANCH_NAME + ":t2");
        testGitClient.checkout().ref("t2").execute();
        assertFalse(testGitClient.hasGitModules());
        IGitAPI igit = (IGitAPI) testGitClient;
        if (igit instanceof JGitAPIImpl) {
            assertThrows(UnsupportedOperationException.class, () -> igit.fixSubmoduleUrls("origin", listener));
        } else if (igit instanceof CliGitAPIImpl) {
            final GitException ex = assertThrows(GitException.class, () -> igit.fixSubmoduleUrls("origin", listener));
            assertThat(ex.getMessage(), containsString("Could not determine remote"));
            assertThat(ex.getMessage(), containsString("origin"));
        }
    }

    /**
     * Checks that the ChangelogCommand abort() API does not write
     * output to the destination.  Does not check that the abort() API
     * releases resources.
     */
    @Test
    void testChangeLogAbort() throws Exception {
        final String logMessage = "changelog-abort-test-commit";
        workspace.touch(
                testGitDir, "file-changelog-abort", "changelog abort file contents " + java.util.UUID.randomUUID());
        testGitClient.add("file-changelog-abort");
        testGitClient.commit(logMessage);
        String sha1 = testGitClient.revParse("HEAD").name();
        ChangelogCommand changelogCommand = testGitClient.changelog();
        StringWriter writer = new StringWriter();
        changelogCommand.to(writer);

        /* Abort the changelog, confirm no content was written */
        changelogCommand.abort();
        assertEquals("", writer.toString(), "aborted changelog wrote data");

        /* Execute the changelog, confirm expected content was written */
        changelogCommand = testGitClient.changelog();
        changelogCommand.to(writer);
        changelogCommand.execute();
        assertTrue(writer.toString().contains(logMessage), "No log message in " + writer);
        assertTrue(writer.toString().contains(sha1), "No SHA1 in " + writer);
    }

    @Test
    public void testChangelogSkipsMerges() throws Exception {
        int counter = 0;
        workspace.touch(testGitDir, "file-skips-merges-" + counter, "changelog skips merges " + counter);
        testGitClient.add("file-skips-merges-" + counter);
        testGitClient.commit("skips-merges-" + counter++);
        String rootCommit = testGitClient.revParse("HEAD").name();

        // Create branches a, b, and common that will merge a and b
        testGitClient.branch("branch-A"); // Create branch-A without switching to it
        testGitClient.branch("branch-B"); // Create branch-B without switching to it
        testGitClient.branch("common"); // common branch that will merge branch-A and branch-B

        testGitClient.checkoutBranch("branch-A", rootCommit); // Switch to branch-A
        workspace.touch(testGitDir, "file-branch-A", "branch-A file " + counter++);
        testGitClient.add("file-branch-A");
        testGitClient.commit("file-branch-A on branch-A");
        String branchACommit = testGitClient.revParse("HEAD").name();

        testGitClient.checkoutBranch("branch-B", rootCommit); // Switch to branch-B
        workspace.touch(testGitDir, "file-branch-B", "branch-B file " + counter++);
        testGitClient.add("file-branch-B");
        testGitClient.commit("file-branch-B on branch-B");
        String branchBCommit = testGitClient.revParse("HEAD").name();

        String mergeMessage = "Merged branch-B into common";
        testGitClient.checkoutBranch("common", rootCommit); // Switch to common branch
        testGitClient
                .merge()
                .setRevisionToMerge(ObjectId.fromString(branchACommit))
                .execute();
        testGitClient
                .merge()
                .setRevisionToMerge(ObjectId.fromString(branchBCommit))
                .setMessage(mergeMessage)
                .execute();
        String mergedCommit = testGitClient.revParse("HEAD").name();

        workspace.touch(testGitDir, "file-skips-merges-" + counter, "changelog skips merges " + counter);
        testGitClient.add("file-skips-merges-" + counter);
        testGitClient.commit("skips-merges-" + counter++);
        String finalCommit = testGitClient.revParse("HEAD").name();

        // Calculate the changelog
        StringWriter writer = new StringWriter();
        testGitClient.changelog().to(writer).execute();
        String changelog = writer.toString();

        // Confirm the changelog includes expected commits
        assertThat(changelog, containsString("commit " + branchACommit));
        assertThat(changelog, containsString("commit " + branchBCommit));
        assertThat(changelog, containsString("commit " + finalCommit));

        // Confirm the changelog does not include the merge commit
        assertThat(changelog, not(containsString("commit " + mergedCommit)));
        assertThat(changelog, not(containsString(mergeMessage)));
    }

    /**
     * inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue
     */
    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
