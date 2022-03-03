package org.jenkinsci.plugins.gitclient;

import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.model.TaskListener;
import hudson.plugins.git.IGitAPI;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;
import org.objenesis.ObjenesisStd;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.junit.Assert.*;

public class GitAPITestUpdate {

	private final TemporaryDirectoryAllocator temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();

	private LogHandler handler = null;
	private int logCount = 0;
	private static String defaultBranchName = "mast" + "er"; // Intentionally split string
	private static String defaultRemoteBranchName = "origin/" + defaultBranchName;

	protected TaskListener listener;

	private static final String LOGGING_STARTED = "Logging started";
	private int checkoutTimeout = -1;
	private int submoduleUpdateTimeout = -1;

	protected hudson.EnvVars env = new hudson.EnvVars();

	private static boolean firstRun = true;

	protected GitClient setupGitAPI(File ws) throws Exception {
		return Git.with(listener, env).in(ws).using("jgit").getClient();
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

	private boolean timeoutVisibleInCurrentTest;

	protected void setTimeoutVisibleInCurrentTest(boolean visible) {
		timeoutVisibleInCurrentTest = visible;
	}

	@Test
	public void test_hasGitRepo_without_git_directory() throws Exception
	{
		setTimeoutVisibleInCurrentTest(false);
		assertFalse("Empty directory has a Git repo", w.git.hasGitRepo());
	}
}
