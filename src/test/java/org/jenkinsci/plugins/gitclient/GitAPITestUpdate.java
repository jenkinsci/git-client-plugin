package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;
import org.objenesis.ObjenesisStd;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.io.OutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.util.Collections.unmodifiableList;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public abstract class GitAPITestUpdate {

    private final TemporaryDirectoryAllocator temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();

    private LogHandler handler = null;
    private int logCount = 0;
    private static String defaultBranchName = "mast" + "er"; // Intentionally split string
    private static String defaultRemoteBranchName = "origin/" + defaultBranchName;
    private static final String ZIP_FILE_DEFAULT_BRANCH_NAME = "mast" + "er";

    private final String remoteMirrorURL = "https://github.com/jenkinsci/git-client-plugin.git";

    protected TaskListener listener;

    private static final String DEFAULT_JGIT_BRANCH_NAME = Constants.MASTER;
    private static final String DEFAULT_MIRROR_BRANCH_NAME = "mast" + "er"; // Intentionally split string

    private static final String LOGGING_STARTED = "Logging started";
    private int checkoutTimeout = -1;
    private int submoduleUpdateTimeout = -1;
    private final Random random = new Random();

    protected hudson.EnvVars env = new hudson.EnvVars();

    private static boolean firstRun = true;

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

    protected abstract GitClient setupGitAPI(File ws) throws Exception;

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

        GitAPITestUpdate.WorkingArea init() throws IOException, InterruptedException {
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

        GitAPITestUpdate.WorkingArea init(boolean bare) throws IOException, InterruptedException {
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

    @Before
    public void setUp() throws Exception {
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

    private List<File> tempDirsToDelete = new ArrayList<>();

    @After
    public void tearDown() throws Exception {
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

    private void checkGetHeadRev(String remote, String branchSpec, ObjectId expectedObjectId) throws Exception
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

    /**
     * Test getHeadRev with namespaces in the branch name
     * and branch specs starting with "refs/heads/".
     */
     @Test
    public void testGetHeadRevNamespacesWithRefsHeads() throws Exception {
        File tempRemoteDir = temporaryDirectoryAllocator.allocate();
        extract(new ZipFile("src/test/resources/namespaceBranchRepo.zip"), tempRemoteDir);
        Properties commits = parseLsRemote(new File("src/test/resources/namespaceBranchRepo.ls-remote"));
        w = clone(tempRemoteDir.getAbsolutePath());
        final String remote = tempRemoteDir.getAbsolutePath();

        final String[][] checkBranchSpecs = {
                {"refs/heads/" + ZIP_FILE_DEFAULT_BRANCH_NAME, commits.getProperty("refs/heads/" + ZIP_FILE_DEFAULT_BRANCH_NAME)},
                {"refs/heads/a_tests/b_namespace1/" + ZIP_FILE_DEFAULT_BRANCH_NAME, commits.getProperty("refs/heads/a_tests/b_namespace1/" + ZIP_FILE_DEFAULT_BRANCH_NAME)},
                {"refs/heads/a_tests/b_namespace2/" + ZIP_FILE_DEFAULT_BRANCH_NAME, commits.getProperty("refs/heads/a_tests/b_namespace2/" + ZIP_FILE_DEFAULT_BRANCH_NAME)},
                {"refs/heads/a_tests/b_namespace3/" + ZIP_FILE_DEFAULT_BRANCH_NAME, commits.getProperty("refs/heads/a_tests/b_namespace3/" + ZIP_FILE_DEFAULT_BRANCH_NAME)},
                {"refs/heads/b_namespace3/" + ZIP_FILE_DEFAULT_BRANCH_NAME, commits.getProperty("refs/heads/b_namespace3/" + ZIP_FILE_DEFAULT_BRANCH_NAME)}
        };

        for(String[] branch : checkBranchSpecs) {
            final ObjectId objectId = ObjectId.fromString(branch[1]);
            final String branchName = branch[0];
            checkGetHeadRev(remote, branchName, objectId);
        }
    }

    @Test
    public void testSubmoduleUpdateShallowWithDepth() throws Exception {
        WorkingArea remote = setupRepositoryWithSubmodule();
        w.git.clone_().url("file://" + remote.file("dir-repository").getAbsolutePath()).repositoryName("origin").execute();
        w.git.checkout().branch(defaultBranchName).ref(defaultRemoteBranchName).execute();
        w.git.submoduleInit();
        w.git.submoduleUpdate().shallow(true).depth(2).execute();

        boolean hasShallowSubmoduleSupport = w.git instanceof CliGitAPIImpl && w.cgit().isAtLeastVersion(1, 8, 4, 0);

        String shallow = Paths.get(".git", "modules", "submodule", "shallow").toString();
        assertEquals("shallow file existence: " + shallow, hasShallowSubmoduleSupport, w.exists(shallow));

        int localSubmoduleCommits = w.cgit().subGit("submodule").revList(defaultBranchName).size();
        int remoteSubmoduleCommits = remote.cgit().subGit("dir-submodule").revList(defaultBranchName).size();
        assertEquals("submodule commit count didn't match", hasShallowSubmoduleSupport ? 2 : remoteSubmoduleCommits, localSubmoduleCommits);
    }

    @NotImplementedInJGit
    @Test
    public void testSubmoduleUpdateWithThreads() throws Exception {
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
    @Test
    public void testTrackingSubmoduleBranches() throws Exception {
        if (! ((CliGitAPIImpl)w.git).isAtLeastVersion(1,8,2,0)) {
            setTimeoutVisibleInCurrentTest(false);
            System.err.println("git must be at least 1.8.2 to do tracking submodules.");
            return;
        }
        w.init(); // empty repository

        // create a new GIT repo.
        //    default branch  -- <file1>C
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
        r.git.checkout().ref(defaultBranchName).execute();

        r.git.branch("branch2");
        r.git.checkout().ref("branch2").execute();
        r.touch("file3", "content3");
        r.git.add("file3");
        r.git.commit("submod-commit3");
        r.git.checkout().ref(defaultBranchName).execute();

        // Setup variables for use in tests
        String submodDir = "submod1" + UUID.randomUUID();
        String subFile1 = submodDir + File.separator + "file1";
        String subFile2 = submodDir + File.separator + "file2";
        String subFile3 = submodDir + File.separator + "file3";

        // Add new GIT repo to w, at the default branch
        w.git.addSubmodule(r.repoPath(), submodDir);
        w.git.submoduleInit();
        assertTrue("file1 does not exist and should be we imported the submodule.", w.exists(subFile1));
        assertFalse("file2 exists and should not because not on 'branch1'", w.exists(subFile2));
        assertFalse("file3 exists and should not because not on 'branch2'", w.exists(subFile3));

        // Windows tests fail if repoPath is more than 200 characters
        // CLI git support for longpaths on Windows is complicated
        if (w.repoPath().length() > 200) {
            return;
        }

        // Switch to branch1
        submoduleUpdateTimeout = 1 + random.nextInt(60 * 24);
        w.git.submoduleUpdate().remoteTracking(true).useBranch(submodDir, "branch1").timeout(submoduleUpdateTimeout).execute();
        assertTrue("file2 does not exist and should because on branch1", w.exists(subFile2));
        assertFalse("file3 exists and should not because not on 'branch2'", w.exists(subFile3));

        // Switch to branch2
        w.git.submoduleUpdate().remoteTracking(true).useBranch(submodDir, "branch2").timeout(submoduleUpdateTimeout).execute();
        assertFalse("file2 exists and should not because not on 'branch1'", w.exists(subFile2));
        assertTrue("file3 does not exist and should because on branch2", w.exists(subFile3));

        // Switch to default branch
        w.git.submoduleUpdate().remoteTracking(true).useBranch(submodDir, defaultBranchName).timeout(submoduleUpdateTimeout).execute();
        assertFalse("file2 exists and should not because not on 'branch1'", w.exists(subFile2));
        assertFalse("file3 exists and should not because not on 'branch2'", w.exists(subFile3));
    }


    /**
     * Test getRemoteReferences with listing all references
     */
     @Test
    public void testGetRemoteReferences() throws Exception {
        Map<String, ObjectId> references = w.git.getRemoteReferences(remoteMirrorURL, null, false, false);
        assertTrue(references.containsKey("refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME));
        assertTrue(references.containsKey("refs/tags/git-client-1.0.0"));
    }

    @NotImplementedInJGit
    @Test
    public void testSparseCheckout() throws Exception {
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
        workingArea.git.checkout().ref(defaultRemoteBranchName).branch(defaultBranchName).deleteBranchIfExist(true).sparseCheckoutPaths(Collections.singletonList("dir1")).timeout(checkoutTimeout).execute();
        assertTrue(workingArea.exists("dir1"));
        assertFalse(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea.git.checkout().ref(defaultRemoteBranchName).branch(defaultBranchName).deleteBranchIfExist(true).sparseCheckoutPaths(Collections.singletonList("dir2")).timeout(checkoutTimeout).execute();
        assertFalse(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea.git.checkout().ref(defaultRemoteBranchName).branch(defaultBranchName).deleteBranchIfExist(true).sparseCheckoutPaths(Arrays.asList("dir1", "dir2")).timeout(checkoutTimeout).execute();
        assertTrue(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea.git.checkout().ref(defaultRemoteBranchName).branch(defaultBranchName).deleteBranchIfExist(true).sparseCheckoutPaths(Collections.emptyList()).timeout(checkoutTimeout).execute();
        assertTrue(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertTrue(workingArea.exists("dir3"));

        workingArea.git.checkout().ref(defaultRemoteBranchName).branch(defaultBranchName).deleteBranchIfExist(true).sparseCheckoutPaths(null)
                .timeout(checkoutTimeout)
                .execute();
        assertTrue(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertTrue(workingArea.exists("dir3"));
    }

    /**
     * Test getRemoteReferences with listing references limit to refs/heads or refs/tags
     */
     @Test
    public void testGetRemoteReferencesWithLimitReferences() throws Exception {
        Map<String, ObjectId> references = w.git.getRemoteReferences(remoteMirrorURL, null, true, false);
        assertTrue(references.containsKey("refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME));
        assertFalse(references.containsKey("refs/tags/git-client-1.0.0"));
        references = w.git.getRemoteReferences(remoteMirrorURL, null, false, true);
        assertFalse(references.containsKey("refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME));
        assertTrue(references.containsKey("refs/tags/git-client-1.0.0"));
        for (String key : references.keySet()) {
            assertFalse(key.endsWith("^{}"));
        }
    }

    @Deprecated
    @Test
    public void testMergeRefspec() throws Exception {
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
     @Test
    public void testGetHeadRevFromPublicRepoWithInvalidCredential() throws Exception {
        GitClient remoteGit = Git.with(listener, env).using("git").getClient();
        StandardUsernamePasswordCredentials testCredential = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "bad-id", "bad-desc", "bad-user", "bad-password");
        remoteGit.addDefaultCredentials(testCredential);
        Map<String, ObjectId> heads = remoteGit.getHeadRev(remoteMirrorURL);
        ObjectId defaultBranch = w.git.getHeadRev(remoteMirrorURL, "refs/heads/" + defaultBranchName);
        assertEquals("URL is " + remoteMirrorURL + ", heads is " + heads, defaultBranch, heads.get("refs/heads/" + defaultBranchName));
    }

    /**
     * Test getHeadRev with namespaces in the branch name
     * and branch specs containing only the simple branch name.
     *
     * TODO: This does not work yet! Fix behaviour and enable test!
     */
     @Test
    public void testGetHeadRevNamespacesWithSimpleBranchNames() throws Exception {
        setTimeoutVisibleInCurrentTest(false);
        File tempRemoteDir = temporaryDirectoryAllocator.allocate();
        extract(new ZipFile("src/test/resources/namespaceBranchRepo.zip"), tempRemoteDir);
        Properties commits = parseLsRemote(new File("src/test/resources/namespaceBranchRepo.ls-remote"));
        w = clone(tempRemoteDir.getAbsolutePath());
        final String remote = tempRemoteDir.getAbsolutePath();

        final String[][] checkBranchSpecs =
                //TODO: Fix and enable test
                {
                        {"a_tests/b_namespace1/" + ZIP_FILE_DEFAULT_BRANCH_NAME, commits.getProperty("refs/heads/a_tests/b_namespace1/" + ZIP_FILE_DEFAULT_BRANCH_NAME)},
                        // {"a_tests/b_namespace2/" + ZIP_FILE_DEFAULT_BRANCH_NAME, commits.getProperty("refs/heads/a_tests/b_namespace2/" + ZIP_FILE_DEFAULT_BRANCH_NAME)},
                        // {"a_tests/b_namespace3/" + ZIP_FILE_DEFAULT_BRANCH_NAME, commits.getProperty("refs/heads/a_tests/b_namespace3/" + ZIP_FILE_DEFAULT_BRANCH_NAME)},
                        // {"b_namespace3/" + ZIP_FILE_DEFAULT_BRANCH_NAME, commits.getProperty("refs/heads/b_namespace3/" + ZIP_FILE_DEFAULT_BRANCH_NAME)},
                        // {defaultBranchName, commits.getProperty("refs/heads/" + ZIP_FILE_DEFAULT_BRANCH_NAME)},
                };

        for(String[] branch : checkBranchSpecs) {
            final ObjectId objectId = ObjectId.fromString(branch[1]);
            final String branchName = branch[0];
            checkGetHeadRev(remote, branchName, objectId);
            checkGetHeadRev(remote, "remotes/origin/" + branchName, objectId);
            checkGetHeadRev(remote, "refs/heads/" + branchName, objectId);
        }
    }

    protected abstract boolean hasWorkingGetRemoteSymbolicReferences();

    private Properties parseLsRemote(File file) throws IOException
    {
        Properties properties = new Properties();
        Pattern pattern = Pattern.compile("([a-f0-9]{40})\\s*(.*)");
        for(String lineO : FileUtils.readLines(file, StandardCharsets.UTF_8)) {
            String line = lineO.trim();
            Matcher matcher = pattern.matcher(line);
            if(matcher.matches()) {
                properties.setProperty(matcher.group(2), matcher.group(1));
            } else {
                System.err.println("ls-remote pattern does not match '" + line + "'");
            }
        }
        return properties;
    }

    protected abstract String getRemoteBranchPrefix();

    /**
     * Test getRemoteSymbolicReferences with listing all references
     */
     @Test
    public void testGetRemoteSymbolicReferencesWithMatchingPattern() throws Exception {
        if (!hasWorkingGetRemoteSymbolicReferences()) return; // JUnit 3 replacement for assumeThat
        Map<String, String> references = w.git.getRemoteSymbolicReferences(remoteMirrorURL, Constants.HEAD);
        assertThat(references, hasEntry(is(Constants.HEAD), is(Constants.R_HEADS + DEFAULT_JGIT_BRANCH_NAME)));
        assertThat(references.size(), is(1));
    }

    @Deprecated
    @Test
    public void testPushDeprecatedSignature() throws Exception {
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


    private void assertExceptionMessageContains(GitException ge, String expectedSubstring) {
        String actual = ge.getMessage().toLowerCase();
        assertTrue("Expected '" + expectedSubstring + "' exception message, but was: " + actual, actual.contains(expectedSubstring));
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

    /* Check JENKINS-23424 - inconsistent handling of modified tracked
     * files when performing a checkout in an existing directory.
     * CliGitAPIImpl reverts tracked files, while JGitAPIImpl does
     * not.
     */
    private void baseCheckoutReplacesTrackedChanges(boolean defineBranch) throws Exception {
        w.git.clone_().url(localMirror()).repositoryName("JENKINS-23424").execute();
        w.git.checkout().ref("JENKINS-23424/" + DEFAULT_MIRROR_BRANCH_NAME).branch(DEFAULT_MIRROR_BRANCH_NAME).execute();
        if (defineBranch) {
            w.git.checkout().branch(defaultBranchName).ref("JENKINS-23424/" + DEFAULT_MIRROR_BRANCH_NAME).deleteBranchIfExist(true).execute();
        } else {
            w.git.checkout().ref("JENKINS-23424/" + DEFAULT_MIRROR_BRANCH_NAME).deleteBranchIfExist(true).execute();
        }

        /* Confirm first checkout */
        String pomContent = w.contentOf("pom.xml");
        assertTrue("Missing inceptionYear ref in pom : " + pomContent, pomContent.contains("inceptionYear"));
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

    @Deprecated
    @Test
    public void testLsTreeNonRecursive() throws IOException, InterruptedException {
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
    @Test
    public void testLsTreeRecursive() throws IOException, InterruptedException {
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

    @NotImplementedInJGit
    @Test
    public void testTrackingSubmodule() throws Exception {
        if (! ((CliGitAPIImpl)w.git).isAtLeastVersion(1,8,2,0)) {
            System.err.println("git must be at least 1.8.2 to do tracking submodules.");
            return;
        }
        w.init(); // empty repository

        // create a new GIT repo.
        //   default branch -- <file1>C  <file2>C
        WorkingArea r = new WorkingArea(createTempDirectoryWithoutSpaces());
        r.init();
        r.touch("file1", "content1");
        r.git.add("file1");
        r.git.commit("submod-commit1");

        // Add new GIT repo to w
        String subModDir = "submod1-" + UUID.randomUUID();
        w.git.addSubmodule(r.repoPath(), subModDir);
        w.git.submoduleInit();

        // Add a new file to the separate GIT repo.
        r.touch("file2", "content2");
        r.git.add("file2");
        r.git.commit("submod-branch1-commit1");

        // Make sure that the new file doesn't exist in the repo with remoteTracking
        String subFile = subModDir + File.separator + "file2";
        w.git.submoduleUpdate().recursive(true).remoteTracking(false).execute();
        assertFalse("file2 exists and should not because we didn't update to the tip of the default branch.", w.exists(subFile));

        // Windows tests fail if repoPath is more than 200 characters
        // CLI git support for longpaths on Windows is complicated
        if (w.repoPath().length() > 200) {
            return;
        }

        // Run submodule update with remote tracking
        w.git.submoduleUpdate().recursive(true).remoteTracking(true).execute();
        assertTrue("file2 does not exist and should because we updated to the tip of the default branch.", w.exists(subFile));
        assertFixSubmoduleUrlsThrows();
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
                     OutputStream out = Files.newOutputStream(entryDestination.toPath())) {
                    org.apache.commons.io.IOUtils.copy(in, out);
                }
            }
        }
    }

    private void checkHeadRev(String repoURL, ObjectId expectedId) throws InterruptedException {
        final ObjectId originDefaultBranch = w.git.getHeadRev(repoURL, DEFAULT_MIRROR_BRANCH_NAME);
        assertEquals("origin default branch mismatch", expectedId, originDefaultBranch);

        final ObjectId simpleDefaultBranch = w.git.getHeadRev(repoURL, DEFAULT_MIRROR_BRANCH_NAME);
        assertEquals("simple default branch mismatch", expectedId, simpleDefaultBranch);

        final ObjectId wildcardSCMDefaultBranch = w.git.getHeadRev(repoURL, "*/" + DEFAULT_MIRROR_BRANCH_NAME);
        assertEquals("wildcard SCM default branch mismatch", expectedId, wildcardSCMDefaultBranch);

        /* This assertion may fail if the localMirror has more than
         * one branch matching the wildcard expression in the call to
         * getHeadRev.  The expression is chosen to be unlikely to
         * match with typical branch names, while still matching a
         * known branch name. Should be fine so long as no one creates
         * branches named like main-default-branch or new-default-branch on the
         * remote repo.
         * 'origin/main' becomes 'origin/m*i?'
         */
        final ObjectId wildcardEndDefaultBranch = w.git.getHeadRev(repoURL, DEFAULT_MIRROR_BRANCH_NAME.replace('a', '*').replace('t', '?').replace('n', '?'));
        assertEquals("wildcard end default branch mismatch", expectedId, wildcardEndDefaultBranch);
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

    protected void setTimeoutVisibleInCurrentTest(boolean visible) {
        timeoutVisibleInCurrentTest = visible;
    }

    /**
     * Test getRemoteReferences with matching pattern
     */
    @Test
    public void testGetRemoteReferencesWithMatchingPattern() throws Exception {
        Map<String, ObjectId> references = w.git.getRemoteReferences(remoteMirrorURL, "refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME, true, false);
        assertTrue(references.containsKey("refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME));
        assertFalse(references.containsKey("refs/tags/git-client-1.0.0"));
        references = w.git.getRemoteReferences(remoteMirrorURL, "git-client-*", false, true);
        assertFalse(references.containsKey("refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME));
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

    @Issue("JENKINS-23424")
    @Test
    public void testCheckoutReplacesTrackedChanges() throws Exception {
        baseCheckoutReplacesTrackedChanges(false);
    }

    @Issue("JENKINS-23424")
    @Test
    public void testCheckoutReplacesTrackedChangesWithBranch() throws Exception {
        baseCheckoutReplacesTrackedChanges(true);
    }

    @Test
    public void testGetHeadRevRemote() throws Exception {
        String lsRemote = w.launchCommand("git", "ls-remote", "-h", remoteMirrorURL, "refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME);
        ObjectId lsRemoteId = ObjectId.fromString(lsRemote.substring(0, 40));
        checkHeadRev(remoteMirrorURL, lsRemoteId);
    }

    @Test
    public void testHasGitRepoWithoutGitDirectory() throws Exception
    {
        setTimeoutVisibleInCurrentTest(false);
        assertFalse("Empty directory has a Git repo", w.git.hasGitRepo());
    }

    @Issue("JENKINS-22343")
    @Test
    public void testShowRevisionForFirstCommit() throws Exception {
        w.init();
        w.touch("a");
        w.git.add("a");
        w.git.commit("first");
        ObjectId first = w.head();
        List<String> revisionDetails = w.git.showRevision(first);
        List<String> commits =
                revisionDetails.stream()
                        .filter(detail -> detail.startsWith("commit "))
                        .collect(Collectors.toList());
        assertTrue("Commits '" + commits + "' missing " + first.getName(), commits.contains("commit " + first.getName()));
        assertEquals("Commits '" + commits + "' wrong size", 1, commits.size());
    }

    /**
     * Test parsing of changelog with unicode characters in commit messages.
     */
    @Deprecated
    @Test
    public void testReset() throws IOException, InterruptedException {
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


    @Issue({"JENKINS-6203", "JENKINS-14798", "JENKINS-23091"})
    @Test
    public void testUnicodeCharsInChangelog() throws Exception {
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

    @Deprecated
    @Test
    public void testGetDefaultRemote() throws Exception {
        w.init();
        w.launchCommand("git", "remote", "add", "origin", "https://github.com/jenkinsci/git-client-plugin.git");
        w.launchCommand("git", "remote", "add", "ndeloof", "git@github.com:ndeloof/git-client-plugin.git");
        assertEquals("Wrong origin default remote", "origin", w.igit().getDefaultRemote("origin"));
        assertEquals("Wrong ndeloof default remote", "ndeloof", w.igit().getDefaultRemote("ndeloof"));
        /* CliGitAPIImpl and JGitAPIImpl return different ordered lists for default remote if invalid */
        assertEquals("Wrong invalid default remote", w.git instanceof CliGitAPIImpl ? "ndeloof" : "origin",
                w.igit().getDefaultRemote("invalid"));
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
    @Test
    public void testGitInitCreatesDirectoryIfNeeded() throws Exception {
        File nonexistentDir = new File(UUID.randomUUID().toString());
        assertFalse("Dir unexpectedly exists at start of test", nonexistentDir.exists());
        try {
            GitClient git = setupGitAPI(nonexistentDir);
            git.init();
        } finally {
            FileUtils.deleteDirectory(nonexistentDir);
        }
    }

    private void checkBoundedChangelogSha1(final String sha1Begin, final String sha1End, final String branchName) throws InterruptedException
    {
        StringWriter writer = new StringWriter();
        w.git.changelog(sha1Begin, sha1End, writer);
        String splitLog[] = writer.toString().split("[\\n\\r]", 3); // Extract first line of changelog
        assertEquals("Wrong bounded changelog line 1 on branch " + branchName, "commit " + sha1End, splitLog[0]);
        assertTrue("Begin sha1 " + sha1Begin + " not in changelog: " + writer, writer.toString().contains(sha1Begin));
    }

    @Test
    public void testGetHeadRevCurrentDirectory() throws Exception {
        w = clone(localMirror());
        w.git.checkout().ref("master").execute();
        final ObjectId defaultBranch = w.head();

        w.git.branch("branch1");
        w.git.checkout().ref("branch1").execute();
        w.touch("file1", "branch1 contents " + UUID.randomUUID());
        w.git.add("file1");
        w.git.commit("commit1-branch1");
        final ObjectId branch1 = w.head();

        Map<String, ObjectId> heads = w.git.getHeadRev(w.repoPath());
        assertEquals(defaultBranch, heads.get("refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME));
        assertEquals(branch1, heads.get("refs/heads/branch1"));

        checkHeadRev(w.repoPath(), getMirrorHead());
    }

    @Test
    public void testShowRevisionForMergeExcludeFiles() throws Exception {
        w = clone(localMirror());
        ObjectId from = ObjectId.fromString("45e76942914664ee19f31d90e6f2edbfe0d13a46");
        ObjectId to = ObjectId.fromString("b53374617e85537ec46f86911b5efe3e4e2fa54b");
        Boolean useRawOutput = false;

        List<String> revisionDetails = w.git.showRevision(from, to, useRawOutput);

        List<String> commits =
                revisionDetails.stream()
                        .filter(detail -> detail.startsWith("commit "))
                        .collect(Collectors.toList());
        assertEquals(2, commits.size());
        assertTrue(commits.contains("commit 4f2964e476776cf59be3e033310f9177bedbf6a8"));
        assertTrue(commits.contains("commit b53374617e85537ec46f86911b5efe3e4e2fa54b"));

        List<String> diffs =
                revisionDetails.stream()
                        .filter(detail -> detail.startsWith(":"))
                        .collect(Collectors.toList());

        assertTrue(diffs.isEmpty());
    }

    @Test
    public void testChangelogBounded() throws Exception {
        w = clone(localMirror());
        String sha1Prev = w.git.revParse("HEAD").name();
        w.touch("changelog-file", "changelog-file-content-" + sha1Prev);
        w.git.add("changelog-file");
        w.git.commit("changelog-commit-message");
        String sha1 = w.git.revParse("HEAD").name();
        checkBoundedChangelogSha1(sha1Prev, sha1, DEFAULT_MIRROR_BRANCH_NAME);
    }

    @Test
    public void testShowRevisionForSingleCommit() throws Exception {
        w = clone(localMirror());
        ObjectId to = ObjectId.fromString("51de9eda47ca8dcf03b2af58dfff7355585f0d0c");
        List<String> revisionDetails = w.git.showRevision(null, to);
        List<String> commits =
                revisionDetails.stream()
                        .filter(detail -> detail.startsWith("commit "))
                        .collect(Collectors.toList());
        assertEquals(1, commits.size());
        assertTrue(commits.contains("commit 51de9eda47ca8dcf03b2af58dfff7355585f0d0c"));
    }

    @Test
    public void testRevListRemoteBranch() throws Exception {
        w = clone(localMirror());
        List<ObjectId> revList = w.git.revList("origin/1.4.x");
        assertEquals("Wrong list size: " + revList, 267, revList.size());
        Ref branchRef = w.repo().findRef("origin/1.4.x");
        assertTrue("origin/1.4.x not in revList", revList.contains(branchRef.getObjectId()));
    }

    /**
     * Test getRemoteSymbolicReferences with listing all references
     */
    @Test
    public void testGetRemoteSymbolicReferences() throws Exception {
        if (!hasWorkingGetRemoteSymbolicReferences()) return; // JUnit 3 replacement for assumeThat
        Map<String, String> references = w.git.getRemoteSymbolicReferences(remoteMirrorURL, null);
        assertThat(references, hasEntry(is(Constants.HEAD), is(Constants.R_HEADS + DEFAULT_JGIT_BRANCH_NAME)));
    }

    @Test
    public void testGetHeadRevLocalMirror() throws Exception {
        checkHeadRev(localMirror(), getMirrorHead());
    }

    @Test
    public void testCheckoutNullRef() throws Exception {
        w = clone(localMirror());
        String branches = w.launchCommand("git", "branch", "-l");
        assertTrue("default branch not current branch in " + branches, branches.contains("* " + DEFAULT_MIRROR_BRANCH_NAME));
        final String branchName = "test-checkout-null-ref-branch-" + UUID.randomUUID();
        branches = w.launchCommand("git", "branch", "-l");
        assertFalse("test branch originally listed in " + branches, branches.contains(branchName));
        w.git.checkout().ref(null).branch(branchName).execute();
        branches = w.launchCommand("git", "branch", "-l");
        assertTrue("test branch not current branch in " + branches, branches.contains("* " + branchName));
    }

    @Test
    public void testShowRevisionForMerge() throws Exception {
        w = clone(localMirror());
        ObjectId from = ObjectId.fromString("45e76942914664ee19f31d90e6f2edbfe0d13a46");
        ObjectId to = ObjectId.fromString("b53374617e85537ec46f86911b5efe3e4e2fa54b");

        List<String> revisionDetails = w.git.showRevision(from, to);

        List<String> commits =
                revisionDetails.stream()
                        .filter(detail -> detail.startsWith("commit "))
                        .collect(Collectors.toList());
        assertEquals(3, commits.size());
        assertTrue(commits.contains("commit 4f2964e476776cf59be3e033310f9177bedbf6a8"));
        // Merge commit is duplicated as have to capture changes that may have been made as part of merge
        assertTrue(commits.contains("commit b53374617e85537ec46f86911b5efe3e4e2fa54b (from 4f2964e476776cf59be3e033310f9177bedbf6a8)"));
        assertTrue(commits.contains("commit b53374617e85537ec46f86911b5efe3e4e2fa54b (from 45e76942914664ee19f31d90e6f2edbfe0d13a46)"));

        List<String> paths =
                revisionDetails.stream()
                        .filter(detail -> detail.startsWith(":"))
                        .map(diff -> diff.substring(diff.indexOf('\t') + 1).trim()) // Windows diff output ^M removed by trim()
                        .collect(Collectors.toList());

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
    @Test
    public void testGetAllLogEntries() throws Exception {
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

    @Test
    public void testGetHeadRevReturnsAccurateSHA1Values() throws Exception {
        /* CliGitAPIImpl had a longstanding bug that it inserted the
         * same SHA1 in all the values, rather than inserting the SHA1
         * which matched the key.
         */
        w = clone(localMirror());
        w.git.checkout().ref(DEFAULT_MIRROR_BRANCH_NAME).execute(); // Depends on default branch name of local mirror
        final ObjectId defaultBranch = w.head();

        w.git.branch("branch1");
        w.git.checkout().ref("branch1").execute();
        w.touch("file1", "content1");
        w.git.add("file1");
        w.git.commit("commit1-branch1");
        final ObjectId branch1 = w.head();

        w.launchCommand("git", "branch", "branch.2", DEFAULT_MIRROR_BRANCH_NAME);
        w.git.checkout().ref("branch.2").execute();
        File f = w.touch("file.2", "content2");
        w.git.add("file.2");
        w.git.commit("commit2-branch.2");
        final ObjectId branchDot2 = w.head();
        assertTrue("file.2 does not exist", f.exists());

        Map<String,ObjectId> heads = w.git.getHeadRev(w.repoPath());
        assertEquals("Wrong default branch in " + heads, defaultBranch, heads.get("refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME));
        assertEquals("Wrong branch1 in " + heads, branch1, heads.get("refs/heads/branch1"));
        assertEquals("Wrong branch.2 in " + heads, branchDot2, heads.get("refs/heads/branch.2"));

        assertEquals("wildcard branch.2 mismatch", branchDot2, w.git.getHeadRev(w.repoPath(), "br*.2"));

        checkHeadRev(w.repoPath(), getMirrorHead());
    }

    @Issue("JENKINS-37185")
    @NotImplementedInJGit /* JGit doesn't have timeout */
    @Test
    public void testCheckoutHonorTimeout() throws Exception {
        w = clone(localMirror());

        checkoutTimeout = 1 + random.nextInt(60 * 24);
        w.git.checkout().branch(DEFAULT_MIRROR_BRANCH_NAME).ref("origin/" + DEFAULT_MIRROR_BRANCH_NAME).timeout(checkoutTimeout).deleteBranchIfExist(true).execute();
    }

    @Test
    public void testCheckout() throws Exception {
        w = clone(localMirror());
        String branches = w.launchCommand("git", "branch", "-l");
        assertTrue("default branch not current branch in " + branches, branches.contains("* " + DEFAULT_MIRROR_BRANCH_NAME));
        final String branchName = "test-checkout-branch-" + UUID.randomUUID();
        branches = w.launchCommand("git", "branch", "-l");
        assertFalse("test branch originally listed in " + branches, branches.contains(branchName));
        w.git.checkout().ref("6b7bbcb8f0e51668ddba349b683fb06b4bd9d0ea").branch(branchName).execute(); // git-client-1.6.0
        branches = w.launchCommand("git", "branch", "-l");
        assertTrue("test branch not current branch in " + branches, branches.contains("* " + branchName));
        String sha1 = w.git.revParse("HEAD").name();
        String sha1Expected = "6b7bbcb8f0e51668ddba349b683fb06b4bd9d0ea";
        assertEquals("Wrong SHA1 as checkout of git-client-1.6.0", sha1Expected, sha1);
    }

    /**
     * UT for {@link GitClient#getBranchesContaining(String, boolean)}. The main
     * testing case is retrieving remote branches.
     * @throws Exception on exceptions occur
     */
     @Test
    public void testBranchContainingRemote() throws Exception {
        final WorkingArea r = new WorkingArea();
        r.init();

        r.commitEmpty("c1");
        ObjectId c1 = r.head();

        w.git.clone_().url("file://" + r.repoPath()).execute();
        final URIish remote = new URIish(Constants.DEFAULT_REMOTE_NAME);
        final List<RefSpec> refspecs = Collections.singletonList(new RefSpec(
                "refs/heads/*:refs/remotes/origin/*"));
        final String remoteBranch = getRemoteBranchPrefix() + Constants.DEFAULT_REMOTE_NAME + "/"
                + defaultBranchName;
        final String bothBranches = defaultBranchName + "," + "origin/" + defaultBranchName;
        w.git.fetch_().from(remote, refspecs).execute();
        checkoutTimeout = 1 + random.nextInt(60 * 24);
        w.git.checkout().ref(defaultBranchName).timeout(checkoutTimeout).execute();

        assertEquals(defaultBranchName, formatBranches(w.git.getBranchesContaining(c1.name(), false)));
        if (!(w.git instanceof CliGitAPIImpl)) { // Branch names incorrect in some CLI git cases
            assertEquals(bothBranches, formatBranches(w.git.getBranchesContaining(c1.name(), true)));
        }

        r.commitEmpty("c2");
        ObjectId c2 = r.head();
        w.git.fetch_().from(remote, refspecs).execute();
        assertEquals("", formatBranches(w.git.getBranchesContaining(c2.name(), false)));
        assertEquals(remoteBranch, formatBranches(w.git.getBranchesContaining(c2.name(), true)));
    }

    private String formatBranches(List<Branch> branches) {
        Set<String> names = new TreeSet<>();
        for (Branch b : branches) {
            names.add(b.getName());
        }
        return String.join(",", names);
    }

    @Test
    public void testCheckoutBranchFailure() throws Exception {
        w = clone(localMirror());
        File lock = new File(w.repo, ".git/index.lock");
        try {
            FileUtils.touch(lock);
            w.git.checkoutBranch("somebranch", DEFAULT_MIRROR_BRANCH_NAME);
            fail();
        } catch (GitLockFailedException e) {
            // expected
        } finally {
            lock.delete();
        }
    }

    @Issue("JENKINS-40023")
    @Test
    public void testChangelogWithMergeCommitAndMaxLogHistory() throws Exception {
        w.init();
        w.commitEmpty("init");

        // First commit to branch-1
        w.git.branch("branch-1");
        w.git.checkout().ref("branch-1").execute();
        w.touch("file-1", "content-1");
        w.git.add("file-1");
        w.git.commit("commit-1");
        String commitSha1 = w.git.revParse("HEAD").name();

        // Merge branch-1 into default branch
        w.git.checkout().ref(defaultBranchName).execute();
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
