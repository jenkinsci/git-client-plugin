package org.jenkinsci.plugins.gitclient;

import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IGitAPI;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;
import org.objenesis.ObjenesisStd;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class GitAPITestUpdate {

	private final TemporaryDirectoryAllocator temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();

	private LogHandler handler = null;
	private int logCount = 0;
	private static String defaultBranchName = "mast" + "er"; // Intentionally split string
	private static String defaultRemoteBranchName = "origin/" + defaultBranchName;

	private final String remoteMirrorURL = "https://github.com/jenkinsci/git-client-plugin.git";

	protected TaskListener listener;

	private static final String DEFAULT_MIRROR_BRANCH_NAME = "mast" + "er"; // Intentionally split string

	private static final String LOGGING_STARTED = "Logging started";
	private int checkoutTimeout = -1;
	private int submoduleUpdateTimeout = -1;

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

	protected GitClient setupGitAPI(File ws) throws Exception {
		return Git.with(listener, env).in(ws).using("git").getClient();
	}

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

	private void assertExceptionMessageContains(GitException ge, String expectedSubstring) {
		String actual = ge.getMessage().toLowerCase();
		assertTrue("Expected '" + expectedSubstring + "' exception message, but was: " + actual, actual.contains(expectedSubstring));
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

	private void check_headRev(String repoURL, ObjectId expectedId) throws InterruptedException {
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

	protected void setTimeoutVisibleInCurrentTest(boolean visible) {
		timeoutVisibleInCurrentTest = visible;
	}

	@Test
	public void test_getRemoteReferences_withMatchingPattern() throws Exception {
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

	@Test
	public void test_getHeadRev_remote() throws Exception {
		String lsRemote = w.launchCommand("git", "ls-remote", "-h", remoteMirrorURL, "refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME);
		ObjectId lsRemoteId = ObjectId.fromString(lsRemote.substring(0, 40));
		check_headRev(remoteMirrorURL, lsRemoteId);
	}

	@Test
	public void test_hasGitRepo_without_git_directory() throws Exception
	{
		setTimeoutVisibleInCurrentTest(false);
		assertFalse("Empty directory has a Git repo", w.git.hasGitRepo());
	}

	@Issue("JENKINS-22343")
	@Test
	public void test_show_revision_for_first_commit() throws Exception {
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

	@Deprecated
	@Test
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


	@Issue({"JENKINS-6203", "JENKINS-14798", "JENKINS-23091"})
	@Test
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

	@Deprecated
	@Test
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

	@Test
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
}
