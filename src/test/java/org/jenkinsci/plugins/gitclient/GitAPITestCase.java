package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import static java.util.Collections.unmodifiableList;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

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
import java.nio.file.Path;
import java.nio.file.Paths;
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
import static java.util.stream.Collectors.toList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import junit.framework.TestCase;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;
import org.objenesis.ObjenesisStd;

import com.google.common.collect.Collections2;
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
    private static final String LOGGING_STARTED = "Logging started";

    private String revParseBranchName = null;

    private int checkoutTimeout = -1;
    private int cloneTimeout = -1;
    private int fetchTimeout = -1;
    private int submoduleUpdateTimeout = -1;
    private final Random random = new Random();

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
            assertCloneTimeout();
            assertFetchTimeout();
            assertSubmoduleUpdateTimeout();
            assertRevParseCalls(revParseBranchName);
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

    private Collection<String> getBranchNames(Collection<Branch> branches) {
        return branches.stream().map(Branch::getName).collect(toList());
    }

    private void assertBranchesExist(Set<Branch> branches, String ... names) throws InterruptedException {
        Collection<String> branchNames = getBranchNames(branches);
        for (String name : names) {
            assertThat(branchNames, hasItem(name));
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
        w.git.push("origin", "master");
        /* JGitAPIImpl revParse fails unexpectedly when used here */
        ObjectId bareHead = w.git instanceof CliGitAPIImpl ? bare.head() : ObjectId.fromString(bare.launchCommand("git", "rev-parse", "master").substring(0, 40));
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
        ObjectId workHead2 = w.git instanceof CliGitAPIImpl ? w.head() : ObjectId.fromString(w.launchCommand("git", "rev-parse", "master").substring(0, 40));
        ObjectId bareHead2 = w.git instanceof CliGitAPIImpl ? bare.head() : ObjectId.fromString(bare.launchCommand("git", "rev-parse", "master").substring(0, 40));
        assertEquals("Working SHA1 != bare SHA1", workHead2, bareHead2);
        assertEquals("Working SHA1 != bare SHA1", w.git.getHeadRev(w.repoPath(), "master"), bare.git.getHeadRev(bare.repoPath(), "master"));
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
        assertSubmoduleDirs(w.repo, true, false);

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
            /* JGit does not fully support renamed submodules - creates directories but not content */
            assertSubmoduleDirs(w.repo, true, false);
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
            /* JGit does not fully support renamed submodules - creates directories but not content */
            assertSubmoduleDirs(w.repo, true, false);
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
            /*
             * Transition from "with submodule" to "without submodule"
             * where the "without submodule" case includes the file
             * ntpContributingFile and the directory ntpDir.
             */
            /* submodule dirs exist because git.clean() won't remove untracked submodules */
            assertDirExists(firewallDir);
            assertDirExists(sshkeysDir);
            assertFileExists(sshkeysModuleFile);
        } else {
            /*
             * Transition from "with submodule" to "without submodule"
             * where the "without submodule" case includes the file
             * ntpContributingFile and the directory ntpDir.
             *
             * Prior to JGit 5.3.1 ntpDir was not available at this point.
             *
             * Prior to JGit 5.2.0 and the CheckoutCommand bug fix,
             * the ntpDir would remain along with ntpContributingFile.
             */
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
                w.launchCommand("git", "clean", "-xffd");
            } else {
                try {
                    w.launchCommand("git", "clean", "-xffd");
                } catch (Exception e) {
                    /* Retry once (and only once) in case of Windows busy file behavior */
                    Thread.sleep(503); /* Wait 0.5 seconds for Windows */
                    w.launchCommand("git", "clean", "-xffd");
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
        assertSubmoduleDirs(w.repo, true, false);

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

    private void assertSubmoduleRepository(File submoduleDir) throws Exception {
        /* Get a client directly on the submoduleDir */
        GitClient submoduleClient = setupGitAPI(submoduleDir);

        /* Assert that when we invoke the repository callback it gets a
         * functioning repository object
         */
        submoduleClient.withRepository((final Repository repo, VirtualChannel channel) -> {
            assertTrue(repo.getDirectory() + " is not a valid repository",
                    repo.getObjectDatabase().exists());
            return null;
        });
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

    public void assertFixSubmoduleUrlsThrows() throws InterruptedException {
        try {
            w.igit().fixSubmoduleUrls("origin", listener);
            fail("Expected exception not thrown");
        } catch (UnsupportedOperationException uoe) {
            assertTrue("Unsupported operation not on JGit", w.igit() instanceof JGitAPIImpl);
        } catch (GitException ge) {
            assertTrue("GitException not on CliGit", w.igit() instanceof CliGitAPIImpl);
            assertTrue("Wrong message in " + ge.getMessage(), ge.getMessage().startsWith("Could not determine remote"));
            assertExceptionMessageContains(ge, "origin");
        }
    }

    private File createTempDirectoryWithoutSpaces() throws IOException {
        // JENKINS-56175 notes that the plugin does not support submodule URL's
        // which contain a space character. Parent pom 3.36 and later use a
        // temporary directory containing a space to detect these problems.
        // Not yet ready to solve JENKINS-56175, so this dodges the problem by
        // creating the submodule repository in a path which does not contain
        // space characters.
        Path tempDirWithoutSpaces = Files.createTempDirectory("no-spaces");
        assertThat(tempDirWithoutSpaces.toString(), not(containsString(" ")));
        tempDirsToDelete.add(tempDirWithoutSpaces.toFile());
        return tempDirWithoutSpaces.toFile();
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
        WorkingArea r = new WorkingArea(createTempDirectoryWithoutSpaces());
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
        w.git.submoduleUpdate().recursive(true).remoteTracking(false).execute();
        assertFalse("file2 exists and should not because we didn't update to the tip of the branch (master).", w.exists(subFile));

        // Run submodule update with remote tracking
        w.git.submoduleUpdate().recursive(true).remoteTracking(true).execute();
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
        w.git.checkout().ref("JENKINS-23424/master").branch("master").execute();
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

    @Issue("JENKINS-23424")
    public void test_checkout_replaces_tracked_changes() throws Exception {
        base_checkout_replaces_tracked_changes(false);
    }

    @Issue("JENKINS-23424")
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
    @Issue("JENKINS-8122")
    public void test_submodule_tags_not_fetched_into_parent() throws Exception {
        w.git.clone_().url(localMirror()).repositoryName("origin").execute();
        checkoutTimeout = 1 + random.nextInt(60 * 24);
        w.git.checkout().ref("origin/master").branch("master").timeout(checkoutTimeout).execute();

        String tagsBefore = w.launchCommand("git", "tag");
        Set<String> tagNamesBefore = w.git.getTagNames(null);
        for (String tag : tagNamesBefore) {
            assertTrue(tag + " not in " + tagsBefore, tagsBefore.contains(tag));
        }

        w.git.checkout().branch("tests/getSubmodules").ref("origin/tests/getSubmodules").timeout(checkoutTimeout).execute();
        w.git.submoduleUpdate().recursive(true).execute();

        String tagsAfter = w.launchCommand("git", "tag");
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
        w.git.checkout().ref("sub_origin/tests/getSubmodules").branch("tests/getSubmodules").execute();
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

        String shallow = Paths.get(".git", "modules", "module", "1", "shallow").toString();
        assertFalse("shallow file existence: " + shallow, w.exists(shallow));
    }

    @NotImplementedInJGit
    public void test_submodule_update_with_error() throws Exception {
        w.git.clone_().url(localMirror()).execute();
        w.git.checkout().ref("origin/tests/getSubmodules").execute();
        w.rm("modules/ntp");
        w.touch("modules/ntp", "file that interferes with ntp submodule folder");

        try {
            w.git.submoduleUpdate().execute();
            fail("Did not throw expected submodule update exception");
        } catch (GitException e) {
            assertThat(e.getMessage(), containsString("Command \"git submodule update modules/ntp\" returned status code 1"));
        }
    }

    public void test_submodule_update_shallow() throws Exception {
        WorkingArea remote = setupRepositoryWithSubmodule();
        w.git.clone_().url("file://" + remote.file("dir-repository").getAbsolutePath()).repositoryName("origin").execute();
        w.git.checkout().branch("master").ref("origin/master").execute();
        w.git.submoduleInit();
        w.git.submoduleUpdate().shallow(true).execute();

        boolean hasShallowSubmoduleSupport = w.git instanceof CliGitAPIImpl && w.cgit().isAtLeastVersion(1, 8, 4, 0);

        String shallow = Paths.get(".git", "modules", "submodule", "shallow").toString();
        assertEquals("shallow file existence: " + shallow, hasShallowSubmoduleSupport, w.exists(shallow));

        int localSubmoduleCommits = w.cgit().subGit("submodule").revList("master").size();
        int remoteSubmoduleCommits = remote.cgit().subGit("dir-submodule").revList("master").size();
        assertEquals("submodule commit count didn't match", hasShallowSubmoduleSupport ? 1 : remoteSubmoduleCommits, localSubmoduleCommits);
    }

    public void test_submodule_update_shallow_with_depth() throws Exception {
        WorkingArea remote = setupRepositoryWithSubmodule();
        w.git.clone_().url("file://" + remote.file("dir-repository").getAbsolutePath()).repositoryName("origin").execute();
        w.git.checkout().branch("master").ref("origin/master").execute();
        w.git.submoduleInit();
        w.git.submoduleUpdate().shallow(true).depth(2).execute();

        boolean hasShallowSubmoduleSupport = w.git instanceof CliGitAPIImpl && w.cgit().isAtLeastVersion(1, 8, 4, 0);

        String shallow = Paths.get(".git", "modules", "submodule", "shallow").toString();
        assertEquals("shallow file existence: " + shallow, hasShallowSubmoduleSupport, w.exists(shallow));

        int localSubmoduleCommits = w.cgit().subGit("submodule").revList("master").size();
        int remoteSubmoduleCommits = remote.cgit().subGit("dir-submodule").revList("master").size();
        assertEquals("submodule commit count didn't match", hasShallowSubmoduleSupport ? 2 : remoteSubmoduleCommits, localSubmoduleCommits);
    }

    @NotImplementedInJGit
    public void test_submodule_update_with_threads() throws Exception {
        w.init();
        w.git.clone_().url(localMirror()).repositoryName("sub2_origin").execute();
        w.git.checkout().branch("tests/getSubmodules").ref("sub2_origin/tests/getSubmodules").deleteBranchIfExist(true).execute();
        w.git.submoduleInit();
        w.git.submoduleUpdate().threads(3).execute();

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
        WorkingArea r = new WorkingArea(createTempDirectoryWithoutSpaces());
        r.init();
        r.touch("file1", "content1");
        r.git.add("file1");
        r.git.commit("submod-commit1");

        r.git.branch("branch1");
        r.git.checkout().ref("branch1").execute();
        r.touch("file2", "content2");
        r.git.add("file2");
        r.git.commit("submod-commit2");
        r.git.checkout().ref("master").execute();

        r.git.branch("branch2");
        r.git.checkout().ref("branch2").execute();
        r.touch("file3", "content3");
        r.git.add("file3");
        r.git.commit("submod-commit3");
        r.git.checkout().ref("master").execute();

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
        workingArea.git.checkout().ref("origin/master").branch("master").deleteBranchIfExist(true).sparseCheckoutPaths(Arrays.asList("dir1")).timeout(checkoutTimeout).execute();
        assertTrue(workingArea.exists("dir1"));
        assertFalse(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea.git.checkout().ref("origin/master").branch("master").deleteBranchIfExist(true).sparseCheckoutPaths(Arrays.asList("dir2")).timeout(checkoutTimeout).execute();
        assertFalse(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea.git.checkout().ref("origin/master").branch("master").deleteBranchIfExist(true).sparseCheckoutPaths(Arrays.asList("dir1", "dir2")).timeout(checkoutTimeout).execute();
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

    /*
     * core.symlinks is set to false by git for WIndows.
     * It is not set on Linux.
     * See also JENKINS-22376 and JENKINS-22391
     */
    @Issue("JENKINS-21168")
    private void checkSymlinkSetting(WorkingArea area) throws IOException {
        String expected = SystemUtils.IS_OS_WINDOWS ? "false" : "";
        String symlinkValue;
        try {
            symlinkValue = w.launchCommand(true, "git", "config", "core.symlinks").trim();
        } catch (Exception e) {
            symlinkValue = e.getMessage();
        }
        assertEquals(expected, symlinkValue);
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
        w.git.checkout().ref("branch1").execute();
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1-branch1");
        final ObjectId branch1 = w.head();

        w.launchCommand("git", "branch", "branch2", "master");
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

        final String[][] checkBranchSpecs =
        //TODO: Fix and enable test
        {
            {"a_tests/b_namespace1/master", commits.getProperty("refs/heads/a_tests/b_namespace1/master")},
            // {"a_tests/b_namespace2/master", commits.getProperty("refs/heads/a_tests/b_namespace2/master")},
            // {"a_tests/b_namespace3/master", commits.getProperty("refs/heads/a_tests/b_namespace3/master")},
            // {"b_namespace3/master", commits.getProperty("refs/heads/b_namespace3/master")},
            // {"master", commits.getProperty("refs/heads/master")},
        };

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
            assertExceptionMessageContains(ge, "unexpected ls-remote output");
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
        String lsRemote = w.launchCommand("git", "ls-remote", "-h", remoteMirrorURL, "refs/heads/master");
        ObjectId lsRemoteId = ObjectId.fromString(lsRemote.substring(0, 40));
        check_headRev(remoteMirrorURL, lsRemoteId);
    }

    public void test_getHeadRev_current_directory() throws Exception {
        w = clone(localMirror());
        w.git.checkout().ref("master").execute();
        final ObjectId master = w.head();

        w.git.branch("branch1");
        w.git.checkout().ref("branch1").execute();
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
        w.git.checkout().ref("master").execute();
        final ObjectId master = w.head();

        w.git.branch("branch1");
        w.git.checkout().ref("branch1").execute();
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1-branch1");
        final ObjectId branch1 = w.head();

        w.launchCommand("git", "branch", "branch.2", "master");
        w.git.checkout().ref("branch.2").execute();
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

    public void test_show_revision_for_merge() throws Exception {
        w = clone(localMirror());
        ObjectId from = ObjectId.fromString("45e76942914664ee19f31d90e6f2edbfe0d13a46");
        ObjectId to = ObjectId.fromString("b53374617e85537ec46f86911b5efe3e4e2fa54b");

        List<String> revisionDetails = w.git.showRevision(from, to);

        Collection<String> commits = Collections2.filter(revisionDetails, (String detail) -> detail.startsWith("commit "));
        assertEquals(3, commits.size());
        assertTrue(commits.contains("commit 4f2964e476776cf59be3e033310f9177bedbf6a8"));
        // Merge commit is duplicated as have to capture changes that may have been made as part of merge
        assertTrue(commits.contains("commit b53374617e85537ec46f86911b5efe3e4e2fa54b (from 4f2964e476776cf59be3e033310f9177bedbf6a8)"));
        assertTrue(commits.contains("commit b53374617e85537ec46f86911b5efe3e4e2fa54b (from 45e76942914664ee19f31d90e6f2edbfe0d13a46)"));

        Collection<String> diffs = Collections2.filter(revisionDetails, (String detail) -> detail.startsWith(":"));
        Collection<String> paths = Collections2.transform(diffs, (String diff) -> diff.substring(diff.indexOf('\t')+1).trim() // Windows diff output ^M removed by trim()
        );

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
        assertFalse(paths.contains("README.adoc"));
        assertFalse(paths.contains("README.md"));
    }

    public void test_show_revision_for_merge_exclude_files() throws Exception {
        w = clone(localMirror());
        ObjectId from = ObjectId.fromString("45e76942914664ee19f31d90e6f2edbfe0d13a46");
        ObjectId to = ObjectId.fromString("b53374617e85537ec46f86911b5efe3e4e2fa54b");
        Boolean useRawOutput = false;

        List<String> revisionDetails = w.git.showRevision(from, to, useRawOutput);

        Collection<String> commits = Collections2.filter(revisionDetails, (String detail) -> detail.startsWith("commit "));
        assertEquals(2, commits.size());
        assertTrue(commits.contains("commit 4f2964e476776cf59be3e033310f9177bedbf6a8"));
        assertTrue(commits.contains("commit b53374617e85537ec46f86911b5efe3e4e2fa54b"));

        Collection<String> diffs = Collections2.filter(revisionDetails, (String detail) -> detail.startsWith(":"));

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
        Collection<String> commits = Collections2.filter(revisionDetails, (String detail) -> detail.startsWith("commit "));
        assertEquals(1, commits.size());
        assertTrue(commits.contains("commit 51de9eda47ca8dcf03b2af58dfff7355585f0d0c"));
    }

    @Issue("JENKINS-22343")
    public void test_show_revision_for_first_commit() throws Exception {
        w.init();
        w.touch("a");
        w.git.add("a");
        w.git.commit("first");
        ObjectId first = w.head();
        List<String> revisionDetails = w.git.showRevision(first);
        Collection<String> commits = Collections2.filter(revisionDetails, (String detail) -> detail.startsWith("commit "));
        assertTrue("Commits '" + commits + "' missing " + first.getName(), commits.contains("commit " + first.getName()));
        assertEquals("Commits '" + commits + "' wrong size", 1, commits.size());
    }

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
        String cgitAllLogEntries = w.cgit().getAllLogEntries("origin/master");
        String igitAllLogEntries = w.igit().getAllLogEntries("origin/master");
        if (!cgitAllLogEntries.equals(igitAllLogEntries)) {
            return; // JUnit 3 does not honor @Ignore annotation
        }
        assertEquals(cgitAllLogEntries, igitAllLogEntries);
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
        String branches = w.launchCommand("git", "branch", "-l");
        assertTrue("master branch not current branch in " + branches, branches.contains("* master"));
        final String branchName = "test-checkout-null-ref-branch-" + java.util.UUID.randomUUID().toString();
        branches = w.launchCommand("git", "branch", "-l");
        assertFalse("test branch originally listed in " + branches, branches.contains(branchName));
        w.git.checkout().ref(null).branch(branchName).execute();
        branches = w.launchCommand("git", "branch", "-l");
        assertTrue("test branch not current branch in " + branches, branches.contains("* " + branchName));
    }

    public void test_checkout() throws Exception {
        w = clone(localMirror());
        String branches = w.launchCommand("git", "branch", "-l");
        assertTrue("master branch not current branch in " + branches, branches.contains("* master"));
        final String branchName = "test-checkout-branch-" + java.util.UUID.randomUUID().toString();
        branches = w.launchCommand("git", "branch", "-l");
        assertFalse("test branch originally listed in " + branches, branches.contains(branchName));
        w.git.checkout().ref("6b7bbcb8f0e51668ddba349b683fb06b4bd9d0ea").branch(branchName).execute(); // git-client-1.6.0
        branches = w.launchCommand("git", "branch", "-l");
        assertTrue("test branch not current branch in " + branches, branches.contains("* " + branchName));
        String sha1 = w.git.revParse("HEAD").name();
        String sha1Expected = "6b7bbcb8f0e51668ddba349b683fb06b4bd9d0ea";
        assertEquals("Wrong SHA1 as checkout of git-client-1.6.0", sha1Expected, sha1);
    }

    @Issue("JENKINS-37185")
    @NotImplementedInJGit /* JGit doesn't have timeout */
    public void test_checkout_honor_timeout() throws Exception {
        w = clone(localMirror());

        checkoutTimeout = 1 + random.nextInt(60 * 24);
        w.git.checkout().branch("master").ref("origin/master").timeout(checkoutTimeout).deleteBranchIfExist(true).execute();
    }

    public void test_revList_remote_branch() throws Exception {
        w = clone(localMirror());
        List<ObjectId> revList = w.git.revList("origin/1.4.x");
        assertEquals("Wrong list size: " + revList, 267, revList.size());
        Ref branchRef = w.repo().findRef("origin/1.4.x");
        assertTrue("origin/1.4.x not in revList", revList.contains(branchRef.getObjectId()));
    }

    private String formatBranches(List<Branch> branches) {
        Set<String> names = new TreeSet<>();
        for (Branch b : branches) {
            names.add(b.getName());
        }
        return Util.join(names,",");
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

        w.launchCommand("git", "rm", "committed-file");
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

    /**
     * Returns the prefix for the remote branches while querying them.
     * @return remote branch prefix, for example, "remotes/"
     */
    protected abstract String getRemoteBranchPrefix();

    /**
     * Test parsing of changelog with unicode characters in commit messages.
     */
    @Issue({"JENKINS-6203", "JENKINS-14798", "JENKINS-23091"})
    public void test_unicodeCharsInChangelog() throws Exception {
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

    @Issue("JENKINS-40023")
    public void test_changelog_with_merge_commit_and_max_log_history() throws Exception {
        w.init();
        w.commitEmpty("init");

        // First commit to branch-1
        w.git.branch("branch-1");
        w.git.checkout().ref("branch-1").execute();
        w.touch("file-1", "content-1");
        w.git.add("file-1");
        w.git.commit("commit-1");
        String commitSha1 = w.git.revParse("HEAD").name();

        // Merge branch-1 into master
        w.git.checkout().ref("master").execute();
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
        assertThat(writer.toString(), is(not("")));
    }

    /** inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue */
    private boolean isWindows() {
        return File.pathSeparatorChar==';';
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

    private WorkingArea setupRepositoryWithSubmodule() throws Exception {
        WorkingArea workingArea = new WorkingArea(createTempDirectoryWithoutSpaces());

        File repositoryDir = workingArea.file("dir-repository");
        File submoduleDir = workingArea.file("dir-submodule");

        assertTrue("did not create dir " + repositoryDir.getName(), repositoryDir.mkdir());
        assertTrue("did not create dir " + submoduleDir.getName(), submoduleDir.mkdir());

        WorkingArea submoduleWorkingArea = new WorkingArea(submoduleDir).init();

        for (int commit = 1; commit <= 5; commit++) {
            submoduleWorkingArea.touch("file", String.format("submodule content-%d", commit));
            submoduleWorkingArea.cgit().add("file");
            submoduleWorkingArea.cgit().commit(String.format("submodule commit-%d", commit));
        }

        WorkingArea repositoryWorkingArea = new WorkingArea(repositoryDir).init();

        repositoryWorkingArea.commitEmpty("init");

        repositoryWorkingArea.cgit().add(".");
        repositoryWorkingArea.cgit().addSubmodule("file://" + submoduleDir.getAbsolutePath(), "submodule");
        repositoryWorkingArea.cgit().commit("submodule");

        return workingArea;
    }
}
