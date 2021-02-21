package org.jenkinsci.plugins.gitclient;

import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Git API Tests, eventual replacement for GitAPITestCase,
 * Implemented in JUnit 4.
 */

@RunWith(Parameterized.class)
public class GitAPITest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private File repoRoot = null;

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

    private void assertBranchesExist(Set<Branch> branches, String ... names) throws InterruptedException {
        Collection<String> branchNames = getBranchNames(branches);
        for (String name : names) {
            assertThat(branchNames, hasItem(name));
        }
    }

    @Before
    public void setUpRepositories() throws Exception {
        File repoRootTemp = tempFolder.newFolder();

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

        workspace = new WorkspaceWithRepo(repoRootTemp, gitImplName, listener);

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
            workspace.getGitClient().tag("non-empty-comment", "empty-tag-comment-fails-on-windows");
        } else {
            workspace.getGitClient().tag("empty-comment", "");
        }
    }

    @Test
    public void testCreateBranch() throws Exception {
        workspace.commitEmpty("init");
        workspace.getGitClient().branch("test");
        String branches = workspace.launchCommand("git", "branch", "-l");
        assertTrue("master branch not listed", branches.contains("master"));
        assertTrue("test branch not listed", branches.contains("test"));
    }

    @Test
    public void testDeleteBranch() throws Exception {
        workspace.commitEmpty("init");
        workspace.getGitClient().branch("test");
        workspace.getGitClient().deleteBranch("test");
        String branches = workspace.launchCommand("git", "branch", "-l");
        assertFalse("deleted test branch still present", branches.contains("test"));
        try {
            workspace.getGitClient().deleteBranch("test");
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
        workspace.getGitClient().deleteTag("test");
        String tags = workspace.launchCommand("git", "tag");
        assertFalse("deleted test tag still present", tags.contains("test"));
        assertTrue("expected tag not listed", tags.contains("another"));
        try {
            workspace.getGitClient().deleteTag("test");
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
        Set<String> tags = workspace.getGitClient().getTagNames("*test");
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
        Set<String> allTags = workspace.getGitClient().getTagNames(null);
        assertTrue("tag 'test' not listed", allTags.contains("test"));
        assertTrue("tag 'another_test' not listed", allTags.contains("another_test"));
        assertTrue("tag 'yet_another' not listed", allTags.contains("yet_another"));
    }

    @Issue("JENKINS-37794")
    @Test
    public void testGetTagNamesSupportsSlashesInTagNames() throws Exception {
        workspace.commitEmpty("init-getTagNames-supports-slashes");
        workspace.getGitClient().tag("no-slash", "Tag without a /");
        Set<String> tags = workspace.getGitClient().getTagNames(null);
        assertThat(tags, hasItem("no-slash"));
        assertThat(tags, not(hasItem("slashed/sample")));
        assertThat(tags, not(hasItem("slashed/sample-with-short-comment")));

        workspace.getGitClient().tag("slashed/sample", "Tag slashed/sample includes a /");
        workspace.getGitClient().tag("slashed/sample-with-short-comment", "short comment");

        for (String matchPattern : Arrays.asList("n*", "no-*", "*-slash", "*/sl*sa*", "*/sl*/sa*")) {
            Set<String> latestTags = workspace.getGitClient().getTagNames(matchPattern);
            assertThat(tags, hasItem("no-slash"));
            assertThat(latestTags, not(hasItem("slashed/sample")));
            assertThat(latestTags, not(hasItem("slashed/sample-with-short-comment")));
        }

        for (String matchPattern : Arrays.asList("s*", "slashed*", "sl*sa*", "slashed/*", "sl*/sa*", "slashed/sa*")) {
            Set<String> latestTags = workspace.getGitClient().getTagNames(matchPattern);
            assertThat(latestTags, hasItem("slashed/sample"));
            assertThat(latestTags, hasItem("slashed/sample-with-short-comment"));
        }
    }

    @Test
    public void testListBranchesContainingRef() throws Exception {
        workspace.commitEmpty("init");
        workspace.getGitClient().branch("test");
        workspace.getGitClient().branch("another");
        Set<Branch> branches = workspace.getGitClient().getBranches();
        assertBranchesExist(branches, "master", "test", "another");
        assertEquals(3, branches.size());
    }

    @Test
    public void testListTagsStarFilter() throws Exception {
        workspace.commitEmpty("init");
        workspace.tag("test");
        workspace.tag("another_test");
        workspace.tag("yet_another");
        Set<String> allTags = workspace.getGitClient().getTagNames("*");
        assertTrue("tag 'test' not listed", allTags.contains("test"));
        assertTrue("tag 'another_test' not listed", allTags.contains("another_test"));
        assertTrue("tag 'yet_another' not listed", allTags.contains("yet_another"));
    }

    @Test
    public void testTagExists() throws Exception {
        workspace.commitEmpty("init");
        workspace.tag("test");
        assertTrue(workspace.getGitClient().tagExists("test"));
        assertFalse(workspace.getGitClient().tagExists("unknown"));
    }

    @Test
    public void testGetTagMessage() throws Exception {
        workspace.commitEmpty("init");
        workspace.launchCommand("git", "tag", "test", "-m", "this-is-a-test");
        assertEquals("this-is-a-test", workspace.getGitClient().getTagMessage("test"));
    }

    @Test
    public void testGetTagMessageMultiLine() throws Exception {
        workspace.commitEmpty("init");
        workspace.launchCommand("git", "tag", "test", "-m", "test 123!\n* multi-line tag message\n padded ");

        // Leading four spaces from each line should be stripped,
        // but not the explicit single space before "padded",
        // and the final errant space at the end should be trimmed
        assertEquals("test 123!\n* multi-line tag message\n padded", workspace.getGitClient().getTagMessage("test"));
    }

    @Test
    public void testCreateRef() throws Exception {
        workspace.commitEmpty("init");
        workspace.getGitClient().ref("refs/testing/testref");
        assertTrue("test ref not created", workspace.launchCommand("git", "show-ref").contains("refs/testing/testref"));
    }

    /**
     * inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue
     */
    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }

}
