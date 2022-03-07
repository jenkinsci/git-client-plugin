package org.jenkinsci.plugins.gitclient;

import static org.apache.commons.lang.StringUtils.isBlank;
import hudson.Launcher;
import hudson.Util;
import hudson.ProxyConfiguration;
import hudson.model.TaskListener;
import hudson.plugins.git.IGitAPI;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import junit.framework.TestCase;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
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

    // Fails on both JGit and CliGit, though with different failure modes
    // @Deprecated
    // public void test_isBareRepository_working_repoPath() throws IOException, InterruptedException {
    //     w.init();
    //     w.commitEmpty("Not-a-bare-repository-working-repoPath-dot-git");
    //     assertFalse("repoPath is a bare repository", w.igit().isBareRepository(w.repoPath()));
    //     assertFalse("abs(.) is a bare repository", w.igit().isBareRepository(w.file(".").getAbsolutePath()));
    // }
}
