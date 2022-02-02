package org.jenkinsci.plugins.gitclient;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import hudson.Launcher;
import hudson.Util;
import hudson.ProxyConfiguration;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.IndexEntry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import junit.framework.TestCase;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RemoteConfig;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;
import org.objenesis.ObjenesisStd;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardCopyOption;

/**
 * JUnit 3 based tests inherited by CliGitAPIImplTest, JGitAPIImplTest, and JGitApacheAPIImplTest.
 * Tests are expected to run in ALL git implementations in the git client plugin.
 *
 * Tests in this class are being migrated to JUnit 4 in other classes.
 * Refer to GitClientTest, GitClientCliTest, GitClientCloneTest, and GitClientFetchTest for examples.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public abstract class GitAPITestCase extends TestCase {

    private final TemporaryDirectoryAllocator temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();

    protected hudson.EnvVars env = new hudson.EnvVars();
    protected TaskListener listener;

    private LogHandler handler = null;
    private int logCount = 0;
    private static String defaultBranchName = "mast" + "er"; // Intentionally split string
    private static String defaultRemoteBranchName = "origin/" + defaultBranchName;
    private static final String LOGGING_STARTED = "Logging started";

    /** Name of the default branch on the official git client plugin remote repository. */
    private static final String DEFAULT_MIRROR_BRANCH_NAME = "mast" + "er"; // Intentionally split string

    private int checkoutTimeout = -1;
    private int submoduleUpdateTimeout = -1;

    private void assertCheckoutTimeout() {
        if (checkoutTimeout > 0) {
            assertSubstringTimeout("git checkout", checkoutTimeout);
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
            String userName = "root";
            String emailAddress = "root@mydomain.com";
            CliGitCommand gitCmd = new CliGitCommand(git);
            gitCmd.run("config", "user.name", userName);
            gitCmd.run("config", "user.email", emailAddress);
            git.setAuthor(userName, emailAddress);
            git.setCommitter(userName, emailAddress);
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
            if (force) {
                launchCommand("git", "tag", "--force", tag);
            } else {
                launchCommand("git", "tag", tag);
            }
        }

        void commitEmpty(String msg) throws IOException, InterruptedException {
            launchCommand("git", "commit", "--allow-empty", "-m", msg);
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

        void rm(String path) {
            file(path).delete();
        }

        String contentOf(String path) throws IOException {
            return FileUtils.readFileToString(file(path), "UTF-8");
        }

        /**
         * Creates a CGit implementation. Sometimes we need this for testing JGit impl.
         */
        CliGitAPIImpl cgit() throws Exception {
            return (CliGitAPIImpl)Git.with(listener, env).in(repo).using("git").getClient();
        }

        /**
         * Creates a JGit implementation. Sometimes we need this for testing CliGit impl.
         */
        JGitAPIImpl jgit() throws Exception {
            return (JGitAPIImpl)Git.with(listener, env).in(repo).using("jgit").getClient();
        }

        /**
         * Creates a {@link Repository} object out of it.
         */
        FileRepository repo() throws IOException {
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
        IGitAPI igit() {
            return (IGitAPI)git;
        }
    }

    protected WorkingArea w;

    protected WorkingArea clone(String src) throws Exception {
        WorkingArea x = new WorkingArea();
        x.launchCommand("git", "clone", src, x.repoPath());
        WorkingArea clonedArea = new WorkingArea(x.repo);
        clonedArea.launchCommand("git", "config", "user.name", "Vojtěch Zweibrücken-Šafařík");
        clonedArea.launchCommand("git", "config", "user.email", "email.address.from.git.client.plugin.test@example.com");
        return clonedArea;
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

    private static boolean firstRun = true;

    @Override
    protected void setUp() throws Exception {
        if (firstRun) {
            firstRun = false;
            defaultBranchName = getDefaultBranchName();
            defaultRemoteBranchName = "origin/" + defaultBranchName;
        }
        setTimeoutVisibleInCurrentTest(true);
        checkoutTimeout = -1;
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

    private String getDefaultBranchName() throws Exception {
        String defaultBranchValue = "mast" + "er"; // Intentionally split to note this will remain
        File configDir = Files.createTempDirectory("readGitConfig").toFile();
        CliGitCommand getDefaultBranchNameCmd = new CliGitCommand(Git.with(TaskListener.NULL, env).in(configDir).using("git").getClient());
        String[] output = getDefaultBranchNameCmd.runWithoutAssert("config", "--get", "init.defaultBranch");
        for (String s : output) {
            String result = s.trim();
            if (result != null && !result.isEmpty()) {
                defaultBranchValue = result;
            }
        }
        assertTrue("Failed to delete temporary readGitConfig directory", configDir.delete());
        return defaultBranchValue;
    }

    /**
     * Populate the local mirror of the git client plugin repository.
     * Returns path to the local mirror directory.
     *
     * @return path to the local mirrror directory
     * @throws IOException on I/O error
     * @throws InterruptedException when execption is interrupted
     */
    protected String localMirror() throws IOException, InterruptedException {
        File base = new File(".").getAbsoluteFile();
        for (File f=base; f!=null; f=f.getParentFile()) {
            File targetDir = new File(f, "target");
            if (targetDir.exists()) {
                String cloneDirName = "clone.git";
                File clone = new File(targetDir, cloneDirName);
                if (!clone.exists()) {
                    /* Clone to a temporary directory then move the
                     * temporary directory to the final destination
                     * directory. The temporary directory prevents
                     * collision with other tests running in parallel.
                     * The atomic move after clone completion assures
                     * that only one of the parallel processes creates
                     * the final destination directory.
                     */
                    Path tempClonePath = Files.createTempDirectory(targetDir.toPath(), "clone-");
                    w.launchCommand("git", "clone", "--reference", f.getCanonicalPath(), "--mirror", "https://github.com/jenkinsci/git-client-plugin", tempClonePath.toFile().getAbsolutePath());
                    if (!clone.exists()) { // Still a race condition, but a narrow race handled by Files.move()
                        renameAndDeleteDir(tempClonePath, cloneDirName);
                    } else {
                        /*
                         * If many unit tests run at the same time and
                         * are using the localMirror, multiple clones
                         * will happen.  All but one of the clones
                         * will be discarded.  The tests reduce the
                         * likelihood of multiple concurrent clones by
                         * adding a random delay to the start of
                         * longer running tests that use the local
                         * mirror.  The delay was enough in my tests
                         * to prevent the duplicate clones and the
                         * resulting discard of the results of the
                         * clone.
                         *
                         * Different processor configurations with
                         * different performance characteristics may
                         * still have parallel tests which attempt to
                         * clone the local mirror concurrently. If
                         * parallel clones happen, only one of the
                         * parallel clones will 'win the race'.  The
                         * deleteRecursive() will discard a clone that
                         * 'lost the race'.
                         */
                        Util.deleteRecursive(tempClonePath.toFile());
                    }
                }
                return clone.getPath();
            }
        }
        throw new IllegalStateException();
    }

    private void renameAndDeleteDir(Path srcDir, String destDirName) {
        try {
            // Try an atomic move first
            Files.move(srcDir, srcDir.resolveSibling(destDirName), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // If Atomic move is not supported, try a move, display exception on failure
            try {
                Files.move(srcDir, srcDir.resolveSibling(destDirName));
            } catch (IOException ioe) {
                Util.displayIOException(ioe, listener);
            }
        } catch (FileAlreadyExistsException ignored) {
            // Intentionally ignore FileAlreadyExists, another thread or process won the race
        } catch (IOException ioe) {
            Util.displayIOException(ioe, listener);
        } finally {
            try {
                Util.deleteRecursive(srcDir.toFile());
            } catch (IOException ioe) {
                Util.displayIOException(ioe, listener);
            }
        }
    }

    protected abstract GitClient setupGitAPI(File ws) throws Exception;

    private List<File> tempDirsToDelete = new ArrayList<>();

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
            assertSubmoduleUpdateTimeout();
        } finally {
            handler.close();
        }
        try {
            for (File tempdir : tempDirsToDelete) {
                Util.deleteRecursive(tempdir);
            }
        } catch (IOException e) {
            e.printStackTrace(System.err);
        } finally {
            tempDirsToDelete = new ArrayList<>();
        }
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
    public void test_getDefaultRemote() throws Exception {
        w.init();
        w.launchCommand("git", "remote", "add", "origin", "https://github.com/jenkinsci/git-client-plugin.git");
        w.launchCommand("git", "remote", "add", "ndeloof", "git@github.com:ndeloof/git-client-plugin.git");
        assertEquals("Wrong origin default remote", "origin", w.igit().getDefaultRemote("origin"));
        assertEquals("Wrong ndeloof default remote", "ndeloof", w.igit().getDefaultRemote("ndeloof"));
        /* CliGitAPIImpl and JGitAPIImpl return different ordered lists for default remote if invalid */
        assertEquals("Wrong invalid default remote", w.git instanceof CliGitAPIImpl ? "ndeloof" : "origin",
                     w.igit().getDefaultRemote("invalid"));
    }

    private void assertExceptionMessageContains(GitException ge, String expectedSubstring) {
        String actual = ge.getMessage().toLowerCase();
        assertTrue("Expected '" + expectedSubstring + "' exception message, but was: " + actual, actual.contains(expectedSubstring));
    }

    public void test_hasGitRepo_without_git_directory() throws Exception
    {
        setTimeoutVisibleInCurrentTest(false);
        assertFalse("Empty directory has a Git repo", w.git.hasGitRepo());
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
        w.git.push("origin", defaultBranchName);
        /* JGitAPIImpl revParse fails unexpectedly when used here */
        ObjectId bareHead = w.git instanceof CliGitAPIImpl ? bare.head() : ObjectId.fromString(bare.launchCommand("git", "rev-parse", defaultBranchName).substring(0, 40));
        assertEquals("Heads don't match", workHead, bareHead);
        assertEquals("Heads don't match", w.git.getHeadRev(w.repoPath(), defaultBranchName), bare.git.getHeadRev(bare.repoPath(), defaultBranchName));

        /* Commit a new file */
        w.touch("file1");
        w.git.add("file1");
        w.git.commit("commit1");

        /* Push commit to the bare repo */
        Config config = new Config();
        config.fromText(w.contentOf(".git/config"));
        RemoteConfig origin = new RemoteConfig(config, "origin");
        w.igit().push(origin, defaultBranchName);

        /* JGitAPIImpl revParse fails unexpectedly when used here */
        ObjectId workHead2 = w.git instanceof CliGitAPIImpl ? w.head() : ObjectId.fromString(w.launchCommand("git", "rev-parse", defaultBranchName).substring(0, 40));
        ObjectId bareHead2 = w.git instanceof CliGitAPIImpl ? bare.head() : ObjectId.fromString(bare.launchCommand("git", "rev-parse", defaultBranchName).substring(0, 40));
        assertEquals("Working SHA1 != bare SHA1", workHead2, bareHead2);
        assertEquals("Working SHA1 != bare SHA1", w.git.getHeadRev(w.repoPath(), defaultBranchName), bare.git.getHeadRev(bare.repoPath(), defaultBranchName));
    }

    @Deprecated
    public void test_merge_refspec() throws Exception {
        w.init();
        w.commitEmpty("init");
        w.touch("file-default-branch", "content-default-branch");
        w.git.add("file-default-branch");
        w.git.commit("commit1-default-branch");
        final ObjectId base = w.head();

        w.git.branch("branch1");
        w.git.checkout().ref("branch1").execute();
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1-branch1");
        final ObjectId branch1 = w.head();

        w.launchCommand("git", "branch", "branch2", defaultBranchName);
        w.git.checkout().ref("branch2").execute();
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
            assertExceptionMessageContains(moa, badSHA1);
        }
        try {
            assertNull("Base unexpected for bad SHA1", w.igit().mergeBase(badBase, branch1));
            assertTrue("Exception not thrown by CliGit", w.git instanceof CliGitAPIImpl);
        } catch (GitException moa) {
            assertFalse("Exception thrown by CliGit", w.git instanceof CliGitAPIImpl);
            assertExceptionMessageContains(moa, badSHA1);
        }

        w.igit().merge("branch1");
        assertTrue("file1 does not exist after merge", w.exists("file1"));

        /* Git 1.7.1 does not understand the --orphan argument to checkout.
         * Stop the test here on older git versions
         */
        if (!w.cgit().isAtLeastVersion(1, 7, 9, 0)) {
            return;
        }
        w.launchCommand("git", "checkout", "--orphan", "newroot"); // Create an independent root
        w.commitEmpty("init-on-newroot");
        final ObjectId newRootCommit = w.head();
        assertNull("Common root not expected", w.igit().mergeBase(newRootCommit, branch1));

        final String remoteUrl = "ssh://mwaite.example.com//var/lib/git/mwaite/jenkins/git-client-plugin.git";
        w.git.setRemoteUrl("origin", remoteUrl);
        assertEquals("Wrong origin default remote", "origin", w.igit().getDefaultRemote("origin"));
        assertEquals("Wrong invalid default remote", "origin", w.igit().getDefaultRemote("invalid"));
    }

    protected abstract boolean hasWorkingGetRemoteSymbolicReferences();

    /*
    * Test result is intentionally ignored because it depends on the output
    * order of the `git log --all` command and the JGit equivalent. Output order
    * of that command is not reliable since it performs a time ordered sort and
    * the time resolution is only one second.  Commits within the same second
    * are sometimes ordered differently by JGit than by command line git.
    * Testing a deprecated method is not important enough to distract with
    * test failures.
    */
    @Deprecated
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
        String cgitAllLogEntries = w.cgit().getAllLogEntries("origin/" + DEFAULT_MIRROR_BRANCH_NAME);
        String igitAllLogEntries = w.igit().getAllLogEntries("origin/" + DEFAULT_MIRROR_BRANCH_NAME);
        if (!cgitAllLogEntries.equals(igitAllLogEntries)) {
            return; // JUnit 3 does not honor @Ignore annotation
        }
        assertEquals(cgitAllLogEntries, igitAllLogEntries);
    }

    private String formatBranches(List<Branch> branches) {
        Set<String> names = new TreeSet<>();
        for (Branch b : branches) {
            names.add(b.getName());
        }
        return String.join(",", names);
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
    public void test_reset() throws IOException, InterruptedException {
        w.init();
        /* No valid HEAD yet - nothing to reset, should give no error */
        w.igit().reset(false);
        w.igit().reset(true);
        w.touch("committed-file", "committed-file content " + UUID.randomUUID());
        w.git.add("committed-file");
        w.git.commit("commit1");
        assertTrue("committed-file missing at commit1", w.file("committed-file").exists());
        assertFalse("added-file exists at commit1", w.file("added-file").exists());
        assertFalse("touched-file exists at commit1", w.file("added-file").exists());

        w.launchCommand("git", "rm", "committed-file");
        w.touch("added-file", "File 2 content " + UUID.randomUUID());
        w.git.add("added-file");
        w.touch("touched-file", "File 3 content " + UUID.randomUUID());
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

    /**
     * Returns the prefix for the remote branches while querying them.
     * @return remote branch prefix, for example, "remotes/"
     */
    protected abstract String getRemoteBranchPrefix();

    @FunctionalInterface
    interface TestedCode {
        void run() throws Exception;
    }
}
