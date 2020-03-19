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

    protected String localMirror() throws IOException, InterruptedException {
        File base = new File(".").getAbsoluteFile();
        for (File f = base; f != null; f = f.getParentFile()) {
            File targetDir = new File(f, "target");
            if (targetDir.exists()) {
                String cloneDirName = "clone.git";
                File clone = new File(targetDir, cloneDirName);
                if (!clone.exists()) {
                    Path tempClonePath = Files.createTempDirectory(targetDir.toPath(), "clone-");
                    cliGitCommand.run("clone", "--reference", f.getCanonicalPath(), "--mirror", repoURL, tempClonePath.toFile().getAbsolutePath());
                    if (!clone.exists()) { // Still a race condition, but a narrow race handled by Files.move()
                        renameAndDeleteDir(tempClonePath, cloneDirName);
                    } else {
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

    CliGitCommand getCliGitCommand() {
        return this.cliGitCommand;
    }
}
