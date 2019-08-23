package hudson.plugins.git;

import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

import hudson.EnvVars;
import hudson.model.TaskListener;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static java.nio.file.StandardOpenOption.APPEND;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import static org.junit.Assert.*;
import org.junit.rules.TemporaryFolder;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assume.*;

public class GitExceptionTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void throwsGitException() {
        String message = null;
        thrown.expect(GitException.class);
        thrown.expectMessage(is(message));
        throw new GitException();
    }

    @Test
    public void throwsGitExceptionExpectedMessage() {
        String message = "My custom git exception message";
        thrown.expect(GitException.class);
        thrown.expectMessage(is(message));
        throw new GitException(message);
    }

    @Test
    public void throwsGitExceptionExpectedMessageWithCause() {
        String message = "My custom git exception message";
        thrown.expect(GitException.class);
        thrown.expectMessage(is(message));
        thrown.expectCause(is(IOException.class));
        throw new GitException(message, new IOException("Custom IOException message"));
    }

    @Test
    public void initDefaultImplThrowsGitException() throws GitAPIException, IOException, InterruptedException {
        File badDirectory = new File("/this/is/a/bad/dir");
        if (isWindows()) {
            badDirectory = new File("\\\\badserver\\badshare\\bad\\dir");
        }
        GitClient defaultClient = Git.with(TaskListener.NULL, new EnvVars()).in(badDirectory).using("Default").getClient();
        assertNotNull(defaultClient);
        thrown.expect(GitException.class);
        defaultClient.init_().workspace(badDirectory.getAbsolutePath()).execute();
    }

    @Test
    public void initCliImplThrowsGitException() throws GitAPIException, IOException, InterruptedException {
        File badDirectory = new File("/this/is/a/bad/dir");
        if (isWindows()) {
            badDirectory = new File("\\\\badserver\\badshare\\bad\\dir");
        }
        assumeFalse("running as root?", new File("/").canWrite());
        GitClient defaultClient = Git.with(TaskListener.NULL, new EnvVars()).in(badDirectory).using("git").getClient();
        assertNotNull(defaultClient);
        thrown.expect(GitException.class);
        defaultClient.init_().workspace(badDirectory.getAbsolutePath()).execute();
    }

    @Test
    public void initJGitImplThrowsGitException() throws GitAPIException, IOException, InterruptedException {
        File badDirectory = new File("/this/is/a/bad/dir");
        if (isWindows()) {
            badDirectory = new File("\\\\badserver\\badshare\\bad\\dir");
        }
        assumeFalse("running as root?", new File("/").canWrite());
        GitClient defaultClient = Git.with(TaskListener.NULL, new EnvVars()).in(badDirectory).using("jgit").getClient();
        assertNotNull(defaultClient);
        thrown.expect(JGitInternalException.class);
        thrown.expectCause(is(IOException.class));
        defaultClient.init_().workspace(badDirectory.getAbsolutePath()).execute();
    }

    @Test
    public void initCliImplCollisionThrowsGitException() throws GitAPIException, IOException, InterruptedException {
        File dir = folder.getRoot();
        File dotGit = folder.newFile(".git");
        Files.write(dotGit.toPath(), "file named .git".getBytes("UTF-8"), APPEND);
        thrown.expect(GitException.class);
        GitClient defaultClient = Git.with(TaskListener.NULL, new EnvVars()).in(dir).using("git").getClient();
        defaultClient.init_().workspace(dir.getAbsolutePath()).execute();
    }

    @Test
    public void initJGitImplCollisionThrowsGitException() throws GitAPIException, IOException, InterruptedException {
        File dir = folder.getRoot();
        File dotGit = folder.newFile(".git");
        Files.write(dotGit.toPath(), "file named .git".getBytes("UTF-8"), APPEND);
        thrown.expect(JGitInternalException.class);
        thrown.expectCause(is(IOException.class));
        GitClient defaultClient = Git.with(TaskListener.NULL, new EnvVars()).in(dir).using("jgit").getClient();
        defaultClient.init_().workspace(dir.getAbsolutePath()).execute();
    }

    /**
     * inline ${@link hudson.Functions#isWindows()} to prevent a transient
     * remote classloader issue
     */
    private static boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
