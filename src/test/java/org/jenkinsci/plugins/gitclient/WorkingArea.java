package org.jenkinsci.plugins.gitclient;

import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.plugins.git.IGitAPI;
import hudson.util.StreamTaskListener;
import junit.framework.TestCase;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * One local workspace of a Git repository on a temporary directory
 * that gets automatically cleaned up in the end.
 *
 * Every test case automatically gets one in {@link #w} but additional ones can be created if multi-repository
 * interactions need to be tested.
 */
public class WorkingArea implements IWorkingArea {
    final File repo;
    final GitClient git;
    final hudson.EnvVars env = new hudson.EnvVars();
    final TaskListener listener = StreamTaskListener.fromStdout();

    WorkingArea(File repo, GitClient git) throws Exception {
        this.repo = repo;
        this.git = git;
    }

    public String cmd(String args) throws IOException, InterruptedException {
        return launchCommand(args.split(" "));
    }

    public String launchCommand(String... args) throws IOException, InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int st = new Launcher.LocalLauncher(listener).launch().pwd(repo).cmds(args).
                envs(env).stdout(out).join();
        String s = out.toString();
        TestCase.assertEquals(s, 0, st);
        System.out.println(s);
        return s;
    }

    public String repoPath() {
        return repo.getAbsolutePath();
    }

    public WorkingArea init() throws IOException, InterruptedException {
        cmd("git init");
        return this;
    }

    public void add(String path) throws IOException, InterruptedException {
        cmd("git add " + path);
    }

    public void tag(String tag) throws IOException, InterruptedException {
        cmd("git tag " + tag);
    }

    public void commit(String msg) throws IOException, InterruptedException {
        cmd("git commit --allow-empty -m " + msg);
    }

    /**
     * Refers to a file in this workspace
     */
    public File file(String path) {
        return new File(repo, path);
    }

    public boolean exists(String path) {
        return file(path).exists();
    }

    /**
     * Creates a file in the workspace.
     */
    public void touch(String path) throws IOException {
        file(path).createNewFile();
    }

    /**
     * Creates a file in the workspace.
     */
    public File touch(String path, String content) throws IOException {
        File f = file(path);
        FileUtils.writeStringToFile(f, content);
        return f;
    }

    public void rm(String path) {
        file(path).delete();
    }

    public String contentOf(String path) throws IOException {
        return FileUtils.readFileToString(file(path));
    }

    /**
     * Creates a CGit implementation. Sometimes we need this for testing JGit impl.
     */
    public CliGitAPIImpl cgit() throws Exception {
        return (CliGitAPIImpl)Git.with(listener, env).in(repo).using("git").getClient();
    }

    /**
     * Creates a {@link org.eclipse.jgit.lib.Repository} object out of it.
     */
    public FileRepository repo() throws IOException {
        return new FileRepository(new File(repo,".git"));
    }

    public void checkout(String branch) throws IOException, InterruptedException {
        cmd("git checkout " + branch);
    }

    /**
     * Obtain the current HEAD revision
     */
    public ObjectId head() throws IOException, InterruptedException {
        return revParse("HEAD");
    }

    public ObjectId revParse(String commit) throws IOException, InterruptedException {
        return ObjectId.fromString(cmd("git rev-parse " + commit).substring(0,40));
    }

    /**
     * Casts the {@link #git} to {@link hudson.plugins.git.IGitAPI}
     */
    public IGitAPI igit() {
        return (IGitAPI)git;
    }
}
