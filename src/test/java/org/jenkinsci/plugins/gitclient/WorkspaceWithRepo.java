package org.jenkinsci.plugins.gitclient;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.Util;
import hudson.model.TaskListener;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.ObjectId;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class WorkspaceWithRepo {

    private GitClient gitClient;
    private File gitFileDir;
    private CliGitCommand cliGitCommand;
    protected TaskListener listener;
    private final String repoURL = "https://github.com/jenkinsci/git-client-plugin.git";

    public WorkspaceWithRepo(File gitDir, String gitImplName, TaskListener listener) throws Exception {
        createEnv(gitDir, gitImplName, listener);
        this.listener = listener;
    }

    private void createEnv(File gitDir, String gitImplName, TaskListener listener) throws Exception {
        gitClient = setupGitClient(gitDir, gitImplName, listener);
        gitFileDir = gitDir;
        cliGitCommand = new CliGitCommand(gitClient);
    }

    private GitClient setupGitClient(File gitFileDir, @NonNull String gitImplName, TaskListener listener) throws Exception {
        return Git.with(listener, new EnvVars()).in(gitFileDir).using(gitImplName).getClient();
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

    /**
     * Populate the local mirror of the git client plugin repository.
     * Returns path to the local mirror directory.
     *
     * @return path to the local mirrror directory
     * @throws IOException on I/O error
     * @throws InterruptedException when execption is interrupted
     */
    String localMirror() throws IOException, InterruptedException {
        File base = new File(".").getAbsoluteFile();
        for (File f = base; f != null; f = f.getParentFile()) {
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
                    cliGitCommand.run("clone", "--reference", f.getCanonicalPath(), "--mirror", repoURL, tempClonePath.toFile().getAbsolutePath());
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

    String launchCommand(String... args) throws IOException, InterruptedException {
        return launchCommand(false, args);
    }

    String launchCommand(boolean ignoreError, String... args) throws IOException, InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int returnCode = new Launcher.LocalLauncher(listener).launch().pwd(gitFileDir).cmds(args).
                envs(new EnvVars()).stdout(out).join();
        String output = out.toString();
        if (!ignoreError) {
            if (output == null || output.isEmpty()) {
                output = StringUtils.join(args, ' ');
            }
            /* Reports full output of failing commands */
            assertThat("Non-zero exit code. Output was " + output, returnCode, is(0));
        }
        return output;
    }

    void initBareRepo(GitClient gitClient, boolean bare) throws InterruptedException {
        gitClient.init_().workspace(gitFileDir.getAbsolutePath()).bare(bare).execute();
    }

    void cloneRepo(WorkspaceWithRepo workspace, String src) throws Exception {
        workspace.launchCommand("git", "clone", src, workspace.getGitFileDir().getAbsolutePath());
    }

    void touch(File gitDir, String fileName, String content) throws Exception {
        File f = new File(gitDir, fileName);
        f.createNewFile();
        FileUtils.writeStringToFile(f, content, "UTF-8");
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

    CliGitAPIImpl cgit() throws Exception {
        return (CliGitAPIImpl) Git.with(listener, new EnvVars()).in(gitFileDir).using("git").getClient();
    }

    JGitAPIImpl jgit() throws Exception {
        return (JGitAPIImpl) Git.with(listener, new EnvVars()).in(gitFileDir).using("jgit").getClient();
    }

    ObjectId head() throws IOException, InterruptedException {
        return gitClient.revParse("HEAD");
    }

    GitClient getGitClient() {
        return this.gitClient;
    }

    File getGitFileDir() {
        return this.gitFileDir;
    }

    File file(String path) {
        return new File(getGitFileDir(), path);
    }

    boolean exists(String path) {
        return file(path).exists();
    }

    String contentOf(String path) throws IOException {
        return FileUtils.readFileToString(file(path), "UTF-8");
    }

    CliGitCommand getCliGitCommand() {
        return this.cliGitCommand;
    }
}
