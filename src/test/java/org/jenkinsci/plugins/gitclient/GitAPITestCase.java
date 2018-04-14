package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import static java.util.Collections.unmodifiableList;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.jenkinsci.plugins.gitclient.StringSharesPrefix.sharesPrefix;
import static org.junit.Assert.*;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.ProxyConfiguration;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitLockFailedException;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import hudson.remoting.VirtualChannel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import junit.framework.TestCase;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;
import org.objenesis.ObjenesisStd;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public abstract class GitAPITestCase extends TestCase {

    public final TemporaryDirectoryAllocator temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();

    protected hudson.EnvVars env = new hudson.EnvVars();
    protected TaskListener listener;

    protected LogHandler handler = null;
    private int logCount = 0;
    private static final String LOGGING_STARTED = "Logging started";

    private static final String SRC_DIR = (new File(".")).getAbsolutePath();
    private String revParseBranchName = null;

    private int checkoutTimeout = -1;
    private int cloneTimeout = -1;
    private int fetchTimeout = -1;
    private int submoduleUpdateTimeout = -1;
    private final Random random = new Random();

    private void createRevParseBranch() throws GitException, InterruptedException {
        revParseBranchName = "rev-parse-branch-" + UUID.randomUUID().toString();
        w.git.checkout("origin/master", revParseBranchName);
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
        if (!(w.git instanceof CliGitAPIImpl)) { // Timeout only implemented in CliGitAPIImpl
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

    /**
     * One local workspace of a Git repository on a temporary directory
     * that gets automatically cleaned up in the end.
     *
     * Every test case automatically gets one in {@link #w} but additional ones can be created if multi-repository
     * interactions need to be tested.
     */
    class WorkingArea {
        final File repo;
        final GitClient git;
        boolean bare = false;

        WorkingArea() throws Exception {
            this(temporaryDirectoryAllocator.allocate());
        }

        WorkingArea(File repo) throws Exception {
            this.repo = repo;
            git = setupGitAPI(repo);
            setupProxy(git);
        }

        private void setupProxy(GitClient gitClient)
              throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException
        {
          final String proxyHost = getSystemProperty("proxyHost", "http.proxyHost", "https.proxyHost");
          final String proxyPort = getSystemProperty("proxyPort", "http.proxyPort", "https.proxyPort");
          final String proxyUser = getSystemProperty("proxyUser", "http.proxyUser", "https.proxyUser");
          //final String proxyPassword = getSystemProperty("proxyPassword", "http.proxyPassword", "https.proxyPassword");
          final String noProxyHosts = getSystemProperty("noProxyHosts", "http.noProxyHosts", "https.noProxyHosts");
          if(isBlank(proxyHost) || isBlank(proxyPort)) return;
          ProxyConfiguration proxyConfig = new ObjenesisStd().newInstance(ProxyConfiguration.class);
          setField(ProxyConfiguration.class, "name", proxyConfig, proxyHost);
          setField(ProxyConfiguration.class, "port", proxyConfig, Integer.parseInt(proxyPort));
          setField(ProxyConfiguration.class, "userName", proxyConfig, proxyUser);
          setField(ProxyConfiguration.class, "noProxyHost", proxyConfig, noProxyHosts);
          //Password does not work since a set password results in a "Secret" call which expects a running Jenkins
          setField(ProxyConfiguration.class, "password", proxyConfig, null);
          setField(ProxyConfiguration.class, "secretPassword", proxyConfig, null);
          gitClient.setProxy(proxyConfig);
        }

        private void setField(Class<?> clazz, String fieldName, Object object, Object value)
              throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException
        {
          Field declaredField = clazz.getDeclaredField(fieldName);
          declaredField.setAccessible(true);
          declaredField.set(object, value);
        }

        private String getSystemProperty(String ... keyVariants)
        {
          for(String key : keyVariants) {
            String value = System.getProperty(key);
            if(value != null) return value;
          }
          return null;
        }

        String cmd(String args) throws IOException, InterruptedException {
            return launchCommand(args.split(" "));
        }

        String cmd(boolean ignoreError, String args) throws IOException, InterruptedException {
            return launchCommand(ignoreError, args.split(" "));
        }

        String launchCommand(String... args) throws IOException, InterruptedException {
            return launchCommand(false, args);
        }

        String launchCommand(boolean ignoreError, String... args) throws IOException, InterruptedException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int st = new Launcher.LocalLauncher(listener).launch().pwd(repo).cmds(args).
                    envs(env).stdout(out).join();
            String s = out.toString();
            if (!ignoreError) {
                if (s == null || s.isEmpty()) {
                    s = StringUtils.join(args, ' ');
                }
                assertEquals(s, 0, st); /* Reports full output of failing commands */
            }
            return s;
        }

        String repoPath() {
            return repo.getAbsolutePath();
        }

        WorkingArea init() throws IOException, InterruptedException {
            git.init();
            git.setAuthor("root", "root@mydomain.com");
            git.setCommitter("root", "root@domain.com");
            return this;
        }

        WorkingArea init(boolean bare) throws IOException, InterruptedException {
            git.init_().workspace(repoPath()).bare(bare).execute();
            return this;
        }

        void tag(String tag) throws IOException, InterruptedException {
            tag(tag, false);
        }

        void tag(String tag, boolean force) throws IOException, InterruptedException {
            cmd("git tag" + (force ? " --force " : " ") + tag);
        }

        void commitEmpty(String msg) throws IOException, InterruptedException {
            cmd("git commit --allow-empty -m " + msg);
        }

        /**
         * Refers to a file in this workspace
         */
        File file(String path) {
            return new File(repo, path);
        }

        boolean exists(String path) {
            return file(path).exists();
        }

        /**
         * Creates a file in the workspace.
         */
        void touch(String path) throws IOException {
            file(path).createNewFile();
        }

        /**
         * Creates a file in the workspace.
         */
        File touch(String path, String content) throws IOException {
            File f = file(path);
            FileUtils.writeStringToFile(f, content, "UTF-8");
            return f;
        }

        public void rm(String path) {
            file(path).delete();
        }

        public String contentOf(String path) throws IOException {
            return FileUtils.readFileToString(file(path), "UTF-8");
        }

        /**
         * Creates a CGit implementation. Sometimes we need this for testing JGit impl.
         */
        protected CliGitAPIImpl cgit() throws Exception {
            return (CliGitAPIImpl)Git.with(listener, env).in(repo).using("git").getClient();
        }

        /**
         * Creates a JGit implementation. Sometimes we need this for testing CliGit impl.
         */
        protected JGitAPIImpl jgit() throws Exception {
            return (JGitAPIImpl)Git.with(listener, env).in(repo).using("jgit").getClient();
        }

        /**
         * Creates a {@link Repository} object out of it.
         */
        protected FileRepository repo() throws IOException {
            return bare ? new FileRepository(repo) : new FileRepository(new File(repo, ".git"));
        }

        /**
         * Obtain the current HEAD revision
         */
        ObjectId head() throws IOException, InterruptedException {
            return git.revParse("HEAD");
        }

        /**
         * Casts the {@link #git} to {@link IGitAPI}
         */
        public IGitAPI igit() {
            return (IGitAPI)git;
        }
    }

    protected WorkingArea w;

    WorkingArea clone(String src) throws Exception {
        WorkingArea x = new WorkingArea();
        x.launchCommand("git", "clone", src, x.repoPath());
        return new WorkingArea(x.repo);
    }

    private boolean timeoutVisibleInCurrentTest;

    /**
     * Returns true if the current test is expected to have a timeout
     * value visible written to the listener log.  Used to assert
     * timeout values are passed correctly through the layers without
     * requiring that the timeout actually expire.
     * @see #setTimeoutVisibleInCurrentTest(boolean)
     */
    protected boolean getTimeoutVisibleInCurrentTest() {
        return timeoutVisibleInCurrentTest;
    }

    /**
     * Pass visible = true to cause the current test to assert that a
     * timeout value should be reported in at least one of the log
     * entries.
     * @param visible set to false if current test performs no operation which should report a timeout value
     * @see #getTimeoutVisibleInCurrentTest()
     */
    protected void setTimeoutVisibleInCurrentTest(boolean visible) {
        timeoutVisibleInCurrentTest = visible;
    }

    @Override
    protected void setUp() throws Exception {
        revParseBranchName = null;
        setTimeoutVisibleInCurrentTest(true);
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
        w = new WorkingArea();
    }

    /* HEAD ref of local mirror - all read access should use getMirrorHead */
    private static ObjectId mirrorHead = null;

    private ObjectId getMirrorHead() throws IOException, InterruptedException
    {
        if (mirrorHead == null) {
            final String mirrorPath = new File(localMirror()).getAbsolutePath();
            mirrorHead = ObjectId.fromString(w.launchCommand("git", "--git-dir=" + mirrorPath, "rev-parse", "HEAD").substring(0,40));
        }
        return mirrorHead;
    }

    private final String remoteMirrorURL = "https://github.com/jenkinsci/git-client-plugin.git";
    private final String remoteSshURL = "git@github.com:ndeloof/git-client-plugin.git";

    /**
     * Obtains the local mirror of https://github.com/jenkinsci/git-client-plugin.git and return URLish to it.
     */
    public String localMirror() throws IOException, InterruptedException {
        File base = new File(".").getAbsoluteFile();
        for (File f=base; f!=null; f=f.getParentFile()) {
            if (new File(f,"target").exists()) {
                File clone = new File(f, "target/clone.git");
                if (!clone.exists()) {  // TODO: perhaps some kind of quick timestamp-based up-to-date check?
                    w.launchCommand("git", "clone", "--mirror", "https://github.com/jenkinsci/git-client-plugin.git", clone.getAbsolutePath());
                }
                return clone.getPath();
            }
        }
        throw new IllegalStateException();
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

    protected abstract GitClient setupGitAPI(File ws) throws Exception;

    @Override
    protected void tearDown() throws Exception {
        try {
            temporaryDirectoryAllocator.dispose();
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
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

    private void check_remote_url(final String repositoryName) throws InterruptedException, IOException {
        assertEquals("Wrong remote URL", localMirror(), w.git.getRemoteUrl(repositoryName));
        String remotes = w.cmd("git remote -v");
        assertTrue("remote URL has not been updated", remotes.contains(localMirror()));
    }

    private void assertBranchesExist(Set<Branch> branches, String ... names) throws InterruptedException {
        Collection<String> branchNames = Collections2.transform(branches, new Function<Branch, String>() {
            public String apply(Branch branch) {
                return branch.getName();
            }
        });
        for (String name : names) {
            assertTrue(name + " branch not found in " + branchNames, branchNames.contains(name));
        }
    }

    private void assertBranchesNotExist(Set<Branch> branches, String ... names) throws InterruptedException {
        Collection<String> branchNames = Collections2.transform(branches, new Function<Branch, String>() {
            public String apply(Branch branch) {
                return branch.getName();
            }
        });
        for (String name : names) {
            assertFalse(name + " branch found in " + branchNames, branchNames.contains(name));
        }
    }

    public void test_setAuthor() throws Exception {
        final String authorName = "Test Author";
        final String authorEmail = "jenkins@example.com";
        w.init();
        w.touch("file1", "Varying content " + java.util.UUID.randomUUID().toString());
        w.git.add("file1");
        w.git.setAuthor(authorName, authorEmail);
        w.git.commit("Author was set explicitly on this commit");
        List<String> revision = w.git.showRevision(w.head());
        assertTrue("Wrong author in " + revision, revision.get(2).startsWith("author " + authorName + " <" + authorEmail +"> "));
    }

    public void test_setCommitter() throws Exception {
        final String committerName = "Test Commiter";
        final String committerEmail = "jenkins.plugin@example.com";
        w.init();
        w.touch("file1", "Varying content " + java.util.UUID.randomUUID().toString());
        w.git.add("file1");
        w.git.setCommitter(committerName, committerEmail);
        w.git.commit("Committer was set explicitly on this commit");
        List<String> revision = w.git.showRevision(w.head());
        assertTrue("Wrong committer in " + revision, revision.get(3).startsWith("committer " + committerName + " <" + committerEmail + "> "));
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
    public void test_clone() throws Exception
    {
        cloneTimeout = 1 + random.nextInt(60 * 24);
        w.git.clone_().timeout(cloneTimeout).url(localMirror()).repositoryName("origin").execute();
        createRevParseBranch(); // Verify JENKINS-32258 is fixed
        w.git.checkout("origin/master", "master");
        check_remote_url("origin");
        assertBranchesExist(w.git.getBranches(), "master");
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        assertFalse("Alternates file found: " + alternates, w.exists(alternates));
        assertFalse("Unexpected shallow clone", w.cgit().isShallowRepository());
    }

    public void test_checkout_exception() throws Exception {
        w.git.clone_().url(localMirror()).repositoryName("origin").execute();
        createRevParseBranch();
        w.git.checkout("origin/master", "master");
        final String SHA1 = "feedbeefbeaded";
        try {
            w.git.checkout(SHA1, "master");
            fail("Expected checkout exception not thrown");
        } catch (GitException ge) {
            assertEquals("Could not checkout master with start point " + SHA1, ge.getMessage());
        }
    }

    public void test_clone_repositoryName() throws IOException, InterruptedException
    {
        w.git.clone_().url(localMirror()).repositoryName("upstream").execute();
        w.git.checkout("upstream/master", "master");
        check_remote_url("upstream");
        assertBranchesExist(w.git.getBranches(), "master");
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        assertFalse("Alternates file found: " + alternates, w.exists(alternates));
    }

    public void test_clone_shallow() throws Exception
    {
        w.git.clone_().url(localMirror()).repositoryName("origin").shallow(true).execute();
        createRevParseBranch(); // Verify JENKINS-32258 is fixed
        w.git.checkout("origin/master", "master");
        check_remote_url("origin");
        assertBranchesExist(w.git.getBranches(), "master");
        assertAlternatesFileNotFound();
        /* JGit does not support shallow clone */
        assertEquals("isShallow?", w.igit() instanceof CliGitAPIImpl, w.cgit().isShallowRepository());
        final String shallow = ".git" + File.separator + "shallow";
        assertEquals("Shallow file existence: " + shallow, w.igit() instanceof CliGitAPIImpl, w.exists(shallow));
    }

    public void test_clone_shallow_with_depth() throws IOException, InterruptedException
    {
        w.git.clone_().url(localMirror()).repositoryName("origin").shallow(true).depth(2).execute();
        w.git.checkout("origin/master", "master");
        check_remote_url("origin");
        assertBranchesExist(w.git.getBranches(), "master");
        assertAlternatesFileNotFound();
        /* JGit does not support shallow clone */
        final String shallow = ".git" + File.separator + "shallow";
        assertEquals("Shallow file existence: " + shallow, w.igit() instanceof CliGitAPIImpl, w.exists(shallow));
    }

    public void test_clone_shared() throws IOException, InterruptedException
    {
        w.git.clone_().url(localMirror()).repositoryName("origin").shared().execute();
        createRevParseBranch(); // Verify JENKINS-32258 is fixed
        w.git.checkout("origin/master", "master");
        check_remote_url("origin");
        assertBranchesExist(w.git.getBranches(), "master");
        assertAlternateFilePointsToLocalMirror();
        assertNoObjectsInRepository();
    }

    public void test_clone_unshared() throws IOException, InterruptedException
    {
        w.git.clone_().url(localMirror()).repositoryName("origin").shared(false).execute();
        createRevParseBranch(); // Verify JENKINS-32258 is fixed
        w.git.checkout("origin/master", "master");
        check_remote_url("origin");
        assertBranchesExist(w.git.getBranches(), "master");
        assertAlternatesFileNotFound();
    }

    public void test_clone_reference() throws IOException, InterruptedException
    {
        w.git.clone_().url(localMirror()).repositoryName("origin").reference(localMirror()).execute();
        createRevParseBranch(); // Verify JENKINS-32258 is fixed
        w.git.checkout("origin/master", "master");
        check_remote_url("origin");
        assertBranchesExist(w.git.getBranches(), "master");
        assertAlternateFilePointsToLocalMirror();
        assertNoObjectsInRepository();
        // Verify JENKINS-46737 expected log message is written
        String messages = StringUtils.join(handler.getMessages(), ";");
        assertTrue("Reference repo not logged in: " + messages, handler.containsMessageSubstring("Using reference repository: "));
    }

    private void assertNoObjectsInRepository() {
        List<String> objectsDir = new ArrayList<>(Arrays.asList(w.file(".git/objects").list()));
        objectsDir.remove("info");
        objectsDir.remove("pack");
        assertTrue("Objects directory must not contain anything but 'info' and 'pack' folders", objectsDir.isEmpty());

        File packDir = w.file(".git/objects/pack");
        if (packDir.isDirectory()) {
            assertEquals("Pack dir must noct contain anything", 0, packDir.list().length);
        }

    }

    private void assertAlternatesFileNotFound() {
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        assertFalse("Alternates file found: " + alternates, w.exists(alternates));
    }

    private void assertAlternateFilePointsToLocalMirror() throws IOException, InterruptedException {
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";

        assertTrue("Alternates file not found: " + alternates, w.exists(alternates));
        final String expectedContent = localMirror().replace("\\", "/") + "/objects";
        final String actualContent = w.contentOf(alternates);
        assertEquals("Alternates file wrong content", expectedContent, actualContent);
        final File alternatesDir = new File(actualContent);
        assertTrue("Alternates destination " + actualContent + " missing", alternatesDir.isDirectory());
    }

    public void test_clone_reference_working_repo() throws IOException, InterruptedException
    {
        assertTrue("SRC_DIR " + SRC_DIR + " has no .git subdir", (new File(SRC_DIR + File.separator + ".git").isDirectory()));
        final File shallowFile = new File(SRC_DIR + File.separator + ".git" + File.separator + "shallow");
        if (shallowFile.exists()) {
            return; /* Reference repository pointing to a shallow checkout is nonsense */
        }
        w.git.clone_().url(localMirror()).repositoryName("origin").reference(SRC_DIR).execute();
        w.git.checkout("origin/master", "master");
        check_remote_url("origin");
        assertBranchesExist(w.git.getBranches(), "master");
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        assertTrue("Alternates file not found: " + alternates, w.exists(alternates));
        final String expectedContent = SRC_DIR.replace("\\", "/") + "/.git/objects";
        final String actualContent = w.contentOf(alternates);
        assertEquals("Alternates file wrong content", expectedContent, actualContent);
        final File alternatesDir = new File(actualContent);
        assertTrue("Alternates destination " + actualContent + " missing", alternatesDir.isDirectory());
    }

    public void test_clone_refspec() throws Exception {
        w.git.clone_().url(localMirror()).repositoryName("origin").execute();
        final WorkingArea w2 = new WorkingArea();
        w2.launchCommand("git", "clone", localMirror(), "./");
        w2.git.withRepository(new RepositoryCallback<Void>() {
            public Void invoke(final Repository realRepo, VirtualChannel channel) throws IOException, InterruptedException {
                return w.git.withRepository(new RepositoryCallback<Void>() {
                    public Void invoke(final Repository implRepo, VirtualChannel channel) {
                        final String realRefspec = realRepo.getConfig().getString(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, "fetch");
                        final String implRefspec = implRepo.getConfig().getString(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, "fetch");
                        assertEquals("Refspec not as git-clone", realRefspec, implRefspec);
                        return null;
                    }
                });
            }
        });
    }

    public void test_clone_refspecs() throws Exception {
      List<RefSpec> refspecs = Lists.newArrayList(
          new RefSpec("+refs/heads/master:refs/remotes/origin/master"),
          new RefSpec("+refs/heads/1.4.x:refs/remotes/origin/1.4.x")
      );
      w.git.clone_().url(localMirror()).refspecs(refspecs).repositoryName("origin").execute();
      w.git.withRepository(new RepositoryCallback<Void>() {
        public Void invoke(Repository repo, VirtualChannel channel) throws IOException, InterruptedException {
          String[] fetchRefSpecs = repo.getConfig().getStringList(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, "fetch");
          assertEquals("Expected 2 refspecs", 2, fetchRefSpecs.length);
          assertEquals("Incorrect refspec 1", "+refs/heads/master:refs/remotes/origin/master", fetchRefSpecs[0]);
          assertEquals("Incorrect refspec 2", "+refs/heads/1.4.x:refs/remotes/origin/1.4.x", fetchRefSpecs[1]);
          return null;
        }});
      Set<Branch> remoteBranches = w.git.getRemoteBranches();
      assertBranchesExist(remoteBranches, "origin/master");
      assertBranchesExist(remoteBranches, "origin/1.4.x");
      assertEquals(2, remoteBranches.size());
    }

    public void test_detect_commit_in_repo() throws Exception {
        w.init();
        assertFalse(w.git.isCommitInRepo(null)); // NPE safety check
        w.touch("file1");
        w.git.add("file1");
        w.git.commit("commit1");
        assertTrue("HEAD commit not found", w.git.isCommitInRepo(w.head()));
        // this MAY fail if commit has this exact sha1, but please admit this would be unlucky
        assertFalse(w.git.isCommitInRepo(ObjectId.fromString("1111111111111111111111111111111111111111")));
        assertFalse(w.git.isCommitInRepo(null)); // NPE safety check
    }

    @Deprecated
    public void test_lsTree_non_recursive() throws IOException, InterruptedException {
        w.init();
        w.touch("file1", "file1 fixed content");
        w.git.add("file1");
        w.git.commit("commit1");
        String expectedBlobSHA1 = "3f5a898e0c8ea62362dbf359cf1a400f3cfd46ae";
        List<IndexEntry> tree = w.igit().lsTree("HEAD", false);
        assertEquals("Wrong blob sha1", expectedBlobSHA1, tree.get(0).getObject());
        assertEquals("Wrong number of tree entries", 1, tree.size());
        final String remoteUrl = localMirror();
        w.igit().setRemoteUrl("origin", remoteUrl, w.repoPath() + File.separator + ".git");
        assertEquals("Wrong origin default remote", "origin", w.igit().getDefaultRemote("origin"));
        assertEquals("Wrong invalid default remote", "origin", w.igit().getDefaultRemote("invalid"));
    }

    @Deprecated
    public void test_lsTree_recursive() throws IOException, InterruptedException {
        w.init();
        assertTrue("mkdir dir1 failed", w.file("dir1").mkdir());
        w.touch("dir1/file1", "dir1/file1 fixed content");
        w.git.add("dir1/file1");
        w.touch("file2", "file2 fixed content");
        w.git.add("file2");
        w.git.commit("commit-dir-and-file");
        String expectedBlob1SHA1 = "a3ee484019f0576fcdeb48e682fa1058d0c74435";
        String expectedBlob2SHA1 = "aa1b259ac5e8d6cfdfcf4155a9ff6836b048d0ad";
        List<IndexEntry> tree = w.igit().lsTree("HEAD", true);
        assertEquals("Wrong blob 1 sha1", expectedBlob1SHA1, tree.get(0).getObject());
        assertEquals("Wrong blob 2 sha1", expectedBlob2SHA1, tree.get(1).getObject());
        assertEquals("Wrong number of tree entries", 2, tree.size());
        final String remoteUrl = "https://github.com/jenkinsci/git-client-plugin.git";
        w.git.setRemoteUrl("origin", remoteUrl);
        assertEquals("Wrong origin default remote", "origin", w.igit().getDefaultRemote("origin"));
        assertEquals("Wrong invalid default remote", "origin", w.igit().getDefaultRemote("invalid"));
    }

    @Deprecated
    public void test_getRemoteURL_two_args() throws Exception {
        w.init();
        String originUrl = "https://github.com/bogus/bogus.git";
        w.git.setRemoteUrl("origin", originUrl);
        assertEquals("Wrong remote URL", originUrl, w.git.getRemoteUrl("origin"));
        assertEquals("Wrong null remote URL", originUrl, w.igit().getRemoteUrl("origin", null));
        assertEquals("Wrong blank remote URL", originUrl, w.igit().getRemoteUrl("origin", ""));
        if (w.igit() instanceof CliGitAPIImpl) {
            String gitDir = w.repoPath() + File.separator + ".git";
            assertEquals("Wrong repoPath/.git remote URL for " + gitDir, originUrl, w.igit().getRemoteUrl("origin", gitDir));
            assertEquals("Wrong .git remote URL", originUrl, w.igit().getRemoteUrl("origin", ".git"));
        } else {
            assertEquals("Wrong repoPath remote URL", originUrl, w.igit().getRemoteUrl("origin", w.repoPath()));
        }
        // Fails on both JGit and CliGit, though with different failure modes in each
        // assertEquals("Wrong . remote URL", originUrl, w.igit().getRemoteUrl("origin", "."));
    }

    @Deprecated
    public void test_getDefaultRemote() throws Exception {
        w.init();
        w.cmd("git remote add origin https://github.com/jenkinsci/git-client-plugin.git");
        w.cmd("git remote add ndeloof git@github.com:ndeloof/git-client-plugin.git");
        assertEquals("Wrong origin default remote", "origin", w.igit().getDefaultRemote("origin"));
        assertEquals("Wrong ndeloof default remote", "ndeloof", w.igit().getDefaultRemote("ndeloof"));
        /* CliGitAPIImpl and JGitAPIImpl return different ordered lists for default remote if invalid */
        assertEquals("Wrong invalid default remote", w.git instanceof CliGitAPIImpl ? "ndeloof" : "origin",
                     w.igit().getDefaultRemote("invalid"));
    }

    public void test_getRemoteURL() throws Exception {
        w.init();
        w.cmd("git remote add origin https://github.com/jenkinsci/git-client-plugin.git");
        w.cmd("git remote add ndeloof git@github.com:ndeloof/git-client-plugin.git");
        String remoteUrl = w.git.getRemoteUrl("origin");
        assertEquals("unexepected remote URL " + remoteUrl, "https://github.com/jenkinsci/git-client-plugin.git", remoteUrl);
    }

    public void test_getRemoteURL_local_clone() throws Exception {
        w = clone(localMirror());
        assertEquals("Wrong origin URL", localMirror(), w.git.getRemoteUrl("origin"));
        String remotes = w.cmd("git remote -v");
        assertTrue("remote URL has not been updated", remotes.contains(localMirror()));
    }

    public void test_setRemoteURL() throws Exception {
        w.init();
        w.cmd("git remote add origin https://github.com/jenkinsci/git-client-plugin.git");
        w.git.setRemoteUrl("origin", "git@github.com:ndeloof/git-client-plugin.git");
        String remotes = w.cmd("git remote -v");
        assertTrue("remote URL has not been updated", remotes.contains("git@github.com:ndeloof/git-client-plugin.git"));
    }

    public void test_setRemoteURL_local_clone() throws Exception {
        w = clone(localMirror());
        String originURL = "https://github.com/jenkinsci/git-client-plugin.git";
        w.git.setRemoteUrl("origin", originURL);
        assertEquals("Wrong origin URL", originURL, w.git.getRemoteUrl("origin"));
        String remotes = w.cmd("git remote -v");
        assertTrue("remote URL has not been updated", remotes.contains(originURL));
    }

    public void test_addRemoteUrl_local_clone() throws Exception {
        w = clone(localMirror());
        assertEquals("Wrong origin URL before add", localMirror(), w.git.getRemoteUrl("origin"));
        String upstreamURL = "https://github.com/jenkinsci/git-client-plugin.git";
        w.git.addRemoteUrl("upstream", upstreamURL);
        assertEquals("Wrong upstream URL", upstreamURL, w.git.getRemoteUrl("upstream"));
        assertEquals("Wrong origin URL after add", localMirror(), w.git.getRemoteUrl("origin"));
    }

    @Bug(20410)
    public void test_clean() throws Exception {
        w.init();
        w.commitEmpty("init");

        /* String starts with a surrogate character, mathematical
         * double struck small t as the first character of the file
         * name. The last three characters of the file name are three
         * different forms of the a-with-ring character. Refer to
         * http://unicode.org/reports/tr15/#Detecting_Normalization_Forms
         * for the source of those example characters.
         */
        String fileName = "\uD835\uDD65-\u5c4f\u5e55\u622a\u56fe-\u0041\u030a-\u00c5-\u212b-fileName.xml";
        w.touch(fileName, "content " + fileName);
        w.git.add(fileName);
        w.git.commit(fileName);

        /* JENKINS-27910 reported that certain cyrillic file names
         * failed to delete if the encoding was not UTF-8.
         */
        String fileNameSwim = "\u00d0\u00bf\u00d0\u00bb\u00d0\u00b0\u00d0\u00b2\u00d0\u00b0\u00d0\u00bd\u00d0\u00b8\u00d0\u00b5-swim.png";
        w.touch(fileNameSwim, "content " + fileNameSwim);
        w.git.add(fileNameSwim);
        w.git.commit(fileNameSwim);

        String fileNameFace = "\u00d0\u00bb\u00d0\u00b8\u00d1\u2020\u00d0\u00be-face.png";
        w.touch(fileNameFace, "content " + fileNameFace);
        w.git.add(fileNameFace);
        w.git.commit(fileNameFace);

        w.touch(".gitignore", ".test");
        w.git.add(".gitignore");
        w.git.commit("ignore");

        String dirName1 = "\u5c4f\u5e55\u622a\u56fe-dir-not-added";
        String fileName1 = dirName1 + File.separator + "\u5c4f\u5e55\u622a\u56fe-fileName1-not-added.xml";
        String fileName2 = ".test-\u00f8\u00e4\u00fc\u00f6-fileName2-not-added";
        assertTrue("Did not create dir " + dirName1, w.file(dirName1).mkdir());
        w.touch(fileName1);
        w.touch(fileName2);
        w.touch(fileName, "new content");

        w.git.clean();
        assertFalse(w.exists(dirName1));
        assertFalse(w.exists(fileName1));
        assertFalse(w.exists(fileName2));
        assertEquals("content " + fileName, w.contentOf(fileName));
        assertEquals("content " + fileNameFace, w.contentOf(fileNameFace));
        assertEquals("content " + fileNameSwim, w.contentOf(fileNameSwim));
        String status = w.cmd("git status");
        assertTrue("unexpected status " + status, status.contains("working directory clean") || status.contains("working tree clean"));

        /* A few poorly placed tests of hudson.FilePath - testing JENKINS-22434 */
        FilePath fp = new FilePath(w.file(fileName));
        assertTrue(fp + " missing", fp.exists());

        assertTrue("mkdir " + dirName1 + " failed", w.file(dirName1).mkdir());
        assertTrue("dir " + dirName1 + " missing", w.file(dirName1).isDirectory());
        FilePath dir1 = new FilePath(w.file(dirName1));
        w.touch(fileName1);
        assertTrue("Did not create file " + fileName1, w.file(fileName1).exists());

        assertTrue(dir1 + " missing", dir1.exists());
        dir1.deleteRecursive(); /* Fails on Linux JDK 7 with LANG=C, ok with LANG=en_US.UTF-8 */
                                /* Java reports "Malformed input or input contains unmappable chacraters" */
        assertFalse("Did not delete file " + fileName1, w.file(fileName1).exists());
        assertFalse(dir1 + " not deleted", dir1.exists());

        w.touch(fileName2);
        FilePath fp2 = new FilePath(w.file(fileName2));

        assertTrue(fp2 + " missing", fp2.exists());
        fp2.delete();
        assertFalse(fp2 + " not deleted", fp2.exists());

        String dirContents = Arrays.toString((new File(w.repoPath())).listFiles());
        String finalStatus = w.cmd("git status");
        assertTrue("unexpected final status " + finalStatus + " dir contents: " + dirContents, finalStatus.contains("working directory clean") || finalStatus.contains("working tree clean"));
    }

    public void test_fetch() throws Exception {
        /* Create a working repo containing a commit */
        w.init();
        w.touch("file1", "file1 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file1");
        w.git.commit("commit1");
        ObjectId commit1 = w.head();

        /* Clone working repo into a bare repo */
        WorkingArea bare = new WorkingArea();
        bare.init(true);
        w.git.setRemoteUrl("origin", bare.repoPath());
        Set<Branch> remoteBranchesEmpty = w.git.getRemoteBranches();
        assertEquals("Unexpected branch count", 0, remoteBranchesEmpty.size());
        w.git.push("origin", "master");
        ObjectId bareCommit1 = bare.git.getHeadRev(bare.repoPath(), "master");
        assertEquals("bare != working", commit1, bareCommit1);
        assertEquals(commit1, bare.git.getHeadRev(bare.repoPath(), "refs/heads/master"));

        /* Clone new working repo from bare repo */
        WorkingArea newArea = clone(bare.repoPath());
        ObjectId newAreaHead = newArea.head();
        assertEquals("bare != newArea", bareCommit1, newAreaHead);
        Set<Branch> remoteBranches1 = newArea.git.getRemoteBranches();
        assertEquals("Unexpected branch count in " + remoteBranches1, 2, remoteBranches1.size());
        assertEquals(bareCommit1, newArea.git.getHeadRev(newArea.repoPath(), "refs/heads/master"));

        /* Commit a new change to the original repo */
        w.touch("file2", "file2 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file2");
        w.git.commit("commit2");
        ObjectId commit2 = w.head();
        assertEquals(commit2, w.git.getHeadRev(w.repoPath(), "refs/heads/master"));

        /* Push the new change to the bare repo */
        w.git.push("origin", "master");
        ObjectId bareCommit2 = bare.git.getHeadRev(bare.repoPath(), "master");
        assertEquals("bare2 != working2", commit2, bareCommit2);
        assertEquals(commit2, bare.git.getHeadRev(bare.repoPath(), "refs/heads/master"));

        /* Fetch new change into newArea repo */
        RefSpec defaultRefSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        List<RefSpec> refSpecs = new ArrayList<>();
        refSpecs.add(defaultRefSpec);
        newArea.git.fetch(new URIish(bare.repo.toString()), refSpecs);

        /* Confirm the fetch did not alter working branch */
        assertEquals("beforeMerge != commit1", commit1, newArea.head());

        /* Merge the fetch results into working branch */
        newArea.git.merge().setRevisionToMerge(bareCommit2).execute();
        assertEquals("bare2 != newArea2", bareCommit2, newArea.head());

        /* Commit a new change to the original repo */
        w.touch("file3", "file3 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file3");
        w.git.commit("commit3");
        ObjectId commit3 = w.head();

        /* Push the new change to the bare repo */
        w.git.push("origin", "master");
        ObjectId bareCommit3 = bare.git.getHeadRev(bare.repoPath(), "master");
        assertEquals("bare3 != working3", commit3, bareCommit3);

        /* Fetch new change into newArea repo using different argument forms */
        newArea.git.fetch(null, defaultRefSpec);
        newArea.git.fetch(null, defaultRefSpec, defaultRefSpec);

        /* Merge the fetch results into working branch */
        newArea.git.merge().setRevisionToMerge(bareCommit3).execute();
        assertEquals("bare3 != newArea3", bareCommit3, newArea.head());

        /* Commit a new change to the original repo */
        w.touch("file4", "file4 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file4");
        w.git.commit("commit4");
        ObjectId commit4 = w.head();

        /* Push the new change to the bare repo */
        w.git.push("origin", "master");
        ObjectId bareCommit4 = bare.git.getHeadRev(bare.repoPath(), "master");
        assertEquals("bare4 != working4", commit4, bareCommit4);

        /* Fetch new change into newArea repo using a different argument form */
        RefSpec [] refSpecArray = { defaultRefSpec, defaultRefSpec };
        newArea.git.fetch("origin", refSpecArray);

        /* Merge the fetch results into working branch */
        newArea.git.merge().setRevisionToMerge(bareCommit4).execute();
        assertEquals("bare4 != newArea4", bareCommit4, newArea.head());

        /* Commit a new change to the original repo */
        w.touch("file5", "file5 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file5");
        w.git.commit("commit5");
        ObjectId commit5 = w.head();

        /* Push the new change to the bare repo */
        w.git.push("origin", "master");
        ObjectId bareCommit5 = bare.git.getHeadRev(bare.repoPath(), "master");
        assertEquals("bare5 != working5", commit5, bareCommit5);

        /* Fetch into newArea repo with null RefSpec - should only
         * pull tags, not commits in git versions prior to git 1.9.0.
         * In git 1.9.0, fetch -t pulls tags and versions. */
        newArea.git.fetch("origin", null, null);
        assertEquals("null refSpec fetch modified local repo", bareCommit4, newArea.head());
        ObjectId expectedHead = bareCommit4;
        try {
            /* Assert that change did not arrive in repo if git
             * command line less than 1.9.  Assert that change arrives in
             * repo if git command line 1.9 or later. */
            newArea.git.merge().setRevisionToMerge(bareCommit5).execute();
            assertTrue("JGit should not have copied the revision", newArea.git instanceof CliGitAPIImpl);
            assertTrue("Wrong git version", w.cgit().isAtLeastVersion(1, 9, 0, 0));
            expectedHead = bareCommit5;
        } catch (org.eclipse.jgit.api.errors.JGitInternalException je) {
            String expectedSubString = "Missing commit " + bareCommit5.name();
            assertTrue("Wrong jgit message :" + je.getMessage(), je.getMessage().contains(expectedSubString));
        } catch (GitException ge) {
            assertTrue("Wrong cli git message :" + ge.getMessage(),
                       ge.getMessage().contains("Could not merge") ||
                       ge.getMessage().contains("not something we can merge") ||
                       ge.getMessage().contains("does not point to a commit"));
            assertTrue("Wrong message :" + ge.getMessage(), ge.getMessage().contains(bareCommit5.name()));
        }
        /* Assert that expected change is in repo after merge.  With
         * git 1.7 and 1.8, it should be bareCommit4.  With git 1.9
         * and later, it should be bareCommit5. */
        assertEquals("null refSpec fetch modified local repo", expectedHead, newArea.head());

        try {
            /* Fetch into newArea repo with invalid repo name and no RefSpec */
            newArea.git.fetch("invalid-remote-name");
            fail("Should have thrown an exception");
        } catch (GitException ge) {
            assertTrue("Wrong message :" + ge.getMessage(), ge.getMessage().contains("invalid-remote-name"));
        }
    }

    public void test_push_tags() throws Exception {
        /* Create a working repo containing a commit */
        w.init();
        w.touch("file1", "file1 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file1");
        w.git.commit("commit1");
        ObjectId commit1 = w.head();

        /* Clone working repo into a bare repo */
        WorkingArea bare = new WorkingArea();
        bare.init(true);
        w.git.setRemoteUrl("origin", bare.repoPath());
        Set<Branch> remoteBranchesEmpty = w.git.getRemoteBranches();
        assertEquals("Unexpected branch count", 0, remoteBranchesEmpty.size());
        w.git.push("origin", "master");
        ObjectId bareCommit1 = bare.git.getHeadRev(bare.repoPath(), "master");
        assertEquals("bare != working", commit1, bareCommit1);
        assertEquals(commit1, bare.git.getHeadRev(bare.repoPath(), "refs/heads/master"));

        /* Add tag1 to working repo without pushing it to bare repo */
        w.tag("tag1");
        assertTrue("tag1 wasn't created", w.git.tagExists("tag1"));
        assertEquals("tag1 points to wrong commit", commit1, w.git.revParse("tag1"));
        w.git.push().ref("master").to(new URIish(bare.repoPath())).tags(false).execute();
        assertFalse("tag1 pushed unexpectedly", bare.cmd("git tag").contains("tag1"));

        /* Push tag1 to bare repo */
        w.git.push().ref("master").to(new URIish(bare.repoPath())).tags(true).execute();
        assertTrue("tag1 not pushed", bare.cmd("git tag").contains("tag1"));

        /* Create a new commit, move tag1 to that commit, attempt push */
        w.touch("file1", "file1 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file1");
        w.git.commit("commit2");
        ObjectId commit2 = w.head();
        w.tag("tag1", true); /* Tag already exists, move from commit1 to commit2 */
        assertTrue("tag1 wasn't created", w.git.tagExists("tag1"));
        assertEquals("tag1 points to wrong commit", commit2, w.git.revParse("tag1"));
        try {
            w.git.push().ref("master").to(new URIish(bare.repoPath())).tags(true).execute();
            /* JGit does not throw exception updating existing tag - ugh */
            /* CliGit before 1.8 does not throw exception updating existing tag - ugh */
            if (w.git instanceof CliGitAPIImpl && w.cgit().isAtLeastVersion(1, 8, 0, 0)) {
	        fail("Modern CLI git should throw exception pushing a change to existing tag");
	    }
        } catch (GitException ge) {
            assertThat(ge.getMessage(), containsString("already exists"));
        }
        try {
            w.git.push().ref("master").to(new URIish(bare.repoPath())).tags(true).force(false).execute();
            /* JGit does not throw exception updating existing tag - ugh */
            /* CliGit before 1.8 does not throw exception updating existing tag - ugh */
            if (w.git instanceof CliGitAPIImpl && w.cgit().isAtLeastVersion(1, 8, 0, 0)) {
	        fail("Modern CLI git should throw exception pushing a change to existing tag");
	    }
        } catch (GitException ge) {
            assertThat(ge.getMessage(), containsString("already exists"));
        }
        w.git.push().ref("master").to(new URIish(bare.repoPath())).tags(true).force(true).execute();

        /* Add tag to working repo without pushing it to the bare
         * repo, tests the default behavior when tags() is not added
         * to PushCommand.
         */
        w.tag("tag3");
        assertTrue("tag3 wasn't created", w.git.tagExists("tag3"));
        w.git.push().ref("master").to(new URIish(bare.repoPath())).execute();
        assertFalse("tag3 was pushed", bare.cmd("git tag").contains("tag3"));

        /* Add another tag to working repo and push tags to the bare repo */
        w.touch("file2", "file2 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file2");
        w.git.commit("commit2");
        w.tag("tag2");
        assertTrue("tag2 wasn't created", w.git.tagExists("tag2"));
        w.git.push().ref("master").to(new URIish(bare.repoPath())).tags(true).execute();
        assertTrue("tag1 wasn't pushed", bare.cmd("git tag").contains("tag1"));
        assertTrue("tag2 wasn't pushed", bare.cmd("git tag").contains("tag2"));
        assertTrue("tag3 wasn't pushed", bare.cmd("git tag").contains("tag3"));
    }

    @Bug(19591)
    public void test_fetch_needs_preceding_prune() throws Exception {
        /* Create a working repo containing a commit */
        w.init();
        w.touch("file1", "file1 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file1");
        w.git.commit("commit1");
        ObjectId commit1 = w.head();
        assertEquals("Wrong branch count", 1, w.git.getBranches().size());
        assertTrue("Remote branches should not exist", w.git.getRemoteBranches().isEmpty());

        /* Prune when a remote is not yet defined */
        try {
            w.git.prune(new RemoteConfig(new Config(), "remote-is-not-defined"));
            fail("Should have thrown an exception");
        } catch (GitException ge) {
            String expected = w.git instanceof CliGitAPIImpl ? "returned status code 1" : "The uri was empty or null";
            final String msg = ge.getMessage();
            assertTrue("Wrong exception: " + msg, msg.contains(expected));
        }

        /* Clone working repo into a bare repo */
        WorkingArea bare = new WorkingArea();
        bare.init(true);
        w.git.setRemoteUrl("origin", bare.repoPath());
        w.git.push("origin", "master");
        ObjectId bareCommit1 = bare.git.getHeadRev(bare.repoPath(), "master");
        assertEquals("bare != working", commit1, bareCommit1);
        assertEquals("Wrong branch count", 1, w.git.getBranches().size());
        assertTrue("Remote branches should not exist", w.git.getRemoteBranches().isEmpty());

        /* Create a branch in working repo named "parent" */
        w.git.branch("parent");
        w.git.checkout("parent");
        w.touch("file2", "file2 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file2");
        w.git.commit("commit2");
        ObjectId commit2 = w.head();
        assertEquals("Wrong branch count", 2, w.git.getBranches().size());
        assertTrue("Remote branches should not exist", w.git.getRemoteBranches().isEmpty());

        /* Push branch named "parent" to bare repo */
        w.git.push("origin", "parent");
        ObjectId bareCommit2 = bare.git.getHeadRev(bare.repoPath(), "parent");
        assertEquals("working parent != bare parent", commit2, bareCommit2);
        assertEquals("Wrong branch count", 2, w.git.getBranches().size());
        assertTrue("Remote branches should not exist", w.git.getRemoteBranches().isEmpty());

        /* Clone new working repo from bare repo */
        WorkingArea newArea = clone(bare.repoPath());
        ObjectId newAreaHead = newArea.head();
        assertEquals("bare != newArea", bareCommit1, newAreaHead);
        Set<Branch> remoteBranches = newArea.git.getRemoteBranches();
        assertBranchesExist(remoteBranches, "origin/master", "origin/parent", "origin/HEAD");
        assertEquals("Wrong count in " + remoteBranches, 3, remoteBranches.size());

        /* Checkout parent in new working repo */
        newArea.git.checkout("origin/parent", "parent");
        ObjectId newAreaParent = newArea.head();
        assertEquals("parent1 != newAreaParent", commit2, newAreaParent);

        /* Delete parent branch from w */
        w.git.checkout("master");
        w.cmd("git branch -D parent");
        assertEquals("Wrong branch count", 1, w.git.getBranches().size());

        /* Delete parent branch on bare repo*/
        bare.cmd("git branch -D parent");
        // assertEquals("Wrong branch count", 1, bare.git.getBranches().size());

        /* Create parent/a branch in working repo */
        w.git.branch("parent/a");
        w.git.checkout("parent/a");
        w.touch("file3", "file3 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file3");
        w.git.commit("commit3");
        ObjectId commit3 = w.head();

        /* Push parent/a branch to bare repo */
        w.git.push("origin", "parent/a");
        ObjectId bareCommit3 = bare.git.getHeadRev(bare.repoPath(), "parent/a");
        assertEquals("parent/a != bare", commit3, bareCommit3);
        remoteBranches = bare.git.getRemoteBranches();
        assertEquals("Wrong count in " + remoteBranches, 0, remoteBranches.size());

        RefSpec defaultRefSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        List<RefSpec> refSpecs = new ArrayList<>();
        refSpecs.add(defaultRefSpec);
        try {
            /* Fetch parent/a into newArea repo - fails for
             * CliGitAPIImpl, succeeds for JGitAPIImpl */
            newArea.git.fetch(new URIish(bare.repo.toString()), refSpecs);
            assertTrue("CliGit should have thrown an exception", newArea.git instanceof JGitAPIImpl);
        } catch (GitException ge) {
            final String msg = ge.getMessage();
            assertTrue("Wrong exception: " + msg, msg.contains("some local refs could not be updated") || msg.contains("error: cannot lock ref "));
        }

        /* Use git remote prune origin to remove obsolete branch named "parent" */
        newArea.git.prune(new RemoteConfig(new Config(), "origin"));

        /* Fetch should succeed */
        newArea.git.fetch_().from(new URIish(bare.repo.toString()), refSpecs).execute();
    }

    public void test_fetch_timeout() throws Exception {
        w.init();
        w.git.setRemoteUrl("origin", localMirror());
        List<RefSpec> refspecs = Collections.singletonList(new RefSpec("refs/heads/*:refs/remotes/origin/*"));
        fetchTimeout = 1 + random.nextInt(24 * 60);
        w.git.fetch_().from(new URIish("origin"), refspecs).timeout(fetchTimeout).execute();
    }

    /**
     * JGit 3.3.0 thru 3.6.0 "prune during fetch" prunes more remote
     * branches than command line git prunes during fetch.  This test
     * should be used to evaluate future versions of JGit to see if
     * pruning behavior more closely emulates command line git.
     *
     * This has been fixed using a workaround.
     */
    public void test_fetch_with_prune() throws Exception {
        WorkingArea bare = new WorkingArea();
        bare.init(true);

        /* Create a working repo containing three branches */
        /* master -> branch1 */
        /*        -> branch2 */
        w.init();
        w.touch("file-master", "file master content " + java.util.UUID.randomUUID().toString());
        w.git.add("file-master");
        w.git.commit("master-commit");
        ObjectId master = w.head();
        assertEquals("Wrong branch count", 1, w.git.getBranches().size());
        w.git.setRemoteUrl("origin", bare.repoPath());
        w.git.push("origin", "master"); /* master branch is now on bare repo */

        w.git.checkout("master");
        w.git.branch("branch1");
        w.touch("file-branch1", "file branch1 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file-branch1");
        w.git.commit("branch1-commit");
        ObjectId branch1 = w.head();
        assertEquals("Wrong branch count", 2, w.git.getBranches().size());
        w.git.push("origin", "branch1"); /* branch1 is now on bare repo */

        w.git.checkout("master");
        w.git.branch("branch2");
        w.touch("file-branch2", "file branch2 content " + java.util.UUID.randomUUID().toString());
        w.git.add("file-branch2");
        w.git.commit("branch2-commit");
        ObjectId branch2 = w.head();
        assertEquals("Wrong branch count", 3, w.git.getBranches().size());
        assertTrue("Remote branches should not exist", w.git.getRemoteBranches().isEmpty());
        w.git.push("origin", "branch2"); /* branch2 is now on bare repo */

        /* Clone new working repo from bare repo */
        WorkingArea newArea = clone(bare.repoPath());
        ObjectId newAreaHead = newArea.head();
        Set<Branch> remoteBranches = newArea.git.getRemoteBranches();
        assertBranchesExist(remoteBranches, "origin/master", "origin/branch1", "origin/branch2", "origin/HEAD");
        assertEquals("Wrong count in " + remoteBranches, 4, remoteBranches.size());

        /* Remove branch1 from bare repo using original repo */
        w.cmd("git push " + bare.repoPath() + " :branch1");

        RefSpec defaultRefSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        List<RefSpec> refSpecs = new ArrayList<>();
        refSpecs.add(defaultRefSpec);

        /* Fetch without prune should leave branch1 in newArea */
        newArea.git.fetch_().from(new URIish(bare.repo.toString()), refSpecs).execute();
        remoteBranches = newArea.git.getRemoteBranches();
        assertBranchesExist(remoteBranches, "origin/master", "origin/branch1", "origin/branch2", "origin/HEAD");
        assertEquals("Wrong count in " + remoteBranches, 4, remoteBranches.size());

        /* Fetch with prune should remove branch1 from newArea */
        newArea.git.fetch_().from(new URIish(bare.repo.toString()), refSpecs).prune().execute();
        remoteBranches = newArea.git.getRemoteBranches();
        assertBranchesExist(remoteBranches, "origin/master", "origin/branch2", "origin/HEAD");

        /* Git 1.7.1 on Red Hat 6 does not prune branch1, don't fail the test
         * on that old git version.
         */
        int expectedBranchCount = 3;
        if (newArea.git instanceof CliGitAPIImpl && !w.cgit().isAtLeastVersion(1, 7, 9, 0)) {
            expectedBranchCount = 4;
            assertBranchesExist(remoteBranches, "origin/master", "origin/branch1", "origin/branch2", "origin/HEAD");
        } else {
            assertBranchesExist(remoteBranches, "origin/master", "origin/branch2", "origin/HEAD");
            assertBranchesNotExist(remoteBranches, "origin/branch1");
        }
        assertEquals("Wrong remote branch count", expectedBranchCount, remoteBranches.size());
    }

    public void test_fetch_from_url() throws Exception {
        WorkingArea r = new WorkingArea();
        r.init();
        r.commitEmpty("init");
        String sha1 = r.cmd("git rev-list --no-walk --max-count=1 HEAD");

        w.init();
        w.cmd("git remote add origin " + r.repoPath());
        w.git.fetch(new URIish(r.repo.toString()), Collections.<RefSpec>emptyList());
        assertTrue(sha1.equals(r.cmd("git rev-list --no-walk --max-count=1 HEAD")));
    }

    public void test_fetch_with_updated_tag() throws Exception {
        WorkingArea r = new WorkingArea();
        r.init();
        r.commitEmpty("init");
        r.tag("t");
        String sha1 = r.cmd("git rev-list --no-walk --max-count=1 t");

        w.init();
        w.cmd("git remote add origin " + r.repoPath());
        w.git.fetch("origin", new RefSpec[] {null});
        assertTrue(sha1.equals(r.cmd("git rev-list --no-walk --max-count=1 t")));

        r.touch("file.txt");
        r.git.add("file.txt");
        r.git.commit("update");
        r.tag("-d t");
        r.tag("t");
        sha1 = r.cmd("git rev-list --no-walk --max-count=1 t");
        w.git.fetch("origin", new RefSpec[] {null});
        assertTrue(sha1.equals(r.cmd("git rev-list --max-count=1 t")));

    }

    public void test_fetch_shallow() throws Exception {
        w.init();
        w.git.setRemoteUrl("origin", localMirror());
        w.git.fetch_().from(new URIish("origin"), Collections.singletonList(new RefSpec("refs/heads/*:refs/remotes/origin/*"))).shallow(true).execute();
        check_remote_url("origin");
        assertBranchesExist(w.git.getRemoteBranches(), "origin/master");
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        assertFalse("Alternates file found: " + alternates, w.exists(alternates));
        /* JGit does not support shallow clone */
        final String shallow = ".git" + File.separator + "shallow";
        assertEquals("Shallow file: " + shallow, w.igit() instanceof CliGitAPIImpl, w.exists(shallow));
    }

    public void test_fetch_shallow_depth() throws Exception {
        w.init();
        w.git.setRemoteUrl("origin", localMirror());
        w.git.fetch_().from(new URIish("origin"), Collections.singletonList(new RefSpec("refs/heads/*:refs/remotes/origin/*"))).shallow(true).depth(2).execute();
        check_remote_url("origin");
        assertBranchesExist(w.git.getRemoteBranches(), "origin/master");
        final String alternates = ".git" + File.separator + "objects" + File.separator + "info" + File.separator + "alternates";
        assertFalse("Alternates file found: " + alternates, w.exists(alternates));
        /* JGit does not support shallow clone */
        final String shallow = ".git" + File.separator + "shallow";
        assertEquals("Shallow file: " + shallow, w.igit() instanceof CliGitAPIImpl, w.exists(shallow));
    }

    public void test_fetch_noTags() throws Exception {
        w.init();
        w.git.setRemoteUrl("origin", localMirror());
        w.git.fetch_().from(new URIish("origin"), Collections.singletonList(new RefSpec("refs/heads/*:refs/remotes/origin/*"))).tags(false).execute();
        check_remote_url("origin");
        assertBranchesExist(w.git.getRemoteBranches(), "origin/master");
        Set<String> tags = w.git.getTagNames("");
        assertTrue("Tags have been found : " + tags, tags.isEmpty());
    }

    @Bug(37794)
    public void test_getTagNames_supports_slashes_in_tag_names() throws Exception {
        w.init();
        w.commitEmpty("init-getTagNames-supports-slashes");
        w.git.tag("no-slash", "Tag without a /");
        Set<String> tags = w.git.getTagNames(null);
        assertThat(tags, hasItem("no-slash"));
        assertThat(tags, not(hasItem("slashed/sample")));
        assertThat(tags, not(hasItem("slashed/sample-with-short-comment")));

        w.git.tag("slashed/sample", "Tag slashed/sample includes a /");
        w.git.tag("slashed/sample-with-short-comment", "short comment");

        for (String matchPattern : Arrays.asList("n*", "no-*", "*-slash", "*/sl*sa*", "*/sl*/sa*")) {
            Set<String> latestTags = w.git.getTagNames(matchPattern);
            assertThat(tags, hasItem("no-slash"));
            assertThat(latestTags, not(hasItem("slashed/sample")));
            assertThat(latestTags, not(hasItem("slashed/sample-with-short-comment")));
        }

        for (String matchPattern : Arrays.asList("s*", "slashed*", "sl*sa*", "slashed/*", "sl*/sa*", "slashed/sa*")) {
            Set<String> latestTags = w.git.getTagNames(matchPattern);
            assertThat(latestTags, hasItem("slashed/sample"));
            assertThat(latestTags, hasItem("slashed/sample-with-short-comment"));
        }
    }

    public void test_empty_comment() throws Exception {
        w.init();
        w.commitEmpty("init-empty-comment-to-tag-fails-on-windows");
        if (isWindows()) {
            w.git.tag("non-empty-comment", "empty-tag-comment-fails-on-windows");
        } else {
            w.git.tag("empty-comment", "");
        }
    }

    public void test_create_branch() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.git.branch("test");
        String branches = w.cmd("git branch -l");
        assertTrue("master branch not listed", branches.contains("master"));
        assertTrue("test branch not listed", branches.contains("test"));
    }

    @Bug(34309)
    public void test_list_branches() throws Exception {
        w.init();
        Set<Branch> branches = w.git.getBranches();
        assertEquals(0, branches.size()); // empty repo should have 0 branches
        w.commitEmpty("init");

        w.git.branch("test");
        w.touch("test-branch.txt");
        w.git.add("test-branch.txt");
        // JGit commit doesn't end commit message with Ctrl-M, even when passed
        final String testBranchCommitMessage = "test branch commit ends in Ctrl-M";
        w.jgit().commit(testBranchCommitMessage + "\r");

        w.git.branch("another");
        w.touch("another-branch.txt");
        w.git.add("another-branch.txt");
        // CliGit commit doesn't end commit message with Ctrl-M, even when passed
        final String anotherBranchCommitMessage = "test branch commit ends in Ctrl-M";
        w.cgit().commit(anotherBranchCommitMessage + "\r");

        branches = w.git.getBranches();
        assertBranchesExist(branches, "master", "test", "another");
        assertEquals(3, branches.size());
        String output = w.cmd("git branch -v --no-abbrev");
        assertTrue("git branch -v --no-abbrev missing test commit msg: '" + output + "'", output.contains(testBranchCommitMessage));
        assertTrue("git branch -v --no-abbrev missing another commit msg: '" + output + "'", output.contains(anotherBranchCommitMessage));
        if (w.cgit().isAtLeastVersion(2, 13, 0, 0)) {
            assertTrue("git branch -v --no-abbrev missing Ctrl-M: '" + output + "'", output.contains("\r"));
            assertTrue("git branch -v --no-abbrev missing test commit msg Ctrl-M: '" + output + "'", output.contains(testBranchCommitMessage + "\r"));
            assertTrue("git branch -v --no-abbrev missing another commit msg Ctrl-M: '" + output + "'", output.contains(anotherBranchCommitMessage + "\r"));
        } else {
            assertFalse("git branch -v --no-abbrev contains Ctrl-M: '" + output + "'", output.contains("\r"));
            assertFalse("git branch -v --no-abbrev contains test commit msg Ctrl-M: '" + output + "'", output.contains(testBranchCommitMessage + "\r"));
            assertFalse("git branch -v --no-abbrev contains another commit msg Ctrl-M: '" + output + "'", output.contains(anotherBranchCommitMessage + "\r"));
        }
    }

    public void test_list_remote_branches() throws Exception {
        WorkingArea r = new WorkingArea();
        r.init();
        r.commitEmpty("init");
        r.git.branch("test");
        r.git.branch("another");

        w.init();
        w.cmd("git remote add origin " + r.repoPath());
        w.cmd("git fetch origin");
        Set<Branch> branches = w.git.getRemoteBranches();
        assertBranchesExist(branches, "origin/master", "origin/test", "origin/another");
        assertEquals(3, branches.size());
    }

    public void test_remote_list_tags_with_filter() throws Exception {
        WorkingArea r = new WorkingArea();
        r.init();
        r.commitEmpty("init");
        r.tag("test");
        r.tag("another_test");
        r.tag("yet_another");

        w.init();
        w.cmd("git remote add origin " + r.repoPath());
        w.cmd("git fetch origin");
        Set<String> local_tags = w.git.getTagNames("*test");
        Set<String> tags = w.git.getRemoteTagNames("*test");
        assertTrue("expected tag test not listed", tags.contains("test"));
        assertTrue("expected tag another_test not listed", tags.contains("another_test"));
        assertFalse("unexpected yet_another tag listed", tags.contains("yet_another"));
    }

    public void test_remote_list_tags_without_filter() throws Exception {
        WorkingArea r = new WorkingArea();
        r.init();
        r.commitEmpty("init");
        r.tag("test");
        r.tag("another_test");
        r.tag("yet_another");

        w.init();
        w.cmd("git remote add origin " + r.repoPath());
        w.cmd("git fetch origin");
        Set<String> allTags = w.git.getRemoteTagNames(null);
        assertTrue("tag 'test' not listed", allTags.contains("test"));
        assertTrue("tag 'another_test' not listed", allTags.contains("another_test"));
        assertTrue("tag 'yet_another' not listed", allTags.contains("yet_another"));
    }

    public void test_list_branches_containing_ref() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.git.branch("test");
        w.git.branch("another");
        Set<Branch> branches = w.git.getBranches();
        assertBranchesExist(branches, "master", "test", "another");
        assertEquals(3, branches.size());
    }

    public void test_delete_branch() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.git.branch("test");
        w.git.deleteBranch("test");
        String branches = w.cmd("git branch -l");
        assertFalse("deleted test branch still present", branches.contains("test"));
        try {
            w.git.deleteBranch("test");
            assertTrue("cgit did not throw an exception", w.git instanceof JGitAPIImpl);
        } catch (GitException ge) {
            assertEquals("Could not delete branch test", ge.getMessage());
        }
    }

    @Bug(23299)
    public void test_create_tag() throws Exception {
        w.init();
        String gitDir = w.repoPath() + File.separator + ".git";
        w.commitEmpty("init");
        ObjectId init = w.git.revParse("HEAD"); // Remember SHA1 of init commit
        w.git.tag("test", "this is a tag");

        /* JGit seems to have the better behavior in this case, always
         * returning the SHA1 of the commit. Most users are using
         * command line git, so the difference is retained in command
         * line git for compatibility with any legacy command line git
         * use cases which depend on returning the SHA-1 of the
         * annotated tag rather than the SHA-1 of the commit to which
         * the annotated tag points.
         */
        ObjectId testTag = w.git.getHeadRev(gitDir, "test"); // Remember SHA1 of annotated test tag
        if (w.git instanceof JGitAPIImpl) {
            assertEquals("Annotated tag does not match SHA1", init, testTag);
        } else {
            assertNotEquals("Annotated tag unexpectedly equals SHA1", init, testTag);
        }

        /* Because refs/tags/test syntax is more specific than "test",
         * and because the more specific syntax was only introduced in
         * more recent git client plugin versions (like 1.10.0 and
         * later), the CliGit and JGit behavior are kept the same here
         * in order to fix JENKINS-23299.
         */
        ObjectId testTagCommit = w.git.getHeadRev(gitDir, "refs/tags/test"); // SHA1 of commit identified by test tag
        assertEquals("Annotated tag doesn't match queried commit SHA1", init, testTagCommit);
        assertEquals(init, w.git.revParse("test")); // SHA1 of commit identified by test tag
        assertEquals(init, w.git.revParse("refs/tags/test")); // SHA1 of commit identified by test tag
        assertTrue("test tag not created", w.cmd("git tag").contains("test"));
        String message = w.cmd("git tag -l -n1");
        assertTrue("unexpected test tag message : " + message, message.contains("this is a tag"));
        assertNull(w.git.getHeadRev(gitDir, "not-a-valid-tag")); // Confirm invalid tag returns null
    }

    public void test_delete_tag() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.tag("test");
        w.tag("another");
        w.git.deleteTag("test");
        String tags = w.cmd("git tag");
        assertFalse("deleted test tag still present", tags.contains("test"));
        assertTrue("expected tag not listed", tags.contains("another"));
        try {
            w.git.deleteTag("test");
            assertTrue("cgit did not throw an exception", w.git instanceof JGitAPIImpl);
        } catch (GitException ge) {
            assertEquals("Could not delete tag test", ge.getMessage());
        }
    }

    public void test_list_tags_with_filter() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.tag("test");
        w.tag("another_test");
        w.tag("yet_another");
        Set<String> tags = w.git.getTagNames("*test");
        assertTrue("expected tag test not listed", tags.contains("test"));
        assertTrue("expected tag another_test not listed", tags.contains("another_test"));
        assertFalse("unexpected yet_another tag listed", tags.contains("yet_another"));
    }

    public void test_list_tags_without_filter() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.tag("test");
        w.tag("another_test");
        w.tag("yet_another");
        Set<String> allTags = w.git.getTagNames(null);
        assertTrue("tag 'test' not listed", allTags.contains("test"));
        assertTrue("tag 'another_test' not listed", allTags.contains("another_test"));
        assertTrue("tag 'yet_another' not listed", allTags.contains("yet_another"));
    }

    public void test_list_tags_star_filter() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.tag("test");
        w.tag("another_test");
        w.tag("yet_another");
        Set<String> allTags = w.git.getTagNames("*");
        assertTrue("tag 'test' not listed", allTags.contains("test"));
        assertTrue("tag 'another_test' not listed", allTags.contains("another_test"));
        assertTrue("tag 'yet_another' not listed", allTags.contains("yet_another"));
    }

    public void test_tag_exists() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.tag("test");
        assertTrue(w.git.tagExists("test"));
        assertFalse(w.git.tagExists("unknown"));
    }

    public void test_get_tag_message() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.tag("test -m this-is-a-test");
        assertEquals("this-is-a-test", w.git.getTagMessage("test"));
    }

    public void test_get_tag_message_multi_line() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.launchCommand("git", "tag", "test", "-m", "test 123!\n* multi-line tag message\n padded ");

        // Leading four spaces from each line should be stripped,
        // but not the explicit single space before "padded",
        // and the final errant space at the end should be trimmed
        assertEquals("test 123!\n* multi-line tag message\n padded", w.git.getTagMessage("test"));
    }

    public void test_create_ref() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.git.ref("refs/testing/testref");
        assertTrue("test ref not created", w.cmd("git show-ref").contains("refs/testing/testref"));
    }

    public void test_delete_ref() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.git.ref("refs/testing/testref");
        w.git.ref("refs/testing/anotherref");
        w.git.deleteRef("refs/testing/testref");
        String refs = w.cmd("git show-ref");
        assertFalse("deleted test tag still present", refs.contains("refs/testing/testref"));
        assertTrue("expected tag not listed", refs.contains("refs/testing/anotherref"));
        w.git.deleteRef("refs/testing/testref");  // Double-deletes do nothing.
    }

    public void test_list_refs_with_prefix() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.git.ref("refs/testing/testref");
        w.git.ref("refs/testing/nested/anotherref");
        w.git.ref("refs/testing/nested/yetanotherref");
        Set<String> refs = w.git.getRefNames("refs/testing/nested/");
        assertFalse("ref testref listed", refs.contains("refs/testing/testref"));
        assertTrue("ref anotherref not listed", refs.contains("refs/testing/nested/anotherref"));
        assertTrue("ref yetanotherref not listed", refs.contains("refs/testing/nested/yetanotherref"));
    }

    public void test_list_refs_without_prefix() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.git.ref("refs/testing/testref");
        w.git.ref("refs/testing/nested/anotherref");
        w.git.ref("refs/testing/nested/yetanotherref");
        Set<String> allRefs = w.git.getRefNames("");
        assertTrue("ref testref not listed", allRefs.contains("refs/testing/testref"));
        assertTrue("ref anotherref not listed", allRefs.contains("refs/testing/nested/anotherref"));
        assertTrue("ref yetanotherref not listed", allRefs.contains("refs/testing/nested/yetanotherref"));
    }

    public void test_ref_exists() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.git.ref("refs/testing/testref");
        assertTrue(w.git.refExists("refs/testing/testref"));
        assertFalse(w.git.refExists("refs/testing/testref_notfound"));
        assertFalse(w.git.refExists("refs/testing2/yetanother"));
    }

    public void test_revparse_sha1_HEAD_or_tag() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.touch("file1");
        w.git.add("file1");
        w.git.commit("commit1");
        w.tag("test");
        String sha1 = w.cmd("git rev-parse HEAD").substring(0,40);
        assertEquals(sha1, w.git.revParse(sha1).name());
        assertEquals(sha1, w.git.revParse("HEAD").name());
        assertEquals(sha1, w.git.revParse("test").name());
    }

    public void test_revparse_throws_expected_exception() throws Exception {
        w.init();
        w.commitEmpty("init");
        try {
            w.git.revParse("unknown-rev-to-parse");
            fail("Did not throw exception");
        } catch (GitException ge) {
            final String msg = ge.getMessage();
            assertTrue("Wrong exception: " + msg, msg.contains("unknown-rev-to-parse"));
        }
    }

    public void test_hasGitRepo_without_git_directory() throws Exception
    {
        setTimeoutVisibleInCurrentTest(false);
        assertFalse("Empty directory has a Git repo", w.git.hasGitRepo());
    }

    public void test_hasGitRepo_with_invalid_git_repo() throws Exception
    {
        // Create an empty directory named .git - "corrupt" git repo
        assertTrue("mkdir .git failed", w.file(".git").mkdir());
        assertFalse("Invalid Git repo reported as valid", w.git.hasGitRepo());
    }

    public void test_hasGitRepo_with_valid_git_repo() throws Exception {
        w.init();
        assertTrue("Valid Git repo reported as invalid", w.git.hasGitRepo());
    }

    public void test_push() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.touch("file1");
        w.git.add("file1");
        w.git.commit("commit1");
        ObjectId sha1 = w.head();

        WorkingArea r = new WorkingArea();
        r.init(true);
        w.cmd("git remote add origin " + r.repoPath());

        w.git.push("origin", "master");
        String remoteSha1 = r.cmd("git rev-parse master").substring(0, 40);
        assertEquals(sha1.name(), remoteSha1);
    }

    @Deprecated
    public void test_push_deprecated_signature() throws Exception {
        /* Make working repo a remote of the bare repo */
        w.init();
        w.commitEmpty("init");
        ObjectId workHead = w.head();

        /* Create a bare repo */
        WorkingArea bare = new WorkingArea();
        bare.init(true);

        /* Set working repo origin to point to bare */
        w.git.setRemoteUrl("origin", bare.repoPath());
        assertEquals("Wrong remote URL", w.git.getRemoteUrl("origin"), bare.repoPath());

        /* Push to bare repo */
        w.git.push("origin", "master");
        /* JGitAPIImpl revParse fails unexpectedly when used here */
        ObjectId bareHead = w.git instanceof CliGitAPIImpl ? bare.head() : ObjectId.fromString(bare.cmd("git rev-parse master").substring(0, 40));
        assertEquals("Heads don't match", workHead, bareHead);
        assertEquals("Heads don't match", w.git.getHeadRev(w.repoPath(), "master"), bare.git.getHeadRev(bare.repoPath(), "master"));

        /* Commit a new file */
        w.touch("file1");
        w.git.add("file1");
        w.git.commit("commit1");

        /* Push commit to the bare repo */
        Config config = new Config();
        config.fromText(w.contentOf(".git/config"));
        RemoteConfig origin = new RemoteConfig(config, "origin");
        w.igit().push(origin, "master");

        /* JGitAPIImpl revParse fails unexpectedly when used here */
        ObjectId workHead2 = w.git instanceof CliGitAPIImpl ? w.head() : ObjectId.fromString(w.cmd("git rev-parse master").substring(0, 40));
        ObjectId bareHead2 = w.git instanceof CliGitAPIImpl ? bare.head() : ObjectId.fromString(bare.cmd("git rev-parse master").substring(0, 40));
        assertEquals("Working SHA1 != bare SHA1", workHead2, bareHead2);
        assertEquals("Working SHA1 != bare SHA1", w.git.getHeadRev(w.repoPath(), "master"), bare.git.getHeadRev(bare.repoPath(), "master"));
    }

    @NotImplementedInJGit
    public void test_push_from_shallow_clone() throws Exception {
        WorkingArea r = new WorkingArea();
        r.init();
        r.commitEmpty("init");
        r.touch("file1");
        r.git.add("file1");
        r.git.commit("commit1");
        r.cmd("git checkout -b other");

        w.init();
        w.cmd("git remote add origin " + r.repoPath());
        w.cmd("git pull --depth=1 origin master");

        w.touch("file2");
        w.git.add("file2");
        w.git.commit("commit2");
        ObjectId sha1 = w.head();

        try {
            w.git.push("origin", "master");
            assertTrue("git < 1.9.0 can push from shallow repository", w.cgit().isAtLeastVersion(1, 9, 0, 0));
            String remoteSha1 = r.cmd("git rev-parse master").substring(0, 40);
            assertEquals(sha1.name(), remoteSha1);
        } catch (GitException e) {
            // expected for git cli < 1.9.0
            assertTrue("Wrong exception message: " + e, e.getMessage().contains("push from shallow repository"));
            assertFalse("git >= 1.9.0 can't push from shallow repository", w.cgit().isAtLeastVersion(1, 9, 0, 0));
        }
    }

    public void test_notes_add_first_note() throws Exception {
        w.init();
        w.touch("file1");
        w.git.add("file1");
        w.commitEmpty("init");

        w.git.addNote("foo", "commits");
        assertEquals("foo\n", w.cmd("git notes show"));
        w.git.appendNote("alpha\rbravo\r\ncharlie\r\n\r\nbar\n\n\nzot\n\n", "commits");
        // cgit normalizes CR+LF aggressively
        // it appears to be collpasing CR+LF to LF, then truncating duplicate LFs down to 2
        // note that CR itself is left as is
        assertEquals("foo\n\nalpha\rbravo\ncharlie\n\nbar\n\nzot\n", w.cmd("git notes show"));
    }

    public void test_notes_append_first_note() throws Exception {
        w.init();
        w.touch("file1");
        w.git.add("file1");
        w.commitEmpty("init");

        w.git.appendNote("foo", "commits");
        assertEquals("foo\n", w.cmd("git notes show"));
        w.git.appendNote("alpha\rbravo\r\ncharlie\r\n\r\nbar\n\n\nzot\n\n", "commits");
        // cgit normalizes CR+LF aggressively
        // it appears to be collpasing CR+LF to LF, then truncating duplicate LFs down to 2
        // note that CR itself is left as is
        assertEquals("foo\n\nalpha\rbravo\ncharlie\n\nbar\n\nzot\n", w.cmd("git notes show"));
    }

    /**
     * A rev-parse warning message should not break revision parsing.
     */
    @Bug(11177)
    public void test_jenkins_11177() throws Exception
    {
        w.init();
        w.commitEmpty("init");
        ObjectId base = w.head();
        ObjectId master = w.git.revParse("master");
        assertEquals(base, master);

        /* Make reference to master ambiguous, verify it is reported ambiguous by rev-parse */
        w.tag("master"); // ref "master" is now ambiguous
        String revParse = w.cmd("git rev-parse master");
        assertTrue("'" + revParse + "' does not contain 'ambiguous'", revParse.contains("ambiguous"));
        ObjectId masterTag = w.git.revParse("refs/tags/master");
        assertEquals("masterTag != head", w.head(), masterTag);

        /* Get reference to ambiguous master */
        ObjectId ambiguous = w.git.revParse("master");
        assertEquals("ambiguous != master", ambiguous.toString(), master.toString());

        /* Exploring JENKINS-20991 ambigous revision breaks checkout */
        w.touch("file-master", "content-master");
        w.git.add("file-master");
        w.git.commit("commit1-master");
        final ObjectId masterTip = w.head();

        w.cmd("git branch branch1 " + masterTip.name());
        w.cmd("git checkout branch1");
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1-branch1");
        final ObjectId branch1 = w.head();

        /* JGit checks out the masterTag, while CliGit checks out
         * master branch.  It is risky that there are different
         * behaviors between the two implementations, but when a
         * reference is ambiguous, it is safe to assume that
         * resolution of the ambiguous reference is an implementation
         * specific detail. */
        w.git.checkout("master");
        String messageDetails =
            ", head=" + w.head().name() +
            ", masterTip=" + masterTip.name() +
            ", masterTag=" + masterTag.name() +
            ", branch1=" + branch1.name();
        if (w.git instanceof CliGitAPIImpl) {
            assertEquals("head != master branch" + messageDetails, masterTip, w.head());
        } else {
            assertEquals("head != master tag" + messageDetails, masterTag, w.head());
        }
    }

    /**
     * Command line git clean as implemented in CliGitAPIImpl does not remove
     * untracked submodules or files contained in untracked submodule dirs.
     * JGit clean as implemented in JGitAPIImpl removes untracked submodules.
     * This test captures that surprising difference between the implementations.
     *
     * Command line git as implemented in CliGitAPIImpl supports renamed submodules.
     * JGit as implemented in JGitAPIImpl does not support renamed submodules.
     * This test captures that surprising difference between the implementations.
     *
     * This test really should be split into multiple tests.
     * Current transitions in the test include:
     *   with submodules -> without submodules, with files/dirs of same name
     *   with submodules -> without submodules, no files/dirs of same name
     *
     * See bug reports such as:
     * JENKINS-22510 - Clean After Checkout Results in Failed to Checkout Revision
     * JENKINS-8053  - Git submodules are cloned too early and not removed once the revToBuild has been checked out
     * JENKINS-14083 - Build can't recover from broken submodule path
     * JENKINS-15399 - Changing remote URL doesn't update submodules
     *
     * @throws Exception on test failure
     */
    public void test_submodule_checkout_and_clean_transitions() throws Exception {
        w = clone(localMirror());
        assertSubmoduleDirs(w.repo, false, false);

        String subBranch = "tests/getSubmodules";
        String subRefName = "origin/" + subBranch;
        String ntpDirName = "modules/ntp";
        String contributingFileName = "modules/ntp/CONTRIBUTING.md";
        String contributingFileContent = "Puppet Labs modules on the Puppet Forge are open projects";

        File modulesDir = new File(w.repo, "modules");
        assertDirNotFound(modulesDir);

        File keeperFile = new File(modulesDir, "keeper");
        assertFileNotFound(keeperFile);

        File ntpDir = new File(modulesDir, "ntp");
        File ntpContributingFile = new File(ntpDir, "CONTRIBUTING.md");
        assertDirNotFound(ntpDir);
        assertFileNotFound(ntpContributingFile);

        File firewallDir = new File(modulesDir, "firewall");
        assertDirNotFound(firewallDir);

        File sshkeysDir = new File(modulesDir, "sshkeys");
        File sshkeysModuleFile = new File(sshkeysDir, "Modulefile");
        assertDirNotFound(sshkeysDir);
        assertFileNotFound(sshkeysModuleFile);

        /* Checkout a branch which includes submodules (in modules directory) */
        w.git.checkout().ref(subRefName).branch(subBranch).execute();
        assertDirExists(modulesDir);
        assertFileExists(keeperFile);
        assertFileContents(keeperFile, "");
        /* Command line git checkout creates empty directories for modules, JGit does not */
        /* That behavioral difference seems harmless */
        if (w.git instanceof CliGitAPIImpl) {
            assertSubmoduleDirs(w.repo, true, false);
        } else {
            assertDirNotFound(ntpDir);
            assertDirNotFound(firewallDir);
            assertDirNotFound(sshkeysDir);
            assertFileNotFound(ntpContributingFile);
            assertFileNotFound(sshkeysModuleFile);
        }

        /* Call submodule update without recursion */
        w.git.submoduleUpdate().recursive(false).execute();
        /* Command line git supports renamed submodule dirs, JGit does not */
        /* JGit silently fails submodule updates on renamed submodule dirs */
        if (w.git instanceof CliGitAPIImpl) {
            assertSubmoduleDirs(w.repo, true, true);
            assertSubmoduleContents(w.repo);
            assertSubmoduleRepository(new File(w.repo, "modules/ntp"));
            assertSubmoduleRepository(new File(w.repo, "modules/firewall"));
            assertSubmoduleRepository(new File(w.repo, "modules/sshkeys"));
        } else {
            assertDirNotFound(ntpDir);
            assertDirNotFound(firewallDir);
            assertDirNotFound(sshkeysDir);
        }

        /* Call submodule update with recursion */
        w.git.submoduleUpdate().recursive(true).execute();
        /* Command line git supports renamed submodule dirs, JGit does not */
        /* JGit silently fails submodule updates on renamed submodule dirs */
        if (w.git instanceof CliGitAPIImpl) {
            assertSubmoduleDirs(w.repo, true, true);
            assertSubmoduleContents(w.repo);
            assertSubmoduleRepository(new File(w.repo, "modules/ntp"));
            assertSubmoduleRepository(new File(w.repo, "modules/firewall"));
            assertSubmoduleRepository(new File(w.repo, "modules/sshkeys"));
        } else {
            assertDirNotFound(ntpDir);
            assertDirNotFound(firewallDir);
            assertDirNotFound(sshkeysDir);
        }

        String notSubBranchName = "tests/notSubmodules";
        String notSubRefName = "origin/" + notSubBranchName;
        String contributingFileContentFromNonsubmoduleBranch = "This is not a useful contribution";

        /* Checkout a detached head which does not include submodules,
         * since checkout of a branch does not currently use the "-f"
         * option (though it probably should).  The checkout includes a file
         * modules/ntp/CONTRIBUTING.md which collides with a file from the
         * submodule but is provided from the repository rather than from a
         * submodule.
         */
        // w.git.checkout().ref(notSubRefName).execute();
        w.git.checkout().ref(notSubRefName).branch(notSubBranchName).deleteBranchIfExist(true).execute();
        assertDirExists(ntpDir);
        assertFileExists(ntpContributingFile);
        assertFileContains(ntpContributingFile, contributingFileContentFromNonsubmoduleBranch);
        if (w.git instanceof CliGitAPIImpl) {
            /* submodule dirs exist because git.clean() won't remove untracked submodules */
            assertDirExists(firewallDir);
            assertDirExists(sshkeysDir);
            assertFileExists(sshkeysModuleFile);
        } else {
            /* firewallDir and sshKeysDir don't exist because JGit submodule update never created them */
            assertDirNotFound(firewallDir);
            assertDirNotFound(sshkeysDir);
        }

        /* CLI git clean does not remove submodule remnants, JGit does */
        w.git.clean();
        assertDirExists(ntpDir);
        assertFileExists(ntpContributingFile); /* exists in nonSubmodule branch */
        if (w.git instanceof CliGitAPIImpl) {
            /* untracked - CLI clean doesn't remove submodule dirs or their contents */
            assertDirExists(firewallDir);
            assertDirExists(sshkeysDir);
            assertFileExists(sshkeysModuleFile);
        } else {
            /* JGit clean removes submodule dirs*/
            assertDirNotFound(firewallDir);
            assertDirNotFound(sshkeysDir);
        }

        /* Checkout master branch - will leave submodule files untracked */
        w.git.checkout().ref("origin/master").execute();
        // w.git.checkout().ref("origin/master").branch("master").execute();
        if (w.git instanceof CliGitAPIImpl) {
            /* CLI git clean will not remove untracked submodules */
            assertDirExists(ntpDir);
            assertDirExists(firewallDir);
            assertDirExists(sshkeysDir);
            assertFileNotFound(ntpContributingFile); /* cleaned because it is in tests/notSubmodules branch */
            assertFileExists(sshkeysModuleFile);
        } else {
            /* JGit git clean removes them */
            assertDirNotFound(ntpDir);
            assertDirNotFound(firewallDir);
            assertDirNotFound(sshkeysDir);
        }

        /* git.clean() does not remove submodule remnants in CliGitAPIImpl, does in JGitAPIImpl */
        w.git.clean();
        if (w.git instanceof CliGitAPIImpl && w.cgit().isAtLeastVersion(1, 7, 9, 0)) {
            assertDirExists(ntpDir);
            assertDirExists(firewallDir);
            assertDirExists(sshkeysDir);
        } else {
            assertDirNotFound(ntpDir);
            assertDirNotFound(firewallDir);
            assertDirNotFound(sshkeysDir);
        }

        /* Really remove submodule remnant, use git command line double force */
        if (w.git instanceof CliGitAPIImpl) {
            if (!isWindows()) {
                w.cmd("git clean -xffd");
            } else {
                try {
                    w.cmd("git clean -xffd");
                } catch (Exception e) {
                    /* Retry once (and only once) in case of Windows busy file behavior */
                    Thread.sleep(503); /* Wait 0.5 seconds for Windows */
                    w.cmd("git clean -xffd");
                }
            }
        }
        assertSubmoduleDirs(w.repo, false, false);

        /* Checkout a branch which *includes submodules* after a prior
         * checkout with a file which has the same name as a file
         * provided by a submodule checkout.  Use a detached head,
         * since checkout of a branch does not currently use the "-f"
         * option.
         */
        assertEquals(ObjectId.fromString("a6dd186704985fdb0c60e60f5c6ea7ea35e082e5"), w.git.revParse(subRefName));
        // w.git.checkout().ref(subRefName).branch(subBranch).execute();
        w.git.checkout().ref(subRefName).execute();
        assertDirExists(modulesDir);
        if (w.git instanceof CliGitAPIImpl) {
            assertSubmoduleDirs(w.repo, true, false);
        } else {
            /* JGit does not support renamed submodules - creates none of the directories */
            assertDirNotFound(ntpDir);
            assertDirNotFound(firewallDir);
            assertDirNotFound(sshkeysDir);
        }

        w.git.submoduleClean(true);
        assertSubmoduleDirs(w.repo, true, false);

        if (w.git instanceof JGitAPIImpl) {
            /* submoduleUpdate().recursive(true).execute() throws an exception */
            /* Call setupSubmoduleUrls to assure it throws expected exception */
            try {
                Revision nullRevision = null;
                w.igit().setupSubmoduleUrls(nullRevision, listener);
            } catch (UnsupportedOperationException uoe) {
                assertTrue("Unsupported operation not on JGit", w.igit() instanceof JGitAPIImpl);
            }
            return;
        }
        w.git.submoduleUpdate().recursive(true).execute();
        assertSubmoduleDirs(w.repo, true, true);
        assertSubmoduleContents(w.repo);
        assertSubmoduleRepository(new File(w.repo, "modules/ntp"));
        assertSubmoduleRepository(new File(w.repo, "modules/firewall"));

        if (w.git instanceof CliGitAPIImpl) {
            // This is a low value section of the test. Does not assert anything
            // about the result of setupSubmoduleUrls
            ObjectId headId = w.git.revParse("HEAD");
            List<Branch> branches = new ArrayList<>();
            branches.add(new Branch("HEAD", headId));
            branches.add(new Branch(subRefName, headId));
            Revision head = new Revision(headId, branches);
            w.cgit().setupSubmoduleUrls(head, listener);
            assertSubmoduleDirs(w.repo, true, true);
            assertSubmoduleContents(w.repo);
        }
    }

    /* Submodule checkout in JGit does not support renamed submodules.
     * The test branch intentionally includes a renamed submodule, so this test
     * is not run with JGit.
     */
    @NotImplementedInJGit
    public void test_submodule_checkout_simple() throws Exception {
        w = clone(localMirror());
        assertSubmoduleDirs(w.repo, false, false);

        /* Checkout a branch which includes submodules (in modules directory) */
        String subBranch = "tests/getSubmodules";
        String subRefName = "origin/" + subBranch;
        w.git.checkout().ref(subRefName).branch(subBranch).execute();
        assertSubmoduleDirs(w.repo, true, false);

        w.git.submoduleUpdate().recursive(true).execute();
        assertSubmoduleDirs(w.repo, true, true);
        assertSubmoduleContents(w.repo);
        assertSubmoduleRepository(new File(w.repo, "modules/ntp"));
        assertSubmoduleRepository(new File(w.repo, "modules/firewall"));
        assertSubmoduleRepository(new File(w.repo, "modules/sshkeys"));
    }

    /* Opening a git repository in a directory with a symbolic git file instead
     * of a git directory should function properly.
     */
    public void test_with_repository_works_with_submodule() throws Exception {
        w = clone(localMirror());
        assertSubmoduleDirs(w.repo, false, false);

        /* Checkout a branch which includes submodules (in modules directory) */
        String subBranch = w.git instanceof CliGitAPIImpl ? "tests/getSubmodules" : "tests/getSubmodules-jgit";
        String subRefName = "origin/" + subBranch;
        w.git.checkout().ref(subRefName).branch(subBranch).execute();
        w.git.submoduleInit();
        w.git.submoduleUpdate().recursive(true).execute();
        assertSubmoduleRepository(new File(w.repo, "modules/ntp"));
        assertSubmoduleRepository(new File(w.repo, "modules/firewall"));
    }

    private void assertSubmoduleRepository(File submoduleDir) throws Exception {
        /* Get a client directly on the submoduleDir */
        GitClient submoduleClient = setupGitAPI(submoduleDir);

        /* Assert that when we invoke the repository callback it gets a
         * functioning repository object
         */
        submoduleClient.withRepository(new RepositoryCallback<Void>() {
            public Void invoke(final Repository repo, VirtualChannel channel) throws IOException, InterruptedException {
                assertTrue(repo.getDirectory() + " is not a valid repository",
                           repo.getObjectDatabase().exists());
                return null;
            }});
    }

    private String listDir(File dir) {
        if (dir == null || !dir.exists()) {
            return "";
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return "";
        }
        StringBuilder fileList = new StringBuilder();
        for (File file : files) {
            fileList.append(file.getName());
            fileList.append(',');
        }
        if (fileList.length() > 0) {
            fileList.deleteCharAt(fileList.length() - 1);
        }
        return fileList.toString();
    }

    private void assertFileExists(File file) {
        assertTrue(file + " not found, peer files: " + listDir(file.getParentFile()), file.exists());
    }

    private void assertFileNotFound(File file) {
        assertFalse(file + " found, peer files: " + listDir(file.getParentFile()), file.exists());
    }

    private void assertDirExists(File dir) {
        assertFileExists(dir);
        assertTrue(dir + " is not a directory", dir.isDirectory());
    }

    private void assertDirNotFound(File dir) {
        assertFileNotFound(dir);
    }

    private void assertFileContains(File file, String expectedContent) throws IOException {
        assertFileExists(file);
        final String fileContent = FileUtils.readFileToString(file, "UTF-8");
        final String message = file + " does not contain '" + expectedContent + "', contains '" + fileContent + "'";
        assertTrue(message, fileContent.contains(expectedContent));
    }

    private void assertFileContents(File file, String expectedContent) throws IOException {
        assertFileExists(file);
        final String fileContent = FileUtils.readFileToString(file, "UTF-8");
        assertEquals(file + " wrong content", expectedContent, fileContent);
    }

    private void assertSubmoduleDirs(File repo, boolean dirsShouldExist, boolean filesShouldExist) throws IOException {
        final File modulesDir = new File(w.repo, "modules");
        final File ntpDir = new File(modulesDir, "ntp");
        final File firewallDir = new File(modulesDir, "firewall");
        final File keeperFile = new File(modulesDir, "keeper");
        final File ntpContributingFile = new File(ntpDir, "CONTRIBUTING.md");
        final File sshkeysDir = new File(modulesDir, "sshkeys");
        final File sshkeysModuleFile = new File(sshkeysDir, "Modulefile");
        if (dirsShouldExist) {
            assertDirExists(modulesDir);
            assertDirExists(ntpDir);
            assertDirExists(firewallDir);
            assertDirExists(sshkeysDir);
            /* keeperFile is in the submodules branch, but is a plain file */
            assertFileExists(keeperFile);
        } else {
            assertDirNotFound(modulesDir);
            assertDirNotFound(ntpDir);
            assertDirNotFound(firewallDir);
            assertDirNotFound(sshkeysDir);
            /* keeperFile is in the submodules branch, but is a plain file */
            assertFileNotFound(keeperFile);
        }
        if (filesShouldExist) {
            assertFileExists(ntpContributingFile);
            assertFileExists(sshkeysModuleFile);
        } else {
            assertFileNotFound(ntpContributingFile);
            assertFileNotFound(sshkeysModuleFile);
        }
    }

    private void assertSubmoduleContents(File repo) throws IOException {
        final File modulesDir = new File(w.repo, "modules");

        final File sshkeysDir = new File(modulesDir, "sshkeys");
        final File sshkeysModuleFile = new File(sshkeysDir, "Modulefile");
        assertFileExists(sshkeysModuleFile);

        final File keeperFile = new File(modulesDir, "keeper");
        final String keeperContent = "";
        assertFileExists(keeperFile);
        assertFileContents(keeperFile, keeperContent);

        final File ntpDir = new File(modulesDir, "ntp");
        final File ntpContributingFile = new File(ntpDir, "CONTRIBUTING.md");
        final String ntpContributingContent = "Puppet Labs modules on the Puppet Forge are open projects";
        assertFileExists(ntpContributingFile);
        assertFileContains(ntpContributingFile, ntpContributingContent); /* Check substring in file */
    }

    public void test_no_submodules() throws IOException, InterruptedException {
        w.init();
        w.touch("committed-file", "committed-file content " + java.util.UUID.randomUUID().toString());
        w.git.add("committed-file");
        w.git.commit("commit1");
        w.igit().submoduleClean(false);
        w.igit().submoduleClean(true);
        w.igit().submoduleUpdate(false);
        w.igit().submoduleUpdate(true);
        w.igit().submoduleSync();
        assertTrue("committed-file missing at commit1", w.file("committed-file").exists());
    }

    public void assertFixSubmoduleUrlsThrows() throws InterruptedException {
        try {
            w.igit().fixSubmoduleUrls("origin", listener);
            fail("Expected exception not thrown");
        } catch (UnsupportedOperationException uoe) {
            assertTrue("Unsupported operation not on JGit", w.igit() instanceof JGitAPIImpl);
        } catch (GitException ge) {
            assertTrue("GitException not on CliGit", w.igit() instanceof CliGitAPIImpl);
            assertTrue("Wrong message in " + ge.getMessage(), ge.getMessage().startsWith("Could not determine remote"));
            assertTrue("Wrong remote in " + ge.getMessage(), ge.getMessage().contains("origin"));
        }
    }

    public void test_addSubmodule() throws Exception {
        String sub1 = "sub1-" + java.util.UUID.randomUUID().toString();
        String readme1 = sub1 + File.separator + "README.md";
        w.init();
        assertFalse("submodule1 dir found too soon", w.file(sub1).exists());
        assertFalse("submodule1 file found too soon", w.file(readme1).exists());

        w.git.addSubmodule(localMirror(), sub1);
        assertTrue("submodule1 dir not found after add", w.file(sub1).exists());
        assertTrue("submodule1 file not found after add", w.file(readme1).exists());

        w.igit().submoduleUpdate(false);
        assertTrue("submodule1 dir not found after add", w.file(sub1).exists());
        assertTrue("submodule1 file not found after add", w.file(readme1).exists());

        w.igit().submoduleUpdate(true);
        assertTrue("submodule1 dir not found after recursive update", w.file(sub1).exists());
        assertTrue("submodule1 file found after recursive update", w.file(readme1).exists());

        w.igit().submoduleSync();
        assertFixSubmoduleUrlsThrows();
    }

    @NotImplementedInJGit
    public void test_trackingSubmodule() throws Exception {
        if (! ((CliGitAPIImpl)w.git).isAtLeastVersion(1,8,2,0)) {
            System.err.println("git must be at least 1.8.2 to do tracking submodules.");
            return;
        }
        w.init(); // empty repository

        // create a new GIT repo.
        //   master -- <file1>C  <file2>C
        WorkingArea r = new WorkingArea();
        r.init();
        r.touch("file1", "content1");
        r.git.add("file1");
        r.git.commit("submod-commit1");

        // Add new GIT repo to w
        String subModDir = "submod1-" + java.util.UUID.randomUUID().toString();
        w.git.addSubmodule(r.repoPath(), subModDir);
        w.git.submoduleInit();

        // Add a new file to the separate GIT repo.
        r.touch("file2", "content2");
        r.git.add("file2");
        r.git.commit("submod-branch1-commit1");

        // Make sure that the new file doesn't exist in the repo with remoteTracking
        String subFile = subModDir + File.separator + "file2";
        w.git.submoduleUpdate(true, false);
        assertFalse("file2 exists and should not because we didn't update to the tip of the branch (master).", w.exists(subFile));

        // Run submodule update with remote tracking
        w.git.submoduleUpdate(true, true);
        assertTrue("file2 does not exist and should because we updated to the top of the branch (master).", w.exists(subFile));
        assertFixSubmoduleUrlsThrows();
    }

    /* Check JENKINS-23424 - inconsistent handling of modified tracked
     * files when performing a checkout in an existing directory.
     * CliGitAPIImpl reverts tracked files, while JGitAPIImpl does
     * not.
     */
    private void base_checkout_replaces_tracked_changes(boolean defineBranch) throws Exception {
        w.git.clone_().url(localMirror()).repositoryName("JENKINS-23424").execute();
        w.git.checkout("JENKINS-23424/master", "master");
        if (defineBranch) {
            w.git.checkout().branch("master").ref("JENKINS-23424/master").deleteBranchIfExist(true).execute();
        } else {
            w.git.checkout().ref("JENKINS-23424/master").deleteBranchIfExist(true).execute();
        }

        /* Confirm first checkout */
        String pomContent = w.contentOf("pom.xml");
        assertTrue("Missing inceptionYear ref in master pom : " + pomContent, pomContent.contains("inceptionYear"));
        assertFalse("Found untracked file", w.file("untracked-file").exists());

        /* Modify the pom file by adding a comment */
        String comment = " <!-- JENKINS-23424 comment -->";
        /* JGit implementation prior to 3.4.1 did not reset modified tracked files */
        w.touch("pom.xml", pomContent + comment);
        assertTrue(w.contentOf("pom.xml").contains(comment));

        /* Create an untracked file.  Both implementations retain
         * untracked files across checkout.
         */
        w.touch("untracked-file", comment);
        assertTrue("Missing untracked file", w.file("untracked-file").exists());

        /* Checkout should erase local modification */
        CheckoutCommand cmd = w.git.checkout().ref("JENKINS-23424/1.4.x").deleteBranchIfExist(true);
        if (defineBranch) {
            cmd.branch("1.4.x");
        }
        cmd.execute();

        /* Tracked file should not contain added comment, nor the inceptionYear reference */
        pomContent = w.contentOf("pom.xml");
        assertFalse("Found inceptionYear ref in 1.4.x pom : " + pomContent, pomContent.contains("inceptionYear"));
        assertFalse("Found comment in 1.4.x pom", pomContent.contains(comment));
        assertTrue("Missing untracked file", w.file("untracked-file").exists());
    }

    @Bug(23424)
    public void test_checkout_replaces_tracked_changes() throws Exception {
        base_checkout_replaces_tracked_changes(false);
    }

    @Bug(23424)
    public void test_checkout_replaces_tracked_changes_with_branch() throws Exception {
        base_checkout_replaces_tracked_changes(true);
    }

    /**
     * Confirm that JENKINS-8122 is fixed in the current
     * implementation.  That bug reported that the tags from a
     * submodule were being included in the set of tags associated
     * with the parent repository.  This test clones a repository with
     * submodules, updates those submodules, and compares the tags
     * available in the repository before the submodule branch
     * checkout, after the submodule branch checkout, and within one
     * of the submodules.
     */
    @Bug(8122)
    public void test_submodule_tags_not_fetched_into_parent() throws Exception {
        w.git.clone_().url(localMirror()).repositoryName("origin").execute();
        checkoutTimeout = 1 + random.nextInt(60 * 24);
        w.git.checkout().ref("origin/master").branch("master").timeout(checkoutTimeout).execute();

        String tagsBefore = w.cmd("git tag");
        Set<String> tagNamesBefore = w.git.getTagNames(null);
        for (String tag : tagNamesBefore) {
            assertTrue(tag + " not in " + tagsBefore, tagsBefore.contains(tag));
        }

        w.git.checkout().branch("tests/getSubmodules").ref("origin/tests/getSubmodules").timeout(checkoutTimeout).execute();
        w.git.submoduleUpdate().recursive(true).execute();

        String tagsAfter = w.cmd("git tag");
        Set<String> tagNamesAfter = w.git.getTagNames(null);
        for (String tag : tagNamesAfter) {
            assertTrue(tag + " not in " + tagsAfter, tagsAfter.contains(tag));
        }

        assertEquals("tags before != after", tagsBefore, tagsAfter);

        GitClient gitNtp = w.git.subGit("modules/ntp");
        Set<String> tagNamesSubmodule = gitNtp.getTagNames(null);
        for (String tag : tagNamesSubmodule) {
            assertFalse("Submodule tag " + tag + " in parent " + tagsAfter, tagsAfter.matches("^" + tag + "$"));
        }

        try {
            w.igit().fixSubmoduleUrls("origin", listener);
            assertTrue("not CliGit", w.igit() instanceof CliGitAPIImpl);
        } catch (UnsupportedOperationException uoe) {
            assertTrue("Unsupported operation not on JGit", w.igit() instanceof JGitAPIImpl);
        }
    }

    /* Shows the JGit submodule update is broken now that tests/getSubmodule includes a renamed submodule */
    public void test_getSubmodules() throws Exception {
        w.init();
        w.git.clone_().url(localMirror()).repositoryName("sub_origin").execute();
        w.git.checkout("sub_origin/tests/getSubmodules", "tests/getSubmodules");
        List<IndexEntry> r = w.git.getSubmodules("HEAD");
        assertEquals(
                "[IndexEntry[mode=160000,type=commit,file=modules/firewall,object=978c8b223b33e203a5c766ecf79704a5ea9b35c8], " +
                 "IndexEntry[mode=160000,type=commit,file=modules/ntp,object=b62fabbc2bb37908c44ded233e0f4bf479e45609], " +
                 "IndexEntry[mode=160000,type=commit,file=modules/sshkeys,object=689c45ed57f0829735f9a2b16760c14236fe21d9]]",
                r.toString()
        );
        w.git.submoduleInit();
        w.git.submoduleUpdate().execute();

        assertTrue("modules/firewall does not exist", w.exists("modules/firewall"));
        assertTrue("modules/ntp does not exist", w.exists("modules/ntp"));
        // JGit submodule implementation doesn't handle renamed submodules
        if (w.igit() instanceof CliGitAPIImpl) {
            assertTrue("modules/sshkeys does not exist", w.exists("modules/sshkeys"));
        }
        assertFixSubmoduleUrlsThrows();
    }

    /* Shows the submodule update is broken now that tests/getSubmodule includes a renamed submodule */
    @NotImplementedInJGit
    public void test_submodule_update() throws Exception {
        w.init();
        w.git.clone_().url(localMirror()).repositoryName("sub2_origin").execute();
        w.git.checkout().branch("tests/getSubmodules").ref("sub2_origin/tests/getSubmodules").deleteBranchIfExist(true).execute();
        w.git.submoduleInit();
        w.git.submoduleUpdate().execute();

        assertTrue("modules/firewall does not exist", w.exists("modules/firewall"));
        assertTrue("modules/ntp does not exist", w.exists("modules/ntp"));
        // JGit submodule implementation doesn't handle renamed submodules
        if (w.igit() instanceof CliGitAPIImpl) {
            assertTrue("modules/sshkeys does not exist", w.exists("modules/sshkeys"));
        }
        assertFixSubmoduleUrlsThrows();
    }

    @NotImplementedInJGit
    public void test_trackingSubmoduleBranches() throws Exception {
        if (! ((CliGitAPIImpl)w.git).isAtLeastVersion(1,8,2,0)) {
            setTimeoutVisibleInCurrentTest(false);
            System.err.println("git must be at least 1.8.2 to do tracking submodules.");
            return;
        }
        w.init(); // empty repository

        // create a new GIT repo.
        //    master  -- <file1>C
        //    branch1 -- <file1>C <file2>C
        //    branch2 -- <file1>C <file3>C
        WorkingArea r = new WorkingArea();
        r.init();
        r.touch("file1", "content1");
        r.git.add("file1");
        r.git.commit("submod-commit1");

        r.git.branch("branch1");
        r.git.checkout("branch1");
        r.touch("file2", "content2");
        r.git.add("file2");
        r.git.commit("submod-commit2");
        r.git.checkout("master");

        r.git.branch("branch2");
        r.git.checkout("branch2");
        r.touch("file3", "content3");
        r.git.add("file3");
        r.git.commit("submod-commit3");
        r.git.checkout("master");

        // Setup variables for use in tests
        String submodDir = "submod1" + java.util.UUID.randomUUID().toString();
        String subFile1 = submodDir + File.separator + "file1";
        String subFile2 = submodDir + File.separator + "file2";
        String subFile3 = submodDir + File.separator + "file3";

        // Add new GIT repo to w, at the master branch
        w.git.addSubmodule(r.repoPath(), submodDir);
        w.git.submoduleInit();
        assertTrue("file1 does not exist and should be we imported the submodule.", w.exists(subFile1));
        assertFalse("file2 exists and should not because not on 'branch1'", w.exists(subFile2));
        assertFalse("file3 exists and should not because not on 'branch2'", w.exists(subFile3));

        // Switch to branch1
        submoduleUpdateTimeout = 1 + random.nextInt(60 * 24);
        w.git.submoduleUpdate().remoteTracking(true).useBranch(submodDir, "branch1").timeout(submoduleUpdateTimeout).execute();
        assertTrue("file2 does not exist and should because on branch1", w.exists(subFile2));
        assertFalse("file3 exists and should not because not on 'branch2'", w.exists(subFile3));

        // Switch to branch2
        w.git.submoduleUpdate().remoteTracking(true).useBranch(submodDir, "branch2").timeout(submoduleUpdateTimeout).execute();
        assertFalse("file2 exists and should not because not on 'branch1'", w.exists(subFile2));
        assertTrue("file3 does not exist and should because on branch2", w.exists(subFile3));

        // Switch to master
        w.git.submoduleUpdate().remoteTracking(true).useBranch(submodDir, "master").timeout(submoduleUpdateTimeout).execute();
        assertFalse("file2 exists and should not because not on 'branch1'", w.exists(subFile2));
        assertFalse("file3 exists and should not because not on 'branch2'", w.exists(subFile3));
    }

    @NotImplementedInJGit
    public void test_sparse_checkout() throws Exception {
        /* Sparse checkout was added in git 1.7.0, but the checkout -f syntax
         * required by the plugin implementation does not work in git 1.7.1.
         */
        if (!w.cgit().isAtLeastVersion(1, 7, 9, 0)) {
            return;
        }
        // Create a repo for cloning purpose
        w.init();
        w.commitEmpty("init");
        assertTrue("mkdir dir1 failed", w.file("dir1").mkdir());
        w.touch("dir1/file1");
        assertTrue("mkdir dir2 failed", w.file("dir2").mkdir());
        w.touch("dir2/file2");
        assertTrue("mkdir dir3 failed", w.file("dir3").mkdir());
        w.touch("dir3/file3");
        w.git.add("dir1/file1");
        w.git.add("dir2/file2");
        w.git.add("dir3/file3");
        w.git.commit("commit");

        // Clone it
        WorkingArea workingArea = new WorkingArea();
        workingArea.git.clone_().url(w.repoPath()).execute();

        checkoutTimeout = 1 + random.nextInt(60 * 24);
        workingArea.git.checkout().ref("origin/master").branch("master").deleteBranchIfExist(true).sparseCheckoutPaths(Lists.newArrayList("dir1")).timeout(checkoutTimeout).execute();
        assertTrue(workingArea.exists("dir1"));
        assertFalse(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea.git.checkout().ref("origin/master").branch("master").deleteBranchIfExist(true).sparseCheckoutPaths(Lists.newArrayList("dir2")).timeout(checkoutTimeout).execute();
        assertFalse(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea.git.checkout().ref("origin/master").branch("master").deleteBranchIfExist(true).sparseCheckoutPaths(Lists.newArrayList("dir1", "dir2")).timeout(checkoutTimeout).execute();
        assertTrue(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea.git.checkout().ref("origin/master").branch("master").deleteBranchIfExist(true).sparseCheckoutPaths(Collections.<String>emptyList()).timeout(checkoutTimeout).execute();
        assertTrue(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertTrue(workingArea.exists("dir3"));

        workingArea.git.checkout().ref("origin/master").branch("master").deleteBranchIfExist(true).sparseCheckoutPaths(null)
            .timeout(checkoutTimeout)
            .execute();
        assertTrue(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertTrue(workingArea.exists("dir3"));
    }

    public void test_clone_no_checkout() throws Exception {
        // Create a repo for cloning purpose
        WorkingArea repoToClone = new WorkingArea();
        repoToClone.init();
        repoToClone.commitEmpty("init");
        repoToClone.touch("file1");
        repoToClone.git.add("file1");
        repoToClone.git.commit("commit");

        // Clone it with no checkout
        w.git.clone_().url(repoToClone.repoPath()).repositoryName("origin").noCheckout().execute();
        assertFalse(w.exists("file1"));
    }

    public void test_hasSubmodules() throws Exception {
        w.init();

        w.launchCommand("git", "fetch", localMirror(), "tests/getSubmodules:t");
        w.git.checkout("t");
        assertTrue(w.git.hasGitModules());

        w.launchCommand("git", "fetch", localMirror(), "master:t2");
        w.git.checkout("t2");
        assertFalse(w.git.hasGitModules());
        assertFixSubmoduleUrlsThrows();
    }

    private boolean isJava6() {
        if (System.getProperty("java.version").startsWith("1.6")) {
            return true;
        }
        return false;
    }

    /**
     * core.symlinks is set to false by msysgit on Windows and by JGit
     * 3.3.0 on all platforms.  It is not set on Linux.  Refer to
     * JENKINS-21168, JENKINS-22376, and JENKINS-22391 for details.
     */
    private void checkSymlinkSetting(WorkingArea area) throws IOException {
        String expected = SystemUtils.IS_OS_WINDOWS || (area.git instanceof JGitAPIImpl && isJava6()) ? "false" : "";
        String symlinkValue = null;
        try {
            symlinkValue = w.cmd(true, "git config core.symlinks").trim();
        } catch (Exception e) {
            symlinkValue = e.getMessage();
        }
        assertEquals(expected, symlinkValue);
    }

    public void test_init() throws Exception {
        assertFalse(w.file(".git").exists());
        w.git.init();
        assertTrue(w.file(".git").exists());
        checkSymlinkSetting(w);
    }

    public void test_init_() throws Exception {
        assertFalse(w.file(".git").exists());
        w.git.init_().workspace(w.repoPath()).execute();
        assertTrue(w.file(".git").exists());
        checkSymlinkSetting(w);
    }

    public void test_init_bare() throws Exception {
        assertFalse(w.file(".git").exists());
        assertFalse(w.file("refs").exists());
        w.git.init_().workspace(w.repoPath()).bare(false).execute();
        assertTrue(w.file(".git").exists());
        assertFalse(w.file("refs").exists());
        checkSymlinkSetting(w);

        WorkingArea anotherRepo = new WorkingArea();
        assertFalse(anotherRepo.file(".git").exists());
        assertFalse(anotherRepo.file("refs").exists());
        anotherRepo.git.init_().workspace(anotherRepo.repoPath()).bare(true).execute();
        assertFalse(anotherRepo.file(".git").exists());
        assertTrue(anotherRepo.file("refs").exists());
        checkSymlinkSetting(anotherRepo);
    }

    @NotImplementedInCliGit // Until submodule rename is fixed
    public void test_getSubmoduleUrl() throws Exception {
        w = clone(localMirror());
        w.cmd("git checkout tests/getSubmodules");
        w.git.submoduleInit();

        assertEquals("https://github.com/puppetlabs/puppetlabs-firewall.git", w.igit().getSubmoduleUrl("modules/firewall"));

        try {
            w.igit().getSubmoduleUrl("bogus");
            fail();
        } catch (GitException e) {
            // expected
        }
    }

    public void test_setSubmoduleUrl() throws Exception {
        w = clone(localMirror());
        w.cmd("git checkout tests/getSubmodules");
        w.git.submoduleInit();

        String DUMMY = "/dummy";
        w.igit().setSubmoduleUrl("modules/firewall", DUMMY);

        // create a brand new Git object to make sure it's persisted
        WorkingArea subModuleVerify = new WorkingArea(w.repo);
        assertEquals(DUMMY, subModuleVerify.igit().getSubmoduleUrl("modules/firewall"));
    }

    public void test_prune() throws Exception {
        // pretend that 'r' is a team repository and ws1 and ws2 are team members
        WorkingArea r = new WorkingArea();
        r.init(true);

        WorkingArea ws1 = new WorkingArea().init();
        WorkingArea ws2 = w.init();

        ws1.commitEmpty("c");
        ws1.cmd("git remote add origin " + r.repoPath());

        ws1.cmd("git push origin master:b1");
        ws1.cmd("git push origin master:b2");
        ws1.cmd("git push origin master");

        ws2.cmd("git remote add origin " + r.repoPath());
        ws2.cmd("git fetch origin");

        // at this point both ws1&ws2 have several remote tracking branches

        ws1.cmd("git push origin :b1");
        ws1.cmd("git push origin master:b3");

        ws2.git.prune(new RemoteConfig(new Config(),"origin"));

        assertFalse(ws2.exists(".git/refs/remotes/origin/b1"));
        assertTrue( ws2.exists(".git/refs/remotes/origin/b2"));
        assertFalse(ws2.exists(".git/refs/remotes/origin/b3"));
    }

    public void test_revListAll() throws Exception {
        w.init();
        w.launchCommand("git", "pull", localMirror());

        StringBuilder out = new StringBuilder();
        for (ObjectId id : w.git.revListAll()) {
            out.append(id.name()).append('\n');
        }
        String all = w.cmd("git rev-list --all");
        assertEquals(all,out.toString());
    }

    public void test_revList_() throws Exception {
        List<ObjectId> oidList = new ArrayList<>();
        w.init();
        w.launchCommand("git", "pull", localMirror());

        RevListCommand revListCommand = w.git.revList_();
        revListCommand.all();
        revListCommand.to(oidList);
        revListCommand.execute();

        StringBuilder out = new StringBuilder();
        for (ObjectId id : oidList) {
            out.append(id.name()).append('\n');
        }
        String all = w.cmd("git rev-list --all");
        assertEquals(all,out.toString());
    }

    public void test_revListFirstParent() throws Exception {
        w.init();
        w.launchCommand("git", "pull", localMirror());

        for (Branch b : w.git.getRemoteBranches()) {
            StringBuilder out = new StringBuilder();
            List<ObjectId> oidList = new ArrayList<>();

            RevListCommand revListCommand = w.git.revList_();
            revListCommand.firstParent();
            revListCommand.to(oidList);
            revListCommand.reference(b.getName());
            revListCommand.execute();

            for (ObjectId id : oidList) {
                out.append(id.name()).append('\n');
            }

            String all = w.cmd("git rev-list --first-parent " + b.getName());
            assertEquals(all,out.toString());
        }
    }

    public void test_revList() throws Exception {
        w.init();
        w.launchCommand("git", "pull", localMirror());

        for (Branch b : w.git.getRemoteBranches()) {
            StringBuilder out = new StringBuilder();
            for (ObjectId id : w.git.revList(b.getName())) {
                out.append(id.name()).append('\n');
            }
            String all = w.cmd("git rev-list " + b.getName());
            assertEquals(all,out.toString());
        }
    }

    public void test_merge_strategy() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.git.branch("branch1");
        w.git.checkout("branch1");
        w.touch("file", "content1");
        w.git.add("file");
        w.git.commit("commit1");
        w.git.checkout("master");
        w.git.branch("branch2");
        w.git.checkout("branch2");
        File f = w.touch("file", "content2");
        w.git.add("file");
        w.git.commit("commit2");
        w.git.merge().setStrategy(MergeCommand.Strategy.OURS).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch1")).execute();
        assertEquals("merge didn't selected OURS content", "content2", FileUtils.readFileToString(f));
    }

    public void test_merge_strategy_correct_fail() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.git.branch("branch1");
        w.git.checkout("branch1");
        w.touch("file", "content1");
        w.git.add("file");
        w.git.commit("commit1");
        w.git.checkout("master");
        w.git.branch("branch2");
        w.git.checkout("branch2");
        w.touch("file", "content2");
        w.git.add("file");
        w.git.commit("commit2");
        try {
            w.git.merge().setStrategy(MergeCommand.Strategy.RESOLVE).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch1")).execute();
            fail();
        }
        catch (GitException e) {
            // expected
        }
    }

    @Bug(12402)
    public void test_merge_fast_forward_mode_ff() throws Exception {
        w.init();

        w.commitEmpty("init");
        w.git.branch("branch1");
        w.git.checkout("branch1");
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1");
        final ObjectId branch1 = w.head();

        w.git.checkout("master");
        w.git.branch("branch2");
        w.git.checkout("branch2");
        w.touch("file2", "content2");
        w.git.add("file2");
        w.git.commit("commit2");
        final ObjectId branch2 = w.head();

        w.git.checkout("master");

        // The first merge is a fast-forward, master moves to branch1
        w.git.merge().setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch1")).execute();
        assertEquals("Fast-forward merge failed. master and branch1 should be the same.",w.head(),branch1);

        // The second merge calls for fast-forward (FF), but a merge commit will result
        // This tests that calling for FF gracefully falls back to a commit merge
        // master moves to a new commit ahead of branch1 and branch2
        w.git.merge().setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch2")).execute();
        // The merge commit (head) should have branch2 and branch1 as parents
        List<ObjectId> revList = w.git.revList("HEAD^1");
        assertEquals("Merge commit failed. branch1 should be a parent of HEAD but it isn't.",revList.get(0).name(), branch1.name());
        revList = w.git.revList("HEAD^2");
        assertEquals("Merge commit failed. branch2 should be a parent of HEAD but it isn't.",revList.get(0).name(), branch2.name());
    }

    public void test_merge_fast_forward_mode_ff_only() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.git.branch("branch1");
        w.git.checkout("branch1");
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1");
        final ObjectId branch1 = w.head();

        w.git.checkout("master");
        w.git.branch("branch2");
        w.git.checkout("branch2");
        w.touch("file2", "content2");
        w.git.add("file2");
        w.git.commit("commit2");
        final ObjectId branch2 = w.head();

        w.git.checkout("master");
        final ObjectId master = w.head();

        // The first merge is a fast-forward, master moves to branch1
        w.git.merge().setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF_ONLY).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch1")).execute();
        assertEquals("Fast-forward merge failed. master and branch1 should be the same but aren't.",w.head(),branch1);

        // The second merge calls for fast-forward only (FF_ONLY), but a merge commit is required, hence it is expected to fail
        try {
            w.git.merge().setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF_ONLY).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch2")).execute();
            fail("Exception not thrown: the fast-forward only mode should have failed");
        } catch (GitException e) {
            // expected
            assertEquals("Fast-forward merge abort failed. master and branch1 should still be the same as the merge was aborted.",w.head(),branch1);
        }
    }

    public void test_merge_fast_forward_mode_no_ff() throws Exception {
        w.init();
        w.commitEmpty("init");
        final ObjectId base = w.head();
        w.git.branch("branch1");
        w.git.checkout("branch1");
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1");
        final ObjectId branch1 = w.head();

        w.git.checkout("master");
        w.git.branch("branch2");
        w.git.checkout("branch2");
        w.touch("file2", "content2");
        w.git.add("file2");
        w.git.commit("commit2");
        final ObjectId branch2 = w.head();

        w.git.checkout("master");
        final ObjectId master = w.head();

        // The first merge is normally a fast-forward, but we're calling for a merge commit which is expected to work
        w.git.merge().setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch1")).execute();

        // The first merge will have base and branch1 as parents
        List<ObjectId> revList = null;
        revList = w.git.revList("HEAD^1");
        assertEquals("Merge commit failed. base should be a parent of HEAD but it isn't.",revList.get(0).name(), base.name());
        revList = w.git.revList("HEAD^2");
        assertEquals("Merge commit failed. branch1 should be a parent of HEAD but it isn't.",revList.get(0).name(), branch1.name());

        final ObjectId base2 = w.head();

        // Calling for NO_FF when required is expected to work
        w.git.merge().setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch2")).execute();

        // The second merge will have base2 and branch2 as parents
        revList = w.git.revList("HEAD^1");
        assertEquals("Merge commit failed. base2 should be a parent of HEAD but it isn't.",revList.get(0).name(), base2.name());
        revList = w.git.revList("HEAD^2");
        assertEquals("Merge commit failed. branch2 should be a parent of HEAD but it isn't.",revList.get(0).name(), branch2.name());
    }

    public void test_merge_squash() throws Exception{
        w.init();
        w.commitEmpty("init");
        w.git.branch("branch1");

        //First commit to branch1
        w.git.checkout("branch1");
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1");

        //Second commit to branch1
        w.touch("file2", "content2");
        w.git.add("file2");
        w.git.commit("commit2");

        //Merge branch1 with master, squashing both commits
        w.git.checkout("master");
        w.git.merge().setSquash(true).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch1")).execute();

        //Compare commit counts of before and after commiting the merge, should be  one due to the squashing of commits.
        final int commitCountBefore = w.git.revList("HEAD").size();
        w.git.commit("commitMerge");
        final int commitCountAfter = w.git.revList("HEAD").size();

        assertEquals("Squash merge failed. Should have merged only one commit.", 1, commitCountAfter - commitCountBefore);
    }

    public void test_merge_no_squash() throws Exception{
        w.init();
        w.commitEmpty("init");

        //First commit to branch1
        w.git.branch("branch1");
        w.git.checkout("branch1");
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1");

        //Second commit to branch1
        w.touch("file2", "content2");
        w.git.add("file2");
        w.git.commit("commit2");

        //Merge branch1 with master, without squashing commits.
        //Compare commit counts of before and after commiting the merge, should be  one due to the squashing of commits.
        w.git.checkout("master");
        final int commitCountBefore = w.git.revList("HEAD").size();
        w.git.merge().setSquash(false).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch1")).execute();
        final int commitCountAfter = w.git.revList("HEAD").size();

        assertEquals("Squashless merge failed. Should have merged two commits.", 2, commitCountAfter - commitCountBefore);
    }

    public void test_merge_no_commit() throws Exception{
        w.init();
        w.commitEmpty("init");

        //Create branch1 and commit a file
        w.git.branch("branch1");
        w.git.checkout("branch1");
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1");

        //Merge branch1 with master, without committing the merge.
        //Compare commit counts of before and after the merge, should be zero due to the lack of autocommit.
        w.git.checkout("master");
        final int commitCountBefore = w.git.revList("HEAD").size();
        w.git.merge().setCommit(false).setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch1")).execute();
        final int commitCountAfter = w.git.revList("HEAD").size();

        assertEquals("No Commit merge failed. Shouldn't have committed any changes.", commitCountBefore, commitCountAfter);
    }

    public void test_merge_commit() throws Exception{
        w.init();
        w.commitEmpty("init");

        //Create branch1 and commit a file
        w.git.branch("branch1");
        w.git.checkout("branch1");
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1");

        //Merge branch1 with master, without committing the merge.
        //Compare commit counts of before and after the merge, should be two due to the commit of the file and the commit of the merge.
        w.git.checkout("master");
        final int commitCountBefore = w.git.revList("HEAD").size();
        w.git.merge().setCommit(true).setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch1")).execute();
        final int commitCountAfter = w.git.revList("HEAD").size();

        assertEquals("Commit merge failed. Should have committed the merge.", 2, commitCountAfter - commitCountBefore);
    }

    public void test_merge_with_message() throws Exception {
        w.init();
        w.commitEmpty("init");

        // First commit to branch1
        w.git.branch("branch1");
        w.git.checkout("branch1");
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1");

        // Merge branch1 into master
        w.git.checkout("master");
        String mergeMessage = "Merge message to be tested.";
        w.git.merge().setMessage(mergeMessage).setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch1")).execute();
        // Obtain last commit message
        String resultMessage = w.git.showRevision(w.head()).get(7).trim();

        assertEquals("Custom message merge failed. Should have set custom merge message.", mergeMessage, resultMessage);
    }

    @Deprecated
    public void test_merge_refspec() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.touch("file-master", "content-master");
        w.git.add("file-master");
        w.git.commit("commit1-master");
        final ObjectId base = w.head();

        w.git.branch("branch1");
        w.git.checkout("branch1");
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1-branch1");
        final ObjectId branch1 = w.head();

        w.cmd("git branch branch2 master");
        w.git.checkout("branch2");
        File f = w.touch("file2", "content2");
        w.git.add("file2");
        w.git.commit("commit2-branch2");
        final ObjectId branch2 = w.head();
        assertTrue("file2 does not exist", f.exists());

        assertFalse("file1 exists before merge", w.exists("file1"));
        assertEquals("Wrong merge-base branch1 branch2", base, w.igit().mergeBase(branch1, branch2));

        String badSHA1 = "15c80fb1567f0e88ca855c69e3f17425d515a188";
        ObjectId badBase = ObjectId.fromString(badSHA1);
        try {
            assertNull("Base unexpected for bad SHA1", w.igit().mergeBase(branch1, badBase));
            assertTrue("Exception not thrown by CliGit", w.git instanceof CliGitAPIImpl);
        } catch (GitException moa) {
            assertFalse("Exception thrown by CliGit", w.git instanceof CliGitAPIImpl);
            assertTrue("Exception message didn't mention " + badBase.toString(), moa.getMessage().contains(badSHA1));
        }
        try {
            assertNull("Base unexpected for bad SHA1", w.igit().mergeBase(badBase, branch1));
            assertTrue("Exception not thrown by CliGit", w.git instanceof CliGitAPIImpl);
        } catch (GitException moa) {
            assertFalse("Exception thrown by CliGit", w.git instanceof CliGitAPIImpl);
            assertTrue("Exception message didn't mention " + badBase.toString(), moa.getMessage().contains(badSHA1));
        }

        w.igit().merge("branch1");
        assertTrue("file1 does not exist after merge", w.exists("file1"));

        /* Git 1.7.1 does not understand the --orphan argument to checkout.
         * Stop the test here on older git versions
         */
        if (!w.cgit().isAtLeastVersion(1, 7, 9, 0)) {
            return;
        }
        w.cmd("git checkout --orphan newroot"); // Create an independent root
        w.commitEmpty("init-on-newroot");
        final ObjectId newRootCommit = w.head();
        assertNull("Common root not expected", w.igit().mergeBase(newRootCommit, branch1));

        final String remoteUrl = "ssh://mwaite.example.com//var/lib/git/mwaite/jenkins/git-client-plugin.git";
        w.git.setRemoteUrl("origin", remoteUrl);
        assertEquals("Wrong origin default remote", "origin", w.igit().getDefaultRemote("origin"));
        assertEquals("Wrong invalid default remote", "origin", w.igit().getDefaultRemote("invalid"));
    }

    public void test_rebase_passes_without_conflict() throws Exception {
        w.init();
        w.commitEmpty("init");

        // First commit to master
        w.touch("master_file", "master1");
        w.git.add("master_file");
        w.git.commit("commit-master1");

        // Create a feature branch and make a commit
        w.git.branch("feature1");
        w.git.checkout("feature1");
        w.touch("feature_file", "feature1");
        w.git.add("feature_file");
        w.git.commit("commit-feature1");

        // Second commit to master
        w.git.checkout("master");
        w.touch("master_file", "master2");
        w.git.add("master_file");
        w.git.commit("commit-master2");

        // Rebase feature commit onto master
        w.git.checkout("feature1");
        w.git.rebase().setUpstream("master").execute();

        assertThat("Should've rebased feature1 onto master", w.git.revList("feature1").contains(w.git.revParse("master")));
        assertEquals("HEAD should be on the rebased branch", w.git.revParse("HEAD").name(), w.git.revParse("feature1").name());
        assertThat("Rebased file should be present in the worktree",w.git.getWorkTree().child("feature_file").exists());
    }

    public void test_rebase_fails_with_conflict() throws Exception {
        w.init();
        w.commitEmpty("init");

        // First commit to master
        w.touch("file", "master1");
        w.git.add("file");
        w.git.commit("commit-master1");

        // Create a feature branch and make a commit
        w.git.branch("feature1");
        w.git.checkout("feature1");
        w.touch("file", "feature1");
        w.git.add("file");
        w.git.commit("commit-feature1");

        // Second commit to master
        w.git.checkout("master");
        w.touch("file", "master2");
        w.git.add("file");
        w.git.commit("commit-master2");

        // Rebase feature commit onto master
        w.git.checkout("feature1");
        try {
            w.git.rebase().setUpstream("master").execute();
            fail("Rebase did not throw expected GitException");
        } catch (GitException e) {
            assertEquals("HEAD not reset to the feature branch.", w.git.revParse("HEAD").name(), w.git.revParse("feature1").name());
            Status status = new org.eclipse.jgit.api.Git(w.repo()).status().call();
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
     * Checks that the ChangelogCommand abort() API does not write
     * output to the destination.  Does not check that the abort() API
     * releases resources.
     */
    public void test_changelog_abort() throws InterruptedException, IOException
    {
        final String logMessage = "changelog-abort-test-commit";
        w.init();
        w.touch("file-changelog-abort", "changelog abort file contents " + java.util.UUID.randomUUID().toString());
        w.git.add("file-changelog-abort");
        w.git.commit(logMessage);
        String sha1 = w.git.revParse("HEAD").name();
        ChangelogCommand changelogCommand = w.git.changelog();
        StringWriter writer = new StringWriter();
        changelogCommand.to(writer);

        /* Abort the changelog, confirm no content was written */
        changelogCommand.abort();
        assertEquals("aborted changelog wrote data", "", writer.toString());

        /* Execute the changelog, confirm expected content was written */
        changelogCommand = w.git.changelog();
        changelogCommand.to(writer);
        changelogCommand.execute();
        assertTrue("No log message in " + writer.toString(), writer.toString().contains(logMessage));
        assertTrue("No SHA1 in " + writer.toString(), writer.toString().contains(sha1));
    }

    @Bug(23299)
    public void test_getHeadRev() throws Exception {
        Map<String, ObjectId> heads = w.git.getHeadRev(remoteMirrorURL);
        ObjectId master = w.git.getHeadRev(remoteMirrorURL, "refs/heads/master");
        assertEquals("URL is " + remoteMirrorURL + ", heads is " + heads, master, heads.get("refs/heads/master"));

        /* Test with a specific tag reference - JENKINS-23299 */
        ObjectId knownTag = w.git.getHeadRev(remoteMirrorURL, "refs/tags/git-client-1.10.0");
        ObjectId expectedTag = ObjectId.fromString("1fb23708d6b639c22383c8073d6e75051b2a63aa"); // commit SHA1
        assertEquals("Wrong SHA1 for git-client-1.10.0 tag", expectedTag, knownTag);
    }

    /**
     * User interface calls getHeadRev without a workspace while
     * validating user input. This test showed a null pointer
     * exception in a development version of credential passing to
     * command line git. The referenced repository is a public
     * repository, and https access to a public repository is allowed
     * even if invalid credentials are provided.
     *
     * @throws Exception on test failure
     */
    public void test_getHeadRevFromPublicRepoWithInvalidCredential() throws Exception {
        GitClient remoteGit = Git.with(listener, env).using("git").getClient();
        StandardUsernamePasswordCredentials testCredential = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "bad-id", "bad-desc", "bad-user", "bad-password");
        remoteGit.addDefaultCredentials(testCredential);
        Map<String, ObjectId> heads = remoteGit.getHeadRev(remoteMirrorURL);
        ObjectId master = w.git.getHeadRev(remoteMirrorURL, "refs/heads/master");
        assertEquals("URL is " + remoteMirrorURL + ", heads is " + heads, master, heads.get("refs/heads/master"));
    }

    @Bug(25444)
    public void test_fetch_delete_cleans() throws Exception {
        w.init();
        w.touch("file1", "old");
        w.git.add("file1");
        w.git.commit("commit1");
        w.touch("file1", "new");
        checkoutTimeout = 1 + random.nextInt(60 * 24);
        w.git.checkout().branch("other").ref(Constants.HEAD).timeout(checkoutTimeout).deleteBranchIfExist(true).execute();

        Status status = new org.eclipse.jgit.api.Git(w.repo()).status().call();

        assertTrue("Workspace must be clean", status.isClean());
    }

    /**
     * Test getHeadRev with wildcard matching in the branch name.
     * Relies on the branches in the git-client-plugin repository
     * include at least branches named:
     *   master
     *   tests/getSubmodules
     *
     * Also relies on a specific return ordering of the values in the
     * pattern matching performed by getHeadRev, and relies on not
     * having new branches created which match the patterns and will
     * occur earlier than the expected value.
     */
    public void test_getHeadRev_wildcards() throws Exception {
        Map<String, ObjectId> heads = w.git.getHeadRev(localMirror());
        ObjectId master = w.git.getHeadRev(localMirror(), "refs/heads/master");
        assertEquals("heads is " + heads, heads.get("refs/heads/master"), master);
        ObjectId wildOrigin = w.git.getHeadRev(localMirror(), "*/master");
        assertEquals("heads is " + heads, heads.get("refs/heads/master"), wildOrigin);
        ObjectId master1 = w.git.getHeadRev(localMirror(), "not-a-real-origin-but-allowed/*ast*"); // matches master
        assertEquals("heads is " + heads, heads.get("refs/heads/master"), master1);
        ObjectId getSubmodules1 = w.git.getHeadRev(localMirror(), "X/g*[b]m*dul*"); // matches tests/getSubmodules
        assertEquals("heads is " + heads, heads.get("refs/heads/tests/getSubmodules"), getSubmodules1);
        ObjectId getSubmodules = w.git.getHeadRev(localMirror(), "N/*et*mod*");
        assertEquals("heads is " + heads, heads.get("refs/heads/tests/getSubmodules"), getSubmodules);
    }

    /**
     * Test getHeadRev with namespaces in the branch name
     * and branch specs containing only the simple branch name.
     *
     * TODO: This does not work yet! Fix behaviour and enable test!
     */
    public void test_getHeadRev_namespaces_withSimpleBranchNames() throws Exception {
        setTimeoutVisibleInCurrentTest(false);
        File tempRemoteDir = temporaryDirectoryAllocator.allocate();
        extract(new ZipFile("src/test/resources/namespaceBranchRepo.zip"), tempRemoteDir);
        Properties commits = parseLsRemote(new File("src/test/resources/namespaceBranchRepo.ls-remote"));
        w = clone(tempRemoteDir.getAbsolutePath());
        final String remote = tempRemoteDir.getAbsolutePath();

        final String[][] checkBranchSpecs = {};
//TODO: Fix and enable test
//                {
//                {"master", commits.getProperty("refs/heads/master")},
//                {"a_tests/b_namespace1/master", commits.getProperty("refs/heads/a_tests/b_namespace1/master")},
//                {"a_tests/b_namespace2/master", commits.getProperty("refs/heads/a_tests/b_namespace2/master")},
//                {"a_tests/b_namespace3/master", commits.getProperty("refs/heads/a_tests/b_namespace3/master")},
//                {"b_namespace3/master", commits.getProperty("refs/heads/b_namespace3/master")}
//                };

        for(String[] branch : checkBranchSpecs) {
            final ObjectId objectId = ObjectId.fromString(branch[1]);
            final String branchName = branch[0];
            check_getHeadRev(remote, branchName, objectId);
            check_getHeadRev(remote, "remotes/origin/" + branchName, objectId);
            check_getHeadRev(remote, "refs/heads/" + branchName, objectId);
        }
    }

    /**
     * Test getHeadRev with namespaces in the branch name
     * and branch specs starting with "refs/heads/".
     */
    public void test_getHeadRev_namespaces_withRefsHeads() throws Exception {
        File tempRemoteDir = temporaryDirectoryAllocator.allocate();
        extract(new ZipFile("src/test/resources/namespaceBranchRepo.zip"), tempRemoteDir);
        Properties commits = parseLsRemote(new File("src/test/resources/namespaceBranchRepo.ls-remote"));
        w = clone(tempRemoteDir.getAbsolutePath());
        final String remote = tempRemoteDir.getAbsolutePath();

        final String[][] checkBranchSpecs = {
                {"refs/heads/master", commits.getProperty("refs/heads/master")},
                {"refs/heads/a_tests/b_namespace1/master", commits.getProperty("refs/heads/a_tests/b_namespace1/master")},
                {"refs/heads/a_tests/b_namespace2/master", commits.getProperty("refs/heads/a_tests/b_namespace2/master")},
                {"refs/heads/a_tests/b_namespace3/master", commits.getProperty("refs/heads/a_tests/b_namespace3/master")},
                {"refs/heads/b_namespace3/master", commits.getProperty("refs/heads/b_namespace3/master")}
                };

        for(String[] branch : checkBranchSpecs) {
            final ObjectId objectId = ObjectId.fromString(branch[1]);
            final String branchName = branch[0];
            check_getHeadRev(remote, branchName, objectId);
        }
    }

    /**
     * Test getHeadRev with branch names which SHOULD BE reserved by Git, but ARE NOT.<br/>
     * E.g. it is possible to create the following LOCAL (!) branches:<br/>
     * <ul>
     *   <li> origin/master
     *   <li> remotes/origin/master
     *   <li> refs/heads/master
     *   <li> refs/remotes/origin/master
     * </ul>
     *
     * TODO: This does not work yet! Fix behaviour and enable test!
     */
    public void test_getHeadRev_reservedBranchNames() throws Exception {
        /* REMARK: Local branch names in this test are called exactly like follows!
         *   e.g. origin/master means the branch is called "origin/master", it does NOT mean master branch in remote "origin".
         *   or refs/heads/master means branch called "refs/heads/master" ("refs/heads/refs/heads/master" in the end).
         */

        setTimeoutVisibleInCurrentTest(false);
        File tempRemoteDir = temporaryDirectoryAllocator.allocate();
        extract(new ZipFile("src/test/resources/specialBranchRepo.zip"), tempRemoteDir);
        Properties commits = parseLsRemote(new File("src/test/resources/specialBranchRepo.ls-remote"));
        w = clone(tempRemoteDir.getAbsolutePath());

        /*
         * The first entry in the String[2] is the branch name (as specified in the job config).
         * The second entry is the expected commit.
         */
        final String[][] checkBranchSpecs = {};
//TODO: Fix and enable test
//                {
//                {"master", commits.getProperty("refs/heads/master")},
//                {"origin/master", commits.getProperty("refs/heads/master")},
//                {"remotes/origin/master", commits.getProperty("refs/heads/master")},
//                {"refs/remotes/origin/master", commits.getProperty("refs/heads/refs/remotes/origin/master")},
//                {"refs/heads/origin/master", commits.getProperty("refs/heads/origin/master")},
//                {"refs/heads/master", commits.getProperty("refs/heads/master")},
//                {"refs/heads/refs/heads/master", commits.getProperty("refs/heads/refs/heads/master")},
//                {"refs/heads/refs/heads/refs/heads/master", commits.getProperty("refs/heads/refs/heads/refs/heads/master")},
//                {"refs/tags/master", commits.getProperty("refs/tags/master^{}")}
//                };
        for(String[] branch : checkBranchSpecs) {
          check_getHeadRev(tempRemoteDir.getAbsolutePath(), branch[0], ObjectId.fromString(branch[1]));
        }
    }

    /**
     * Test getRemoteReferences with listing all references
     */
    public void test_getRemoteReferences() throws Exception {
        Map<String, ObjectId> references = w.git.getRemoteReferences(remoteMirrorURL, null, false, false);
        assertTrue(references.containsKey("refs/heads/master"));
        assertTrue(references.containsKey("refs/tags/git-client-1.0.0"));
    }

    /**
     * Test getRemoteReferences with listing references limit to refs/heads or refs/tags
     */
    public void test_getRemoteReferences_withLimitReferences() throws Exception {
        Map<String, ObjectId> references = w.git.getRemoteReferences(remoteMirrorURL, null, true, false);
        assertTrue(references.containsKey("refs/heads/master"));
        assertTrue(!references.containsKey("refs/tags/git-client-1.0.0"));
        references = w.git.getRemoteReferences(remoteMirrorURL, null, false, true);
        assertTrue(!references.containsKey("refs/heads/master"));
        assertTrue(references.containsKey("refs/tags/git-client-1.0.0"));
        for (String key : references.keySet()) {
            assertTrue(!key.endsWith("^{}"));
        }
    }

    /**
     * Test getRemoteReferences with matching pattern
     */
    public void test_getRemoteReferences_withMatchingPattern() throws Exception {
        Map<String, ObjectId> references = w.git.getRemoteReferences(remoteMirrorURL, "refs/heads/master", true, false);
        assertTrue(references.containsKey("refs/heads/master"));
        assertTrue(!references.containsKey("refs/tags/git-client-1.0.0"));
        references = w.git.getRemoteReferences(remoteMirrorURL, "git-client-*", false, true);
        assertTrue(!references.containsKey("refs/heads/master"));
        for (String key : references.keySet()) {
            assertTrue(key.startsWith("refs/tags/git-client"));
        }

        references = new HashMap<>();
        try {
            references = w.git.getRemoteReferences(remoteMirrorURL, "notexists-*", false, false);
        } catch (GitException ge) {
            assertTrue("Wrong exception message: " + ge, ge.getMessage().contains("unexpected ls-remote output"));
        }
        assertTrue(references.isEmpty());
    }

    /**
     * Test getRemoteSymbolicReferences with listing all references
     */
    public void test_getRemoteSymbolicReferences() throws Exception {
        if (!hasWorkingGetRemoteSymbolicReferences()) return; // JUnit 3 replacement for assumeThat
        Map<String, String> references = w.git.getRemoteSymbolicReferences(remoteMirrorURL, null);
        assertThat(references, hasEntry(is(Constants.HEAD), is(Constants.R_HEADS + Constants.MASTER)));
    }

    protected abstract boolean hasWorkingGetRemoteSymbolicReferences();

    /**
     * Test getRemoteSymbolicReferences with listing all references
     */
    public void test_getRemoteSymbolicReferences_withMatchingPattern() throws Exception {
        if (!hasWorkingGetRemoteSymbolicReferences()) return; // JUnit 3 replacement for assumeThat
        Map<String, String> references = w.git.getRemoteSymbolicReferences(remoteMirrorURL, Constants.HEAD);
        assertThat(references, hasEntry(is(Constants.HEAD), is(Constants.R_HEADS + Constants.MASTER)));
        assertThat(references.size(), is(1));
    }

    private Properties parseLsRemote(File file) throws IOException
    {
        Properties properties = new Properties();
        Pattern pattern = Pattern.compile("([a-f0-9]{40})\\s*(.*)");
        for(Object lineO : FileUtils.readLines(file)) {
            String line = ((String)lineO).trim();
            Matcher matcher = pattern.matcher(line);
            if(matcher.matches()) {
                properties.setProperty(matcher.group(2), matcher.group(1));
            } else {
                System.err.println("ls-remote pattern does not match '" + line + "'");
            }
        }
        return properties;
    }

    private void extract(ZipFile zipFile, File outputDir) throws IOException
    {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            File entryDestination = new File(outputDir,  entry.getName());
            entryDestination.getParentFile().mkdirs();
            if (entry.isDirectory())
                entryDestination.mkdirs();
            else {
                try (InputStream in = zipFile.getInputStream(entry);
                        OutputStream out = Files.newOutputStream(entryDestination.toPath());) {
                    org.apache.commons.io.IOUtils.copy(in, out);
                }
            }
        }
    }

    private void check_getHeadRev(String remote, String branchSpec, ObjectId expectedObjectId) throws Exception
    {
        ObjectId actualObjectId = w.git.getHeadRev(remote, branchSpec);
        assertNotNull(String.format("Expected ObjectId is null expectedObjectId '%s', remote '%s', branchSpec '%s'.",
                    expectedObjectId, remote, branchSpec), expectedObjectId);
        assertNotNull(String.format("Actual ObjectId is null. expectedObjectId '%s', remote '%s', branchSpec '%s'.",
                    expectedObjectId, remote, branchSpec), actualObjectId);
        assertEquals(String.format("Actual ObjectId differs from expected one for branchSpec '%s', remote '%s':\n" +
                "Actual %s,\nExpected %s\n", branchSpec, remote,
                StringUtils.join(getBranches(actualObjectId), ", "),
                StringUtils.join(getBranches(expectedObjectId), ", ")),
                expectedObjectId, actualObjectId);
    }

    private List<Branch> getBranches(ObjectId objectId) throws GitException, InterruptedException
    {
        List<Branch> matches = new ArrayList<>();
        Set<Branch> branches = w.git.getBranches();
        for(Branch branch : branches) {
            if(branch.getSHA1().equals(objectId)) matches.add(branch);
        }
        return unmodifiableList(matches);
    }

    private void check_headRev(String repoURL, ObjectId expectedId) throws InterruptedException, IOException {
        final ObjectId originMaster = w.git.getHeadRev(repoURL, "origin/master");
        assertEquals("origin/master mismatch", expectedId, originMaster);

        final ObjectId simpleMaster = w.git.getHeadRev(repoURL, "master");
        assertEquals("simple master mismatch", expectedId, simpleMaster);

        final ObjectId wildcardSCMMaster = w.git.getHeadRev(repoURL, "*/master");
        assertEquals("wildcard SCM master mismatch", expectedId, wildcardSCMMaster);

        /* This assertion may fail if the localMirror has more than
         * one branch matching the wildcard expression in the call to
         * getHeadRev.  The expression is chosen to be unlikely to
         * match with typical branch names, while still matching a
         * known branch name. Should be fine so long as no one creates
         * branches named like master-master or new-master on the
         * remote repo */
        final ObjectId wildcardEndMaster = w.git.getHeadRev(repoURL, "origin/m*aste?");
        assertEquals("wildcard end master mismatch", expectedId, wildcardEndMaster);
    }

    public void test_getHeadRev_localMirror() throws Exception {
        check_headRev(localMirror(), getMirrorHead());
    }

    public void test_getHeadRev_remote() throws Exception {
        String lsRemote = w.cmd("git ls-remote -h " + remoteMirrorURL + " refs/heads/master");
        ObjectId lsRemoteId = ObjectId.fromString(lsRemote.substring(0, 40));
        check_headRev(remoteMirrorURL, lsRemoteId);
    }

    public void test_getHeadRev_current_directory() throws Exception {
        w = clone(localMirror());
        w.git.checkout("master");
        final ObjectId master = w.head();

        w.git.branch("branch1");
        w.git.checkout("branch1");
        w.touch("file1", "branch1 contents " + java.util.UUID.randomUUID().toString());
        w.git.add("file1");
        w.git.commit("commit1-branch1");
        final ObjectId branch1 = w.head();

        Map<String, ObjectId> heads = w.git.getHeadRev(w.repoPath());
        assertEquals(master, heads.get("refs/heads/master"));
        assertEquals(branch1, heads.get("refs/heads/branch1"));

        check_headRev(w.repoPath(), getMirrorHead());
    }

    public void test_getHeadRev_returns_accurate_SHA1_values() throws Exception {
        /* CliGitAPIImpl had a longstanding bug that it inserted the
         * same SHA1 in all the values, rather than inserting the SHA1
         * which matched the key.
         */
        w = clone(localMirror());
        w.git.checkout("master");
        final ObjectId master = w.head();

        w.git.branch("branch1");
        w.git.checkout("branch1");
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1-branch1");
        final ObjectId branch1 = w.head();

        w.cmd("git branch branch.2 master");
        w.git.checkout("branch.2");
        File f = w.touch("file.2", "content2");
        w.git.add("file.2");
        w.git.commit("commit2-branch.2");
        final ObjectId branchDot2 = w.head();
        assertTrue("file.2 does not exist", f.exists());

        Map<String,ObjectId> heads = w.git.getHeadRev(w.repoPath());
        assertEquals("Wrong master in " + heads, master, heads.get("refs/heads/master"));
        assertEquals("Wrong branch1 in " + heads, branch1, heads.get("refs/heads/branch1"));
        assertEquals("Wrong branch.2 in " + heads, branchDot2, heads.get("refs/heads/branch.2"));

        assertEquals("wildcard branch.2 mismatch", branchDot2, w.git.getHeadRev(w.repoPath(), "br*.2"));

        check_headRev(w.repoPath(), getMirrorHead());
    }

    private void check_changelog_sha1(final String sha1, final String branchName) throws InterruptedException
    {
        ChangelogCommand changelogCommand = w.git.changelog();
        changelogCommand.max(1);
        StringWriter writer = new StringWriter();
        changelogCommand.to(writer);
        changelogCommand.execute();
        String splitLog[] = writer.toString().split("[\\n\\r]", 3); // Extract first line of changelog
        assertEquals("Wrong changelog line 1 on branch " + branchName, "commit " + sha1, splitLog[0]);
    }

    public void test_changelog() throws Exception {
        w = clone(localMirror());
        String sha1Prev = w.git.revParse("HEAD").name();
        w.touch("changelog-file", "changelog-file-content-" + sha1Prev);
        w.git.add("changelog-file");
        w.git.commit("changelog-commit-message");
        String sha1 = w.git.revParse("HEAD").name();
        check_changelog_sha1(sha1, "master");
    }

    public void test_show_revision_for_merge() throws Exception {
        w = clone(localMirror());
        ObjectId from = ObjectId.fromString("45e76942914664ee19f31d90e6f2edbfe0d13a46");
        ObjectId to = ObjectId.fromString("b53374617e85537ec46f86911b5efe3e4e2fa54b");

        List<String> revisionDetails = w.git.showRevision(from, to);

        Collection<String> commits = Collections2.filter(revisionDetails, new Predicate<String>() {
            public boolean apply(String detail) {
                return detail.startsWith("commit ");
            }
        });
        assertEquals(3, commits.size());
        assertTrue(commits.contains("commit 4f2964e476776cf59be3e033310f9177bedbf6a8"));
        // Merge commit is duplicated as have to capture changes that may have been made as part of merge
        assertTrue(commits.contains("commit b53374617e85537ec46f86911b5efe3e4e2fa54b (from 4f2964e476776cf59be3e033310f9177bedbf6a8)"));
        assertTrue(commits.contains("commit b53374617e85537ec46f86911b5efe3e4e2fa54b (from 45e76942914664ee19f31d90e6f2edbfe0d13a46)"));

        Collection<String> diffs = Collections2.filter(revisionDetails, new Predicate<String>() {
            public boolean apply(String detail) {
                return detail.startsWith(":");
            }
        });
        Collection<String> paths = Collections2.transform(diffs, new Function<String, String>() {
            public String apply(String diff) {
                return diff.substring(diff.indexOf('\t')+1).trim(); // Windows diff output ^M removed by trim()
            }
        });

        assertTrue(paths.contains(".gitignore"));
        // Some irrelevant changes will be listed due to merge commit
        assertTrue(paths.contains("pom.xml"));
        assertTrue(paths.contains("src/main/java/hudson/plugins/git/GitAPI.java"));
        assertTrue(paths.contains("src/main/java/org/jenkinsci/plugins/gitclient/CliGitAPIImpl.java"));
        assertTrue(paths.contains("src/main/java/org/jenkinsci/plugins/gitclient/Git.java"));
        assertTrue(paths.contains("src/main/java/org/jenkinsci/plugins/gitclient/GitClient.java"));
        assertTrue(paths.contains("src/main/java/org/jenkinsci/plugins/gitclient/JGitAPIImpl.java"));
        assertTrue(paths.contains("src/test/java/org/jenkinsci/plugins/gitclient/GitAPITestCase.java"));
        assertTrue(paths.contains("src/test/java/org/jenkinsci/plugins/gitclient/JGitAPIImplTest.java"));
        // Previous implementation included other commits, and listed irrelevant changes
        assertFalse(paths.contains("README.md"));
    }

    public void test_show_revision_for_merge_exclude_files() throws Exception {
        w = clone(localMirror());
        ObjectId from = ObjectId.fromString("45e76942914664ee19f31d90e6f2edbfe0d13a46");
        ObjectId to = ObjectId.fromString("b53374617e85537ec46f86911b5efe3e4e2fa54b");
        Boolean useRawOutput = false;

        List<String> revisionDetails = w.git.showRevision(from, to, useRawOutput);

        Collection<String> commits = Collections2.filter(revisionDetails, new Predicate<String>() {
            public boolean apply(String detail) {
                return detail.startsWith("commit ");
            }
        });
        assertEquals(2, commits.size());
        assertTrue(commits.contains("commit 4f2964e476776cf59be3e033310f9177bedbf6a8"));
        assertTrue(commits.contains("commit b53374617e85537ec46f86911b5efe3e4e2fa54b"));

        Collection<String> diffs = Collections2.filter(revisionDetails, new Predicate<String>() {
            public boolean apply(String detail) {
                return detail.startsWith(":");
            }
        });

        assertTrue(diffs.isEmpty());
    }

    private void check_bounded_changelog_sha1(final String sha1Begin, final String sha1End, final String branchName) throws InterruptedException
    {
        StringWriter writer = new StringWriter();
        w.git.changelog(sha1Begin, sha1End, writer);
        String splitLog[] = writer.toString().split("[\\n\\r]", 3); // Extract first line of changelog
        assertEquals("Wrong bounded changelog line 1 on branch " + branchName, "commit " + sha1End, splitLog[0]);
        assertTrue("Begin sha1 " + sha1Begin + " not in changelog: " + writer.toString(), writer.toString().contains(sha1Begin));
    }

    public void test_changelog_bounded() throws Exception {
        w = clone(localMirror());
        String sha1Prev = w.git.revParse("HEAD").name();
        w.touch("changelog-file", "changelog-file-content-" + sha1Prev);
        w.git.add("changelog-file");
        w.git.commit("changelog-commit-message");
        String sha1 = w.git.revParse("HEAD").name();
        check_bounded_changelog_sha1(sha1Prev, sha1, "master");
    }

    public void test_show_revision_for_single_commit() throws Exception {
        w = clone(localMirror());
        ObjectId to = ObjectId.fromString("51de9eda47ca8dcf03b2af58dfff7355585f0d0c");
        List<String> revisionDetails = w.git.showRevision(null, to);
        Collection<String> commits = Collections2.filter(revisionDetails, new Predicate<String>() {
            public boolean apply(String detail) {
                return detail.startsWith("commit ");
            }
        });
        assertEquals(1, commits.size());
        assertTrue(commits.contains("commit 51de9eda47ca8dcf03b2af58dfff7355585f0d0c"));
    }

    @Bug(22343)
    public void test_show_revision_for_first_commit() throws Exception {
        w.init();
        w.touch("a");
        w.git.add("a");
        w.git.commit("first");
        ObjectId first = w.head();
        List<String> revisionDetails = w.git.showRevision(first);
        Collection<String> commits = Collections2.filter(revisionDetails, new Predicate<String>() {
            public boolean apply(String detail) {
                return detail.startsWith("commit ");
            }
        });
        assertTrue("Commits '" + commits + "' missing " + first.getName(), commits.contains("commit " + first.getName()));
        assertEquals("Commits '" + commits + "' wrong size", 1, commits.size());
    }

    public void test_describe() throws Exception {
        w.init();
        w.commitEmpty("first");
        w.tag("-m test t1");
        w.touch("a");
        w.git.add("a");
        w.git.commit("second");
        assertThat(w.cmd("git describe").trim(), sharesPrefix(w.git.describe("HEAD")));

        w.tag("-m test2 t2");
        assertThat(w.cmd("git describe").trim(), sharesPrefix(w.git.describe("HEAD")));
    }

    public void test_getAllLogEntries() throws Exception {
        /* Use original clone source instead of localMirror.  The
         * namespace test modifies the localMirror content by creating
         * three independent branches very rapidly.  Those three
         * branches may be created within the same second, making it
         * more difficult for git to provide a time ordered log. The
         * reference to localMirror will help performance of the C git
         * implementation, since that will avoid copying content which
         * is already local. */
        String gitUrl = "https://github.com/jenkinsci/git-client-plugin.git";
        if (SystemUtils.IS_OS_WINDOWS) {
            // Does not leak an open file
            w = clone(gitUrl);
        } else {
            // Leaks an open file - unclear why
            w.git.clone_().url(gitUrl).repositoryName("origin").reference(localMirror()).execute();
        }
        assertEquals(
                w.cgit().getAllLogEntries("origin/master"),
                w.igit().getAllLogEntries("origin/master"));
    }

    public void test_branchContaining() throws Exception {
        /*
         OLD                                    NEW
                   -> X
                  /
                c1 -> T -> c2 -> Z
                  \            \
                   -> c3 --------> Y
         */
        w.init();

        w.commitEmpty("c1");
        ObjectId c1 = w.head();

        w.cmd("git branch Z "+c1.name());
        w.git.checkout("Z");
        w.commitEmpty("T");
        ObjectId t = w.head();
        w.commitEmpty("c2");
        ObjectId c2 = w.head();
        w.commitEmpty("Z");

        w.cmd("git branch X "+c1.name());
        w.git.checkout("X");
        w.commitEmpty("X");

        w.cmd("git branch Y "+c1.name());
        w.git.checkout("Y");
        w.commitEmpty("c3");
        ObjectId c3 = w.head();
        w.cmd("git merge --no-ff -m Y "+c2.name());

        w.git.deleteBranch("master");
        assertEquals(3,w.git.getBranches().size());     // X, Y, and Z

        assertEquals("X,Y,Z",formatBranches(w.igit().getBranchesContaining(c1.name())));
        assertEquals("Y,Z",formatBranches(w.igit().getBranchesContaining(t.name())));
        assertEquals("Y",formatBranches(w.igit().getBranchesContaining(c3.name())));
        assertEquals("X",formatBranches(w.igit().getBranchesContaining("X")));
    }

    /**
     * UT for {@link GitClient#getBranchesContaining(String, boolean)}. The main
     * testing case is retrieving remote branches.
     * @throws Exception on exceptions occur
     */
    public void test_branchContainingRemote() throws Exception {
        final WorkingArea r = new WorkingArea();
        r.init();

        r.commitEmpty("c1");
        ObjectId c1 = r.head();

        w.git.clone_().url("file://" + r.repoPath()).execute();
        final URIish remote = new URIish(Constants.DEFAULT_REMOTE_NAME);
        final List<RefSpec> refspecs = Collections.singletonList(new RefSpec(
                "refs/heads/*:refs/remotes/origin/*"));
        final String remoteBranch = getRemoteBranchPrefix() + Constants.DEFAULT_REMOTE_NAME + "/"
                + Constants.MASTER;
        final String bothBranches = Constants.MASTER + "," + remoteBranch;
        w.git.fetch_().from(remote, refspecs).execute();
        checkoutTimeout = 1 + random.nextInt(60 * 24);
        w.git.checkout().ref(Constants.MASTER).timeout(checkoutTimeout).execute();

        assertEquals(Constants.MASTER,
                formatBranches(w.git.getBranchesContaining(c1.name(), false)));
        assertEquals(bothBranches, formatBranches(w.git.getBranchesContaining(c1.name(), true)));

        r.commitEmpty("c2");
        ObjectId c2 = r.head();
        w.git.fetch_().from(remote, refspecs).execute();
        assertEquals("", formatBranches(w.git.getBranchesContaining(c2.name(), false)));
        assertEquals(remoteBranch, formatBranches(w.git.getBranchesContaining(c2.name(), true)));
    }

    public void test_checkout_null_ref() throws Exception {
        w = clone(localMirror());
        String branches = w.cmd("git branch -l");
        assertTrue("master branch not current branch in " + branches, branches.contains("* master"));
        final String branchName = "test-checkout-null-ref-branch-" + java.util.UUID.randomUUID().toString();
        branches = w.cmd("git branch -l");
        assertFalse("test branch originally listed in " + branches, branches.contains(branchName));
        w.git.checkout(null, branchName);
        branches = w.cmd("git branch -l");
        assertTrue("test branch not current branch in " + branches, branches.contains("* " + branchName));
    }

    public void test_checkout() throws Exception {
        w = clone(localMirror());
        String branches = w.cmd("git branch -l");
        assertTrue("master branch not current branch in " + branches, branches.contains("* master"));
        final String branchName = "test-checkout-branch-" + java.util.UUID.randomUUID().toString();
        branches = w.cmd("git branch -l");
        assertFalse("test branch originally listed in " + branches, branches.contains(branchName));
        w.git.checkout("6b7bbcb8f0e51668ddba349b683fb06b4bd9d0ea", branchName); // git-client-1.6.0
        branches = w.cmd("git branch -l");
        assertTrue("test branch not current branch in " + branches, branches.contains("* " + branchName));
        String sha1 = w.git.revParse("HEAD").name();
        String sha1Expected = "6b7bbcb8f0e51668ddba349b683fb06b4bd9d0ea";
        assertEquals("Wrong SHA1 as checkout of git-client-1.6.0", sha1Expected, sha1);
    }

    @Bug(37185)
    @NotImplementedInJGit /* JGit doesn't have timeout */
    public void test_checkout_honor_timeout() throws Exception {
        w = clone(localMirror());

        checkoutTimeout = 1 + random.nextInt(60 * 24);
        w.git.checkout().branch("master").ref("origin/master").timeout(checkoutTimeout).deleteBranchIfExist(true).execute();
    }

    @Bug(25353)
    @NotImplementedInJGit /* JGit lock file management ignored for now */
    public void test_checkout_interrupted() throws Exception {
        w = clone(localMirror());
        File lockFile = new File(w.repo().getDirectory(), "index.lock");
        assertFalse("lock file already exists", lockFile.exists());
        String exceptionMsg = "test checkout intentionally interrupted";
        CliGitAPIImpl cli = (CliGitAPIImpl) w.git;
        CheckoutCommand cmd = cli.checkout().ref("6b7bbcb8f0e51668ddba349b683fb06b4bd9d0ea"); // git-client-1.6.0
        cli.interruptNextCheckoutWithMessage(exceptionMsg);
        try {
            cmd.execute();
            fail("Did not throw InterruptedException");
        } catch (InterruptedException ie) {
            assertEquals(exceptionMsg, ie.getMessage());
        }
        assertFalse("lock file '" + lockFile.getCanonicalPath() + " not removed by cleanup", lockFile.exists());
    }

    @Bug(25353)
    @NotImplementedInJGit /* JGit lock file management ignored for now */
    public void test_checkout_interrupted_with_existing_lock() throws Exception {
        w = clone(localMirror());
        File lockFile = new File(w.repo().getDirectory(), "index.lock");
        lockFile.createNewFile();
        Thread.sleep(1500); // Wait 1.5 seconds to "age" existing lock file
        assertTrue("lock file does not exist", lockFile.exists());
        String exceptionMsg = "test checkout intentionally interrupted";
        CliGitAPIImpl cli = (CliGitAPIImpl) w.git;
        CheckoutCommand cmd = cli.checkout().ref("6b7bbcb8f0e51668ddba349b683fb06b4bd9d0ea").timeout(1); // git-client-1.6.0
        cli.interruptNextCheckoutWithMessage(exceptionMsg);
        try {
            cmd.execute();
            fail("Did not throw InterruptedException");
        } catch (InterruptedException ie) {
            assertEquals(exceptionMsg, ie.getMessage());
        }
        assertTrue("lock file '" + lockFile.getCanonicalPath() + " removed by cleanup", lockFile.exists());
    }

    @Bug(19108)
    public void test_checkoutBranch() throws Exception {
        w.init();
        w.commitEmpty("c1");
        w.tag("t1");
        w.commitEmpty("c2");

        w.git.checkoutBranch("foo", "t1");

        assertEquals(w.head(),w.git.revParse("t1"));
        assertEquals(w.head(),w.git.revParse("foo"));

        Ref head = w.repo().getRef("HEAD");
        assertTrue(head.isSymbolic());
        assertEquals("refs/heads/foo",head.getTarget().getName());
    }

    /**
     * Test case for auto local branch creation behviour.
     * This is essentially a stripped down version of {@link #test_branchContainingRemote()}
     * @throws Exception on exceptions occur
     */
    public void test_checkout_remote_autocreates_local() throws Exception {
        final WorkingArea r = new WorkingArea();
        r.init();
        r.commitEmpty("c1");

        w.git.clone_().url("file://" + r.repoPath()).execute();
        final URIish remote = new URIish(Constants.DEFAULT_REMOTE_NAME);
        final List<RefSpec> refspecs = Collections.singletonList(new RefSpec(
                "refs/heads/*:refs/remotes/origin/*"));
        w.git.fetch_().from(remote, refspecs).execute();
        w.git.checkout().ref(Constants.MASTER).execute();

        Set<String> refNames = w.git.getRefNames("refs/heads/");
        assertThat(refNames, contains("refs/heads/master"));
    }

    public void test_autocreate_fails_on_multiple_matching_origins() throws Exception {
        final WorkingArea r = new WorkingArea();
        r.init();
        r.commitEmpty("c1");

        w.git.clone_().url("file://" + r.repoPath()).execute();
        final URIish remote = new URIish(Constants.DEFAULT_REMOTE_NAME);

        try ( // add second remote
                FileRepository repo = w.repo()) {
            StoredConfig config = repo.getConfig();
            config.setString("remote", "upstream", "url", "file://" + r.repoPath());
            config.setString("remote", "upstream", "fetch", "+refs/heads/*:refs/remotes/upstream/*");
            config.save();
        }

        // fill both remote branches
        List<RefSpec> refspecs = Collections.singletonList(new RefSpec(
                "refs/heads/*:refs/remotes/origin/*"));
        w.git.fetch_().from(remote, refspecs).execute();
        refspecs = Collections.singletonList(new RefSpec(
                "refs/heads/*:refs/remotes/upstream/*"));
        w.git.fetch_().from(remote, refspecs).execute();

        try {
            w.git.checkout().ref(Constants.MASTER).execute();
            fail("GitException expected");
        } catch (GitException e) {
            // expected
        }
    }

    public void test_revList_remote_branch() throws Exception {
        w = clone(localMirror());
        List<ObjectId> revList = w.git.revList("origin/1.4.x");
        assertEquals("Wrong list size: " + revList, 267, revList.size());
        Ref branchRef = w.repo().getRef("origin/1.4.x");
        assertTrue("origin/1.4.x not in revList", revList.contains(branchRef.getObjectId()));
    }

    public void test_revList_tag() throws Exception {
        w.init();
        w.commitEmpty("c1");
        Ref commitRefC1 = w.repo().getRef("HEAD");
        w.tag("t1");
        Ref tagRefT1 = w.repo().getRef("t1");
        Ref head = w.repo().getRef("HEAD");
        assertEquals("head != t1", head.getObjectId(), tagRefT1.getObjectId());
        w.commitEmpty("c2");
        Ref commitRefC2 = w.repo().getRef("HEAD");
        List<ObjectId> revList = w.git.revList("t1");
        assertTrue("c1 not in revList", revList.contains(commitRefC1.getObjectId()));
        assertEquals("Wrong list size: " + revList, 1, revList.size());
    }

    public void test_revList_local_branch() throws Exception {
        w.init();
        w.commitEmpty("c1");
        w.tag("t1");
        w.commitEmpty("c2");
        List<ObjectId> revList = w.git.revList("master");
        assertEquals("Wrong list size: " + revList, 2, revList.size());
    }

    @Bug(20153)
    public void test_checkoutBranch_null() throws Exception {
        w.init();
        w.commitEmpty("c1");
        String sha1 = w.git.revParse("HEAD").name();
        w.commitEmpty("c2");

        w.git.checkoutBranch(null, sha1);

        assertEquals(w.head(),w.git.revParse(sha1));

        Ref head = w.repo().getRef("HEAD");
        assertFalse(head.isSymbolic());
    }

    private String formatBranches(List<Branch> branches) {
        Set<String> names = new TreeSet<>();
        for (Branch b : branches) {
            names.add(b.getName());
        }
        return Util.join(names,",");
    }

    @Bug(18988)
    public void test_localCheckoutConflict() throws Exception {
        w.init();
        w.touch("foo","old");
        w.git.add("foo");
        w.git.commit("c1");
        w.tag("t1");

        // delete the file from git
        w.cmd("git rm foo");
        w.git.commit("c2");
        assertFalse(w.file("foo").exists());

        // now create an untracked local file
        w.touch("foo","new");

        // this should overwrite foo
        w.git.checkout("t1");

        assertEquals("old",FileUtils.readFileToString(w.file("foo")));
    }

    public void test_bare_repo_init() throws IOException, InterruptedException {
        w.init(true);
        assertFalse(".git exists unexpectedly", w.file(".git").exists());
        assertFalse(".git/objects exists unexpectedly", w.file(".git/objects").exists());
        assertTrue("objects is not a directory", w.file("objects").isDirectory());
    }

    /* The most critical use cases of isBareRepository respond the
     * same for both the JGit implementation and the CliGit
     * implementation.  Those are asserted first in this section of
     * assertions.
     */

    @Deprecated
    public void test_isBareRepository_working_repoPath_dot_git() throws IOException, InterruptedException {
        w.init();
        w.commitEmpty("Not-a-bare-repository-false-repoPath-dot-git");
        assertFalse("repoPath/.git is a bare repository", w.igit().isBareRepository(w.repoPath() + File.separator + ".git"));
    }

    @Deprecated
    public void test_isBareRepository_working_null() throws IOException, InterruptedException {
        w.init();
        w.commitEmpty("Not-a-bare-repository-working-null");
        try {
            assertFalse("null is a bare repository", w.igit().isBareRepository(null));
            fail("Did not throw expected exception");
        } catch (GitException ge) {
            assertTrue("Wrong exception message: " + ge, ge.getMessage().toLowerCase().contains("not a git repository"));
        }
    }

    @Deprecated
    public void test_isBareRepository_bare_null() throws IOException, InterruptedException {
        w.init(true);
        try {
            assertTrue("null is not a bare repository", w.igit().isBareRepository(null));
            fail("Did not throw expected exception");
        } catch (GitException ge) {
            assertTrue("Wrong exception message: " + ge, ge.getMessage().toLowerCase().contains("not a git repository"));
        }
    }

    @Deprecated
    public void test_isBareRepository_bare_repoPath() throws IOException, InterruptedException {
        w.init(true);
        assertTrue("repoPath is not a bare repository", w.igit().isBareRepository(w.repoPath()));
        assertTrue("abs(.) is not a bare repository", w.igit().isBareRepository(w.file(".").getAbsolutePath()));
    }

    @Deprecated
    public void test_isBareRepository_working_no_arg() throws IOException, InterruptedException {
        w.init();
        w.commitEmpty("Not-a-bare-repository-no-arg");
        assertFalse("no arg is a bare repository", w.igit().isBareRepository());
    }

    @Deprecated
    public void test_isBareRepository_bare_no_arg() throws IOException, InterruptedException {
        w.init(true);
        assertTrue("no arg is not a bare repository", w.igit().isBareRepository());
    }

    @Deprecated
    public void test_isBareRepository_working_empty_string() throws IOException, InterruptedException {
        w.init();
        w.commitEmpty("Not-a-bare-repository-empty-string");
        assertFalse("empty string is a bare repository", w.igit().isBareRepository(""));
    }

    @Deprecated
    public void test_isBareRepository_bare_empty_string() throws IOException, InterruptedException {
        w.init(true);
        assertTrue("empty string is not a bare repository", w.igit().isBareRepository(""));
    }

    /* The less critical assertions do not respond the same for the
     * JGit and the CliGit implementation. They are implemented here
     * so that the current behavior is described in tests and can be
     * used to assure that changes to current behavior are
     * detected.
     */

    // Fails on both JGit and CliGit, though with different failure modes
    // @Deprecated
    // public void test_isBareRepository_working_repoPath() throws IOException, InterruptedException {
    //     w.init();
    //     w.commitEmpty("Not-a-bare-repository-working-repoPath-dot-git");
    //     assertFalse("repoPath is a bare repository", w.igit().isBareRepository(w.repoPath()));
    //     assertFalse("abs(.) is a bare repository", w.igit().isBareRepository(w.file(".").getAbsolutePath()));
    // }

    @Deprecated
    public void test_isBareRepository_working_dot() throws IOException, InterruptedException {
        w.init();
        w.commitEmpty("Not-a-bare-repository-working-dot");
        try {
            assertFalse(". is a bare repository", w.igit().isBareRepository("."));
            if (w.git instanceof CliGitAPIImpl) {
                /* No exception from JGit */
                fail("Did not throw expected exception");
            }
        } catch (GitException ge) {
            assertTrue("Wrong exception message: " + ge, ge.getMessage().toLowerCase().contains("not a git repository"));
        }
    }

    @Deprecated
    public void test_isBareRepository_bare_dot() throws IOException, InterruptedException {
        w.init(true);
        assertTrue(". is not a bare repository", w.igit().isBareRepository("."));
    }

    @Deprecated
    public void test_isBareRepository_working_dot_git() throws IOException, InterruptedException {
        w.init();
        w.commitEmpty("Not-a-bare-repository-dot-git");
        assertFalse(".git is a bare repository", w.igit().isBareRepository(".git"));
    }

    @Deprecated
    public void test_isBareRepository_bare_dot_git() throws IOException, InterruptedException {
        w.init(true);
        /* Bare repository does not have a .git directory.  This is
         * another no-such-location test but is included here for
         * consistency.
         */
        try {
            /* JGit knows that w.igit() has a workspace, and asks the workspace
             * if it is bare.  That seems more correct than relying on testing
             * a specific file that the repository is bare.  JGit behaves better
             * than CliGit in this case.
             */
            assertTrue("non-existent .git is in a bare repository", w.igit().isBareRepository(".git"));
            /* JGit will not throw an exception - it knows the repo is bare */
            /* CliGit throws an exception so should not reach the next assertion */
            assertFalse("CliGitAPIImpl did not throw expected exception", w.igit() instanceof CliGitAPIImpl);
        } catch (GitException ge) {
            /* Only enters this path for CliGit */
            assertTrue("Wrong exception message: " + ge, ge.getMessage().toLowerCase().contains("not a git repository"));
        }
    }

    @Deprecated
    public void test_isBareRepository_working_no_such_location() throws IOException, InterruptedException {
        w.init();
        w.commitEmpty("Not-a-bare-repository-working-no-such-location");
        try {
            assertFalse("non-existent location is in a bare repository", w.igit().isBareRepository("no-such-location"));
            /* JGit will not throw an exception - it knows the repo is not bare */
            /* CliGit throws an exception so should not reach the next assertion */
            assertFalse("CliGitAPIImpl did not throw expected exception", w.igit() instanceof CliGitAPIImpl);
        } catch (GitException ge) {
            /* Only enters this path for CliGit */
            assertTrue("Wrong exception message: " + ge, ge.getMessage().toLowerCase().contains("not a git repository"));
        }
    }

    @Deprecated
    public void test_isBareRepository_bare_no_such_location() throws IOException, InterruptedException {
        w.init(true);
        try {
            assertTrue("non-existent location is in a bare repository", w.igit().isBareRepository("no-such-location"));
            /* JGit will not throw an exception - it knows the repo is not bare */
            /* CliGit throws an exception so should not reach the next assertion */
            assertFalse("CliGitAPIImpl did not throw expected exception", w.igit() instanceof CliGitAPIImpl);
        } catch (GitException ge) {
            /* Only enters this path for CliGit */
            assertTrue("Wrong exception message: " + ge, ge.getMessage().toLowerCase().contains("not a git repository"));
        }
    }

    public void test_checkoutBranchFailure() throws Exception {
        w = clone(localMirror());
        File lock = new File(w.repo, ".git/index.lock");
        try {
            FileUtils.touch(lock);
            w.git.checkoutBranch("somebranch", "master");
            fail();
        } catch (GitLockFailedException e) {
            // expected
        } finally {
            lock.delete();
        }
    }

    @Deprecated
    public void test_reset() throws IOException, InterruptedException {
        w.init();
        /* No valid HEAD yet - nothing to reset, should give no error */
        w.igit().reset(false);
        w.igit().reset(true);
        w.touch("committed-file", "committed-file content " + java.util.UUID.randomUUID().toString());
        w.git.add("committed-file");
        w.git.commit("commit1");
        assertTrue("committed-file missing at commit1", w.file("committed-file").exists());
        assertFalse("added-file exists at commit1", w.file("added-file").exists());
        assertFalse("touched-file exists at commit1", w.file("added-file").exists());

        w.cmd("git rm committed-file");
        w.touch("added-file", "File 2 content " + java.util.UUID.randomUUID().toString());
        w.git.add("added-file");
        w.touch("touched-file", "File 3 content " + java.util.UUID.randomUUID().toString());
        assertFalse("committed-file exists", w.file("committed-file").exists());
        assertTrue("added-file missing", w.file("added-file").exists());
        assertTrue("touched-file missing", w.file("touched-file").exists());

        w.igit().reset(false);
        assertFalse("committed-file exists", w.file("committed-file").exists());
        assertTrue("added-file missing", w.file("added-file").exists());
        assertTrue("touched-file missing", w.file("touched-file").exists());

        w.git.add("added-file"); /* Add the file which soft reset "unadded" */

        w.igit().reset(true);
        assertTrue("committed-file missing", w.file("committed-file").exists());
        assertFalse("added-file exists at hard reset", w.file("added-file").exists());
        assertTrue("touched-file missing", w.file("touched-file").exists());

        final String remoteUrl = "git@github.com:MarkEWaite/git-client-plugin.git";
        w.git.setRemoteUrl("origin", remoteUrl);
        w.git.setRemoteUrl("ndeloof", "git@github.com:ndeloof/git-client-plugin.git");
        assertEquals("Wrong origin default remote", "origin", w.igit().getDefaultRemote("origin"));
        assertEquals("Wrong ndeloof default remote", "ndeloof", w.igit().getDefaultRemote("ndeloof"));
        /* CliGitAPIImpl and JGitAPIImpl return different ordered lists for default remote if invalid */
        assertEquals("Wrong invalid default remote", w.git instanceof CliGitAPIImpl ? "ndeloof" : "origin",
                     w.igit().getDefaultRemote("invalid"));
    }

    private static final int MAX_PATH = 256;

    private void commitFile(String dirName, String fileName, boolean longpathsEnabled) throws Exception {
        assertTrue("Didn't mkdir " + dirName, w.file(dirName).mkdir());

        String fullName = dirName + File.separator + fileName;
        w.touch(fullName, fullName + " content " + UUID.randomUUID().toString());

        boolean shouldThrow = !longpathsEnabled &&
            SystemUtils.IS_OS_WINDOWS &&
            w.git instanceof CliGitAPIImpl &&
            w.cgit().isAtLeastVersion(1, 9, 0, 0) &&
            !w.cgit().isAtLeastVersion(2, 8, 0, 0) &&
            (new File(fullName)).getAbsolutePath().length() > MAX_PATH;

        try {
            w.git.add(fullName);
            w.git.commit("commit-" + fileName);
            assertFalse("unexpected success " + fullName, shouldThrow);
        } catch (GitException ge) {
            assertEquals("Wrong message", "Cannot add " + fullName, ge.getMessage());
        }
        assertTrue("file " + fullName + " missing at commit", w.file(fullName).exists());
    }

    private void commitFile(String dirName, String fileName) throws Exception {
        commitFile(dirName, fileName, false);
    }

    /**
     * msysgit prior to 1.9 forbids file names longer than MAXPATH.
     * msysgit 1.9 and later allows longer paths if core.longpaths is
     * set to true.
     *
     * JGit does not have that limitation.
     */
    public void check_longpaths(boolean longpathsEnabled) throws Exception {
        String shortName = "0123456789abcdef" + "ghijklmnopqrstuv";
        String longName = shortName + shortName + shortName + shortName;

        String dirName1 = longName;
        commitFile(dirName1, "file1a", longpathsEnabled);

        String dirName2 = dirName1 + File.separator + longName;
        commitFile(dirName2, "file2b", longpathsEnabled);

        String dirName3 = dirName2 + File.separator + longName;
        commitFile(dirName3, "file3c", longpathsEnabled);

        String dirName4 = dirName3 + File.separator + longName;
        commitFile(dirName4, "file4d", longpathsEnabled);

        String dirName5 = dirName4 + File.separator + longName;
        commitFile(dirName5, "file5e", longpathsEnabled);
    }

    private String getConfigValue(File workingDir, String name) throws IOException, InterruptedException {
        String[] args = {"git", "config", "--get", name};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int st = new Launcher.LocalLauncher(listener).launch().pwd(workingDir).cmds(args).stdout(out).join();
        String result = out.toString();
        if (st != 0 && result != null && !result.isEmpty()) {
            fail("git config --get " + name + " failed with result: " + result);
        }
        return out.toString().trim();
    }

    private String getHomeConfigValue(String name) throws IOException, InterruptedException {
        return getConfigValue(new File(System.getProperty("user.home")), name);
    }

    private void assert_longpaths(boolean expectedLongPathSetting) throws IOException, InterruptedException {
        String value = getHomeConfigValue("core.longpaths");
        boolean longPathSetting = Boolean.valueOf(value);
        assertEquals("Wrong value: '" + value + "'", expectedLongPathSetting, longPathSetting);
    }

    private void assert_longpaths(WorkingArea workingArea, boolean expectedLongPathSetting) throws IOException, InterruptedException {
        String value = getConfigValue(workingArea.repo, "core.longpaths");
        boolean longPathSetting = Boolean.valueOf(value);
        assertEquals("Wrong value: '" + value + "'", expectedLongPathSetting, longPathSetting);
    }

    public void test_longpaths_default() throws Exception {
        assert_longpaths(false);
        w.init();
        assert_longpaths(w, false);
        check_longpaths(false);
        assert_longpaths(w, false);
    }

    @NotImplementedInJGit
    /* Not implemented in JGit because it is not needed there */
    public void test_longpaths_enabled() throws Exception {
        assert_longpaths(false);
        w.init();
        assert_longpaths(w, false);
        w.cmd("git config core.longpaths true");
        assert_longpaths(w, true);
        check_longpaths(true);
        assert_longpaths(w, true);
    }

    @NotImplementedInJGit
    /* Not implemented in JGit because it is not needed there */
    public void test_longpaths_disabled() throws Exception {
        assert_longpaths(false);
        w.init();
        assert_longpaths(w, false);
        w.cmd("git config core.longpaths false");
        assert_longpaths(w, false);
        check_longpaths(false);
        assert_longpaths(w, false);
    }

    /**
     * Returns the prefix for the remote branches while querying them.
     * @return remote branch prefix, for example, "remotes/"
     */
    protected abstract String getRemoteBranchPrefix();

    /**
     * Test parsing of changelog with unicode characters in commit messages.
     */
    public void test_unicodeCharsInChangelog() throws Exception {

        // Test for
        //   https://issues.jenkins-ci.org/browse/JENKINS-6203
        //   https://issues.jenkins-ci.org/browse/JENKINS-14798
        //   https://issues.jenkins-ci.org/browse/JENKINS-23091

        File tempRemoteDir = temporaryDirectoryAllocator.allocate();
        extract(new ZipFile("src/test/resources/unicodeCharsInChangelogRepo.zip"), tempRemoteDir);
        File pathToTempRepo = new File(tempRemoteDir, "unicodeCharsInChangelogRepo");
        w = clone(pathToTempRepo.getAbsolutePath());

        // w.git.changelog gives us strings
        // We want to collect all the strings and check that unicode characters are still there.

        StringWriter sw = new StringWriter();
        w.git.changelog("v0", "vLast", sw);
        String content = sw.toString();

        assertTrue(content.contains("hello in English: hello"));
        assertTrue(content.contains("hello in Russian: \u043F\u0440\u0438\u0432\u0435\u0442 (priv\u00E9t)"));
        assertTrue(content.contains("hello in Chinese: \u4F60\u597D (n\u01D0 h\u01CEo)"));
        assertTrue(content.contains("hello in French: \u00C7a va ?"));
        assertTrue(content.contains("goodbye in German: Tsch\u00FCss"));
    }

    /**
     * Multi-branch pipeline plugin and other AbstractGitSCMSource callers were
     * initially using JGit as their implementation, and developed an unexpected
     * dependency on JGit behavior. JGit init() (in JGit 3.7 at least) creates
     * the directory if it does not exist. Rather than change the multi-branch
     * pipeline when the git client plugin was adapted to allow either git or
     * jgit, instead the git.init() method was changed to create the target
     * directory if it does not exist.
     *
     * Low risk from that change of behavior, since a non-existent directory
     * caused the command line git init() method to consistently throw an
     * exception.
     *
     * @throws java.lang.Exception on error
     */
    public void test_git_init_creates_directory_if_needed() throws Exception {
        File nonexistentDir = new File(UUID.randomUUID().toString());
        assertFalse("Dir unexpectedly exists at start of test", nonexistentDir.exists());
        try {
            GitClient git = setupGitAPI(nonexistentDir);
            git.init();
        } finally {
            FileUtils.deleteDirectory(nonexistentDir);
        }
    }

    @Bug(40023)
    public void test_changelog_with_merge_commit_and_max_log_history() throws Exception {
        w.init();
        w.commitEmpty("init");

        // First commit to branch-1
        w.git.branch("branch-1");
        w.git.checkout("branch-1");
        w.touch("file-1", "content-1");
        w.git.add("file-1");
        w.git.commit("commit-1");
        String commitSha1 = w.git.revParse("HEAD").name();

        // Merge branch-1 into master
        w.git.checkout("master");
        String mergeMessage = "Merge message to be tested.";
        w.git.merge().setMessage(mergeMessage).setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.NO_FF).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch-1")).execute();

        /* JGit, and git 1.7.1 handle merge commits in changelog
         * differently than git 1.7.9 and later.  See JENKINS-40023.
         */
        int maxlimit;
        if (w.git instanceof CliGitAPIImpl) {
            if (!w.cgit().isAtLeastVersion(1, 7, 9, 0)) {
                return; /* git 1.7.1 is too old, changelog is too different */
            }
            maxlimit = 1;
        } else {
            maxlimit = 2;
        }

        StringWriter writer = new StringWriter();
        w.git.changelog().max(maxlimit).to(writer).execute();
        assertThat(writer.toString(),not(isEmptyString()));
    }

    /** inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue */
    private boolean isWindows() {
        return File.pathSeparatorChar==';';
    }

}
