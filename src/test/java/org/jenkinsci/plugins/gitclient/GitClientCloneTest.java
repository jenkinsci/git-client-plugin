package org.jenkinsci.plugins.gitclient;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.io.FileMatchers.*;
import static org.junit.Assert.assertThrows;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.remoting.VirtualChannel;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
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
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class GitClientCloneTest {

    @Rule
    public GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule secondRepo = new GitClientSampleRepoRule();

    private int logCount = 0;
    private final Random random = new Random();
    private LogHandler handler = null;
    private TaskListener listener;
    private final String gitImplName;

    WorkspaceWithRepo workspace;
    WorkspaceWithRepo secondWorkspace;

    private GitClient testGitClient;
    private File testGitDir;

    public GitClientCloneTest(final String gitImplName) {
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
        /* Allow 3-8 second delay before priming the cache */
        /* Allow other tests a better chance to prime the cache */
        /* 3-8 second delay is small compared to execution time of this test */
        Random random = new Random();
        Thread.sleep(3000L + random.nextInt(5000)); // Wait 3-8 seconds before priming the cache
        TaskListener mirrorListener = StreamTaskListener.fromStdout();
        File tempDir = Files.createTempDirectory("PrimeGitClientCloneTest").toFile();
        WorkspaceWithRepo cache = new WorkspaceWithRepo(tempDir, "git", mirrorListener);
        cache.localMirror();
        Util.deleteRecursive(tempDir);
    }

    @Before
    public void setUpRepositories() throws Exception {
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);

        workspace = new WorkspaceWithRepo(repo.getRoot(), gitImplName, listener);
        testGitClient = workspace.getGitClient();
        testGitDir = workspace.getGitFileDir();
    }

    /* Clone arguments include:
     *   repositoryName(String) - if omitted, CliGit does not set a remote repo name
     *   shallow() - no relevant assertion of success or failure of this argument
     *   shared() - not implemented on CliGit, not verified on JGit
     *   reference() - implemented on JGit, not verified on either JGit or CliGit
     *
     * CliGit and JGit both require the w.git.checkout() call
     * otherwise no branch is checked out. That is different than the
     * command line git program, but consistent within the git API.
     */
    @Test
    public void test_clone() throws Exception {
        int cloneTimeout = CliGitAPIImpl.TIMEOUT + random.nextInt(60 * 24);
        CloneCommand cmd = testGitClient
                .clone_()
                .timeout(cloneTimeout)
                .url(workspace.localMirror())
                .repositoryName("origin");
        if (random.nextBoolean()) {
            cmd.noCheckout(); // Randomly confirm this deprecated call is a no-op
        }
        cmd.execute();
        assertSubstringTimeout(testGitClient, "git fetch", cloneTimeout);
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternatesFileNotFound();
        assertThat("Shallow clone", workspace.cgit().isShallowRepository(), is(false));
    }

    @Test
    public void test_checkout_exception() throws Exception {
        CloneCommand cmd = testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin");
        if (random.nextBoolean()) {
            cmd.noCheckout(); // Randomly confirm this deprecated call is a no-op
        }
        cmd.execute();
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        final String SHA1 = "feedabeefabeadeddeedaccede";
        GitException gitException = assertThrows(
                GitException.class,
                () -> testGitClient.checkout().ref(SHA1).branch("master").execute());
        assertThat(gitException.getMessage(), is("Could not checkout master with start point " + SHA1));
    }

    @Test
    public void test_clone_repositoryName() throws IOException, InterruptedException {
        CloneCommand cmd = testGitClient.clone_().url(workspace.localMirror()).repositoryName("upstream");
        if (random.nextBoolean()) {
            cmd.noCheckout(); // Randomly confirm this deprecated call is a no-op
        }
        cmd.execute();
        testGitClient.checkout().ref("upstream/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "upstream");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternatesFileNotFound();
    }

    @Test
    public void test_clone_shallow() throws Exception {
        CloneCommand cmd = testGitClient
                .clone_()
                .url(workspace.localMirror())
                .repositoryName("origin")
                .shallow(true);
        if (random.nextBoolean()) {
            cmd.noCheckout(); // Randomly confirm this deprecated call is a no-op
        }
        cmd.execute();
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternatesFileNotFound();
        assertThat("isShallow?", workspace.cgit().isShallowRepository(), is(true));
        String shallow = ".git" + File.separator + "shallow";
        assertThat(new File(testGitDir, shallow), is(anExistingFile()));
    }

    @Test
    public void test_clone_shallow_with_depth() throws Exception {
        testGitClient
                .clone_()
                .url(workspace.localMirror())
                .repositoryName("origin")
                .shallow(true)
                .depth(2)
                .execute();
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternatesFileNotFound();
        assertThat("isShallow?", workspace.cgit().isShallowRepository(), is(true));
        String shallow = ".git" + File.separator + "shallow";
        assertThat(new File(testGitDir, shallow), is(anExistingFile()));
    }

    @Test
    public void test_clone_shared() throws IOException, InterruptedException {
        testGitClient
                .clone_()
                .url(workspace.localMirror())
                .repositoryName("origin")
                .shared(true)
                .tags(true)
                .execute();
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternateFilePointsToLocalMirror();
        assertNoObjectsInRepository();
    }

    @Test
    public void test_clone_null_branch() throws IOException, InterruptedException {
        testGitClient
                .clone_()
                .url(workspace.localMirror())
                .repositoryName("origin")
                .shared()
                .tags(false)
                .execute();
        testGitClient.checkout().ref("origin/master").branch(null).execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertAlternateFilePointsToLocalMirror();
        assertNoObjectsInRepository();
    }

    @Test
    public void test_clone_unshared() throws IOException, InterruptedException {
        testGitClient
                .clone_()
                .url(workspace.localMirror())
                .repositoryName("origin")
                .shared(false)
                .execute();
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternatesFileNotFound();
    }

    @Test
    public void test_clone_reference() throws Exception {
        testGitClient
                .clone_()
                .url(workspace.localMirror())
                .repositoryName("origin")
                .reference(workspace.localMirror())
                .execute();
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternateFilePointsToLocalMirror();
        assertNoObjectsInRepository();
        // Verify JENKINS-46737 expected log message is written
        String messages = StringUtils.join(handler.getMessages(), ";");
        assertThat(
                "Reference repo logged in: " + messages,
                handler.containsMessageSubstring("Using reference repository: "),
                is(true));
    }

    private static final String SRC_DIR = (new File(".")).getAbsolutePath();

    @Test
    public void test_clone_reference_working_repo() throws IOException, InterruptedException {
        assertThat(new File(SRC_DIR + File.separator + ".git"), is(anExistingDirectory()));
        final File shallowFile = new File(SRC_DIR + File.separator + ".git" + File.separator + "shallow");
        if (shallowFile.exists()) {
            return;
            /* Reference repository pointing to a shallow checkout is nonsense */
        }
        testGitClient
                .clone_()
                .url(workspace.localMirror())
                .repositoryName("origin")
                .reference(SRC_DIR)
                .execute();
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternatesFileExists();
        final String alternates =
                ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        final String expectedContent = SRC_DIR.replace("\\", "/") + "/.git/objects";
        final String actualContent = Files.readString(testGitDir.toPath().resolve(alternates), StandardCharsets.UTF_8);
        assertThat("Alternates file content", actualContent, is(expectedContent));
        final File alternatesDir = new File(actualContent);
        assertThat(alternatesDir, is(anExistingDirectory()));
    }

    @Test
    public void test_clone_refspec() throws Exception {
        testGitClient
                .clone_()
                .url(workspace.localMirror())
                .repositoryName("origin")
                .execute();

        WorkspaceWithRepo anotherWorkspace = new WorkspaceWithRepo(secondRepo.getRoot(), "git", listener);
        anotherWorkspace.launchCommand("git", "clone", anotherWorkspace.localMirror(), "./");
        anotherWorkspace
                .getGitClient()
                .withRepository((final Repository realRepo, VirtualChannel channel) -> anotherWorkspace
                        .getGitClient()
                        .withRepository((final Repository implRepo, VirtualChannel channel1) -> {
                            final String realRefspec = realRepo.getConfig()
                                    .getString(
                                            ConfigConstants.CONFIG_REMOTE_SECTION,
                                            Constants.DEFAULT_REMOTE_NAME,
                                            "fetch");
                            final String implRefspec = implRepo.getConfig()
                                    .getString(
                                            ConfigConstants.CONFIG_REMOTE_SECTION,
                                            Constants.DEFAULT_REMOTE_NAME,
                                            "fetch");
                            assertThat("Refspecs vs. original clone", implRefspec, is(realRefspec));
                            return null;
                        }));
    }

    @Test
    public void test_clone_refspecs() throws Exception {
        List<RefSpec> refspecs = Arrays.asList(
                new RefSpec("+refs/heads/master:refs/remotes/origin/master"),
                new RefSpec("+refs/heads/1.4.x:refs/remotes/origin/1.4.x"));
        testGitClient
                .clone_()
                .url(workspace.localMirror())
                .refspecs(refspecs)
                .repositoryName("origin")
                .execute();
        testGitClient.withRepository((Repository workRepo, VirtualChannel channel) -> {
            String[] fetchRefSpecs = workRepo.getConfig()
                    .getStringList(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, "fetch");
            assertThat(fetchRefSpecs.length, is(2));
            assertThat(fetchRefSpecs[0], is("+refs/heads/master:refs/remotes/origin/master"));
            assertThat(fetchRefSpecs[1], is("+refs/heads/1.4.x:refs/remotes/origin/1.4.x"));
            return null;
        });
        Set<Branch> remoteBranches = testGitClient.getRemoteBranches();
        assertBranchesExist(remoteBranches, "origin/master");
        assertBranchesExist(remoteBranches, "origin/1.4.x");
        assertThat(remoteBranches.size(), is(2));
    }

    @Test
    public void test_getRemoteURL_local_clone() throws Exception {
        workspace.cloneRepo(workspace, workspace.localMirror());
        assertThat("Origin URL", testGitClient.getRemoteUrl("origin"), is(workspace.localMirror()));
        String remotes = workspace.launchCommand("git", "remote", "-v");
        assertThat("Remote URL", remotes, containsString(workspace.localMirror()));
    }

    @Test
    public void test_setRemoteURL_local_clone() throws Exception {
        workspace.cloneRepo(workspace, workspace.localMirror());
        String originURL = "https://github.com/jenkinsci/git-client-plugin.git";
        testGitClient.setRemoteUrl("origin", originURL);
        assertThat("Origin URL", testGitClient.getRemoteUrl("origin"), is(originURL));
        String remotes = workspace.launchCommand("git", "remote", "-v");
        assertThat("Remote URL", remotes, containsString(originURL));
    }

    @Test
    public void test_addRemoteUrl_local_clone() throws Exception {
        workspace.cloneRepo(workspace, workspace.localMirror());
        assertThat("Origin URL before add", testGitClient.getRemoteUrl("origin"), is(workspace.localMirror()));
        String upstreamURL = "https://github.com/jenkinsci/git-client-plugin.git";
        testGitClient.addRemoteUrl("upstream", upstreamURL);
        assertThat("Upstream URL", testGitClient.getRemoteUrl("upstream"), is(upstreamURL));
        assertThat("Origin URL after add", testGitClient.getRemoteUrl("origin"), is(workspace.localMirror()));
    }

    @Test
    public void test_clone_default_timeout_logging() throws Exception {
        testGitClient
                .clone_()
                .url(workspace.localMirror())
                .repositoryName("origin")
                .execute();
        assertTimeout(testGitClient, "fetch", CliGitAPIImpl.TIMEOUT);
    }

    @Test
    public void test_clone_timeout_logging() throws Exception {
        int largerTimeout = CliGitAPIImpl.TIMEOUT + 1 + random.nextInt(600);
        testGitClient
                .clone_()
                .url(workspace.localMirror())
                .timeout(largerTimeout)
                .repositoryName("origin")
                .execute();
        assertTimeout(testGitClient, "fetch", largerTimeout);
    }

    @Test
    public void test_max_timeout_logging() throws Exception {
        int maxTimeout = JGitAPIImpl.MAX_TIMEOUT;
        testGitClient
                .clone_()
                .url(workspace.localMirror())
                .timeout(maxTimeout)
                .repositoryName("origin")
                .execute();
        assertTimeout(testGitClient, "fetch", maxTimeout);
    }

    @Test
    public void test_clone_huge_timeout_logging() throws Exception {
        int hugeTimeout = JGitAPIImpl.MAX_TIMEOUT + 1 + random.nextInt(Integer.MAX_VALUE - 1 - JGitAPIImpl.MAX_TIMEOUT);
        testGitClient
                .clone_()
                .url(workspace.localMirror())
                .timeout(hugeTimeout)
                .repositoryName("origin")
                .execute();
        /* Expect fallback value from JGit for this nonsense timeout value */
        int expectedValue = gitImplName.startsWith("jgit") ? JGitAPIImpl.MAX_TIMEOUT : hugeTimeout;
        assertTimeout(testGitClient, "fetch", expectedValue);
    }

    private void assertAlternatesFileExists() {
        final String alternates =
                ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        assertThat(new File(testGitDir, alternates), is(anExistingFile()));
    }

    private void assertAlternatesFileNotFound() {
        final String alternates =
                ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        assertThat(new File(testGitDir, alternates), is(not(anExistingFile())));
    }

    private void assertNoObjectsInRepository() {
        List<String> objectsDir = new ArrayList<>(Arrays.asList(new File(testGitDir, ".git/objects").list()));
        objectsDir.remove("info");
        objectsDir.remove("pack");
        assertThat(objectsDir, is(empty()));

        File packDir = new File(testGitDir, ".git/objects/pack");
        if (packDir.isDirectory()) {
            assertThat("Pack directory", packDir.list(), is(emptyArray()));
        }
    }

    private void assertAlternateFilePointsToLocalMirror() throws IOException, InterruptedException {
        final String alternates =
                ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";

        assertThat(new File(testGitDir, alternates), is(anExistingFile()));
        final String expectedContent = workspace.localMirror().replace("\\", "/") + "/objects";
        final String actualContent = Files.readString(testGitDir.toPath().resolve(alternates), StandardCharsets.UTF_8);
        assertThat("Alternates file content", actualContent, is(expectedContent));
        final File alternatesDir = new File(actualContent);
        assertThat(alternatesDir, is(anExistingDirectory()));
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

    protected void assertSubstringTimeout(GitClient gitClient, final String substring, int expectedTimeout) {
        if (!(gitClient instanceof CliGitAPIImpl)) { // Timeout only implemented in CliGitAPIImpl
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
        assertThat("Timeout messages", substringTimeoutMessages, is(substringMessages));
    }

    private void check_remote_url(WorkspaceWithRepo workspace, GitClient gitClient, final String repositoryName)
            throws InterruptedException, IOException {
        assertThat("Remote URL", gitClient.getRemoteUrl(repositoryName), is(workspace.localMirror()));
        String remotes = workspace.launchCommand("git", "remote", "-v");
        assertThat("Updated URL", remotes, containsString(workspace.localMirror()));
    }

    private void assertLoggedMessage(
            GitClient gitClient,
            final String candidateSubstring,
            final String expectedValue,
            final boolean expectToFindMatch) {
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
            assertThat(
                    "No messages matched substring '" + candidateSubstring + "'", candidateMessages, is(not(empty())));
            assertThat(
                    "Messages matched substring '" + candidateSubstring + "', found: " + candidateMessages
                            + "\nExpected " + expectedValue,
                    matchedMessages,
                    is(not(empty())));
            assertThat("All candidate messages matched", matchedMessages, is(candidateMessages));
        } else {
            assertThat(
                    "Messages matched substring '" + candidateSubstring + "' unexpectedly",
                    candidateMessages,
                    is(empty()));
        }
    }

    private void assertTimeout(GitClient gitClient, final String substring, int expectedTimeout) {
        String implName = gitImplName.equals("git") ? "git" : "JGit";
        String operationName = implName + " " + substring;
        assertLoggedMessage(gitClient, operationName, " [#] timeout=" + expectedTimeout, true);
    }
}
