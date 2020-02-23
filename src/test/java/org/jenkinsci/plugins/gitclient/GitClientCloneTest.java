package org.jenkinsci.plugins.gitclient;

import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.remoting.VirtualChannel;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.io.FileMatchers.*;
import static org.junit.Assert.assertFalse;

@RunWith(Parameterized.class)
public class GitClientCloneTest {

    @Rule
    public GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule secondRepo = new GitClientSampleRepoRule();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private int logCount = 0;
    private final Random random = new Random();
    private String revParseBranchName = null;
    private LogHandler handler = null;
    private static final String LOGGING_STARTED = "Logging started";
    private TaskListener listener;
    private final String gitImplName;

    WorkspaceWithRepoRule workspace;
    WorkspaceWithRepoRule secondWorkspace;

    private GitClient testGitClient;
    private File testGitDir;

    private int cloneTimeout = -1;
    private int checkoutTimeout = -1;
    private int fetchTimeout = -1;
    private int submoduleUpdateTimeout = -1;

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

    @Before
    public void setUpRepositories() throws Exception {
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        listener.getLogger().println(LOGGING_STARTED);

        workspace = new WorkspaceWithRepoRule(repo, gitImplName, listener);
        testGitClient = workspace.getGitClient();
        testGitDir = workspace.getGitFileDir();

        cloneTimeout = -1;
        checkoutTimeout = -1;
        fetchTimeout = -1;
        submoduleUpdateTimeout = -1;
    }

    @Test
    public void test_clone_default_timeout_logging() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();

        cloneTimeout = CliGitAPIImpl.TIMEOUT;
        assertCloneTimeout(testGitClient);
    }

    @Test()
    public void test_fetch_default_timeout_logging() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();

        testGitClient.fetch_().from(new URIish("origin"), null).execute();

        fetchTimeout = CliGitAPIImpl.TIMEOUT;
        assertFetchTimeout(testGitClient);
    }

    @Test()
    public void test_checkout_default_timeout_logging() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();

        testGitClient.checkout().ref("origin/master").execute();

        checkoutTimeout = CliGitAPIImpl.TIMEOUT;
        assertCheckoutTimeout(testGitClient);
    }

    @Test()
    public void test_submodule_update_default_timeout_logging() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        testGitClient.checkout().ref("origin/tests/getSubmodules").execute();

        checkoutTimeout = CliGitAPIImpl.TIMEOUT;
        assertCheckoutTimeout(testGitClient);

        testGitClient.submoduleUpdate().execute();

        submoduleUpdateTimeout = CliGitAPIImpl.TIMEOUT;
        assertSubmoduleUpdateTimeout(testGitClient);
    }

    @Test
    public void test_fetch_timeout() throws Exception {
        testGitClient.init();
        testGitClient.setRemoteUrl("origin", workspace.localMirror());
        List<RefSpec> refspecs = Collections.singletonList(new RefSpec("refs/heads/*:refs/remotes/origin/*"));
        fetchTimeout = CliGitAPIImpl.TIMEOUT + random.nextInt(24 * 60);
        testGitClient.fetch_().from(new URIish("origin"), refspecs).timeout(fetchTimeout).execute();
        assertFetchTimeout(testGitClient);
    }

    /** Clone arguments include:
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
        cloneTimeout = CliGitAPIImpl.TIMEOUT + random.nextInt(60 * 24);
        testGitClient.clone_().timeout(cloneTimeout).url(workspace.localMirror()).repositoryName("origin").execute();
        assertCloneTimeout(testGitClient);
        createRevParseBranch(); // Verify JENKINS-32258 is fixed
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternatesFileNotFound();
        assertThat("Shallow clone", workspace.cgit().isShallowRepository(), is(false));
    }

    @Test
    public void test_checkout_exception() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();
        createRevParseBranch();
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        final String SHA1 = "feedbeefbeaded";
        thrown.expect(GitException.class);
        thrown.expectMessage(is("Could not checkout master with start point " + SHA1));
        testGitClient.checkout().ref(SHA1).branch("master").execute();
    }

    @Test
    public void test_clone_repositoryName() throws IOException, InterruptedException {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("upstream").execute();
        testGitClient.checkout().ref("upstream/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "upstream");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternatesFileNotFound();
    }

    @Test
    public void test_clone_shallow() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").shallow(true).execute();
        createRevParseBranch(); // Verify JENKINS-32258 is fixed
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternatesFileNotFound();
        /* JGit does not support shallow clone */
        boolean hasShallowCloneSupport = testGitClient instanceof CliGitAPIImpl && workspace.cgit().isAtLeastVersion(1, 5, 0, 0);
        assertThat("isShallow?", workspace.cgit().isShallowRepository(), is(hasShallowCloneSupport));
        String shallow = ".git" + File.separator + "shallow";
        if (hasShallowCloneSupport) {
            assertThat(new File(testGitDir, shallow), is(anExistingFile()));
        } else {
            assertThat(new File(testGitDir, shallow), is(not(anExistingFile())));
        }
    }

    @Test
    public void test_clone_shallow_with_depth() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").shallow(true).depth(2).execute();
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternatesFileNotFound();
        /* JGit does not support shallow clone */
        boolean hasShallowCloneSupport = testGitClient instanceof CliGitAPIImpl && workspace.cgit().isAtLeastVersion(1, 5, 0, 0);
        assertThat("isShallow?", workspace.cgit().isShallowRepository(), is(hasShallowCloneSupport));
        String shallow = ".git" + File.separator + "shallow";
        if (hasShallowCloneSupport) {
            assertThat(new File(testGitDir, shallow), is(anExistingFile()));
        } else {
            assertThat(new File(testGitDir, shallow), is(not(anExistingFile())));
        }
    }

    @Test
    public void test_clone_shared() throws IOException, InterruptedException {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").shared(true).execute();
        createRevParseBranch(); // Verify JENKINS-32258 is fixed
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternateFilePointsToLocalMirror();
        assertNoObjectsInRepository();
    }

    @Test
    public void test_clone_null_branch() throws IOException, InterruptedException {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").shared(true).execute();
        createRevParseBranch();
        testGitClient.checkout().ref("origin/master").branch(null).execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertAlternateFilePointsToLocalMirror();
        assertNoObjectsInRepository();
    }

    @Test
    public void test_clone_unshared() throws IOException, InterruptedException {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").shared(false).execute();
        createRevParseBranch(); // Verify JENKINS-32258 is fixed
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternatesFileNotFound();
    }

    @Test
    public void test_clone_reference() throws Exception, IOException, InterruptedException {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").reference(workspace.localMirror()).execute();
        createRevParseBranch(); // Verify JENKINS-32258 is fixed
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternateFilePointsToLocalMirror();
        assertNoObjectsInRepository();
        // Verify JENKINS-46737 expected log message is written
        String messages = StringUtils.join(handler.getMessages(), ";");
        assertThat("Reference repo logged in: " + messages, handler.containsMessageSubstring("Using reference repository: "), is(true));
    }

    private static final String SRC_DIR = (new File(".")).getAbsolutePath();

    @Test
    public void test_clone_reference_working_repo() throws IOException, InterruptedException {
        assertThat(new File(SRC_DIR + File.separator + ".git"), is(anExistingDirectory()));
        final File shallowFile = new File(SRC_DIR + File.separator + ".git" + File.separator + "shallow");
        if (shallowFile.exists()) {
            return; /* Reference repository pointing to a shallow checkout is nonsense */
        }
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").reference(SRC_DIR).execute();
        testGitClient.checkout().ref("origin/master").branch("master").execute();
        check_remote_url(workspace, testGitClient, "origin");
        assertBranchesExist(testGitClient.getBranches(), "master");
        assertAlternatesFileExists();
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        final String expectedContent = SRC_DIR.replace("\\", "/") + "/.git/objects";
        final String actualContent = FileUtils.readFileToString(new File(testGitDir, alternates), "UTF-8");
        assertThat("Alternates file content", actualContent, is(expectedContent));
        final File alternatesDir = new File(actualContent);
        assertThat(alternatesDir, is(anExistingDirectory()));
    }

    @Test
    public void test_clone_refspec() throws Exception {
        testGitClient.clone_().url(workspace.localMirror()).repositoryName("origin").execute();

        WorkspaceWithRepoRule anotherWorkspace = new WorkspaceWithRepoRule(secondRepo, "git", listener);
        anotherWorkspace.launchCommand("git", "clone", anotherWorkspace.localMirror(), "./");
        anotherWorkspace.getGitClient().withRepository((final Repository realRepo, VirtualChannel channel) -> anotherWorkspace.getGitClient().withRepository((final Repository implRepo, VirtualChannel channel1) -> {
            final String realRefspec = realRepo.getConfig().getString(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, "fetch");
            final String implRefspec = implRepo.getConfig().getString(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, "fetch");
            assertThat("Refspecs vs. original clone", implRefspec, is(realRefspec));
            return null;
        }));
    }

    @Test
    public void test_clone_refspecs() throws Exception {
        List<RefSpec> refspecs = Arrays.asList(
                new RefSpec("+refs/heads/master:refs/remotes/origin/master"),
                new RefSpec("+refs/heads/1.4.x:refs/remotes/origin/1.4.x")
        );
        testGitClient.clone_().url(workspace.localMirror()).refspecs(refspecs).repositoryName("origin").execute();
        testGitClient.withRepository((Repository workRepo, VirtualChannel channel) -> {
            String[] fetchRefSpecs = workRepo.getConfig().getStringList(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, "fetch");
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
    public void test_clone_no_checkout() throws Exception {
        // Create a repo for cloning purpose
        WorkspaceWithRepoRule repoToClone = new WorkspaceWithRepoRule(secondRepo, "git", listener);
        repoToClone.getGitClient().init();
        repoToClone.commitEmpty("init");
        secondRepo.write("file1", "");
        repoToClone.getGitClient().add("file1");
        repoToClone.getGitClient().commit("commit1");

        // Clone it with no checkout
        workspace.getGitClient().clone_().url(repoToClone.getGitFileDir().getAbsolutePath()).repositoryName("origin").noCheckout().execute();
        assertFalse(new File(workspace.getGitFileDir(), "file1").exists());
    }

    private void assertAlternatesFileExists() {
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        assertThat(new File(testGitDir, alternates), is(anExistingFile()));
    }

    private void assertAlternatesFileNotFound() {
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
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
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";

        assertThat(new File(testGitDir, alternates), is(anExistingFile()));
        final String expectedContent = workspace.localMirror().replace("\\", "/") + "/objects";
        final String actualContent = FileUtils.readFileToString(new File(testGitDir, alternates), "UTF-8");
        assertThat("Alternates file content", actualContent, is(expectedContent));
        final File alternatesDir = new File(actualContent);
        assertThat(alternatesDir, is(anExistingDirectory()));
    }

    protected void createRevParseBranch() throws GitException, InterruptedException {
        revParseBranchName = "rev-parse-branch-" + UUID.randomUUID().toString();
        testGitClient.checkout().ref("origin/master").branch(revParseBranchName).execute();
    }

    protected void assertCloneTimeout(GitClient gitClient) {
        if (cloneTimeout > 0) {
            // clone_() uses "git fetch" internally, not "git clone"
            assertSubstringTimeout(gitClient, "git fetch", cloneTimeout);
        }
    }

    protected void assertCheckoutTimeout(GitClient gitClient) {
        if (checkoutTimeout > 0) {
            assertSubstringTimeout(gitClient, "git checkout", checkoutTimeout);
        }
    }

    protected void assertFetchTimeout(GitClient gitClient) {
        if (fetchTimeout > 0) {
            assertSubstringTimeout(gitClient, "git fetch", fetchTimeout);
        }
    }

    protected void assertSubmoduleUpdateTimeout(GitClient gitClient) {
        if (submoduleUpdateTimeout > 0) {
            assertSubstringTimeout(gitClient, "git submodule update", submoduleUpdateTimeout);
        }
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

    protected void assertSubstringTimeout(GitClient gitClient, final String substring, int expectedTimeout) {
        if (!(gitClient instanceof CliGitAPIImpl)) { // Timeout only implemented in CliGitAPIImpl
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
        assertThat("Timeout messages", substringTimeoutMessages, is(substringMessages));
    }

    private void check_remote_url(WorkspaceWithRepoRule workspace, GitClient gitClient, final String repositoryName) throws InterruptedException, IOException {
        assertThat("Remote URL", gitClient.getRemoteUrl(repositoryName), is(workspace.localMirror()));
        String remotes = workspace.launchCommand("git", "remote", "-v");
        assertThat("Updated URL", remotes, containsString(workspace.localMirror()));
    }

}
