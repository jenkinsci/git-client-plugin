package hudson.plugins.git;

import static java.nio.file.StandardOpenOption.APPEND;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import hudson.EnvVars;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GitExceptionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void throwsGitException() {
        GitException e = assertThrows(GitException.class, () -> {
            throw new GitException();
        });
        assertThat(e.getMessage(), is(nullValue()));
    }

    @Test
    public void throwsGitExceptionExpectedMessage() {
        String message = "My custom git exception message";
        GitException e = assertThrows(GitException.class, () -> {
            throw new GitException(message);
        });
        assertThat(e.getMessage(), is(message));
    }

    @Test
    public void throwsGitExceptionExpectedMessageWithCause() {
        String message = "My custom git exception message";
        GitException e = assertThrows(GitException.class, () -> {
            throw new GitException(message, new IOException("Custom IOException message"));
        });
        assertThat(e.getMessage(), is(message));
        assertThat(e.getCause(), isA(IOException.class));
    }

    @Test
    public void initCliImplThrowsGitException() throws IOException, InterruptedException {
        if (new File("/").canWrite()) { // running as root?
            return;
        }
        String fileName = isWindows() ? "\\\\badserver\\badshare\\bad\\dir" : "/this/is/a/bad/dir";
        final File badDirectory = new File(fileName);
        GitClient defaultClient = Git.with(TaskListener.NULL, new EnvVars())
                .in(badDirectory)
                .using("git")
                .getClient();
        assertNotNull(defaultClient);
        assertThrows(GitException.class, () -> defaultClient
                .init_()
                .workspace(badDirectory.getAbsolutePath())
                .execute());
    }

    @Test
    public void initJGitImplThrowsGitException() throws IOException, InterruptedException {
        if (new File("/").canWrite()) { // running as root?
            return;
        }
        String fileName = isWindows() ? "\\\\badserver\\badshare\\bad\\dir" : "/this/is/a/bad/dir";
        final File badDirectory = new File(fileName);
        GitClient defaultClient = Git.with(TaskListener.NULL, new EnvVars())
                .in(badDirectory)
                .using("jgit")
                .getClient();
        assertNotNull(defaultClient);
        JGitInternalException e = assertThrows(JGitInternalException.class, () -> defaultClient
                .init_()
                .workspace(badDirectory.getAbsolutePath())
                .execute());
        assertThat(e.getCause(), isA(IOException.class));
    }

    @Test
    public void initCliImplCollisionThrowsGitException() throws IOException, InterruptedException {
        File dir = folder.getRoot();
        File dotGit = folder.newFile(".git");
        Files.write(dotGit.toPath(), "file named .git".getBytes(StandardCharsets.UTF_8), APPEND);
        GitClient defaultClient =
                Git.with(TaskListener.NULL, new EnvVars()).in(dir).using("git").getClient();
        assertThrows(
                GitException.class,
                () -> defaultClient.init_().workspace(dir.getAbsolutePath()).execute());
    }

    @Test
    public void initJGitImplCollisionThrowsGitException() throws IOException, InterruptedException {
        File dir = folder.getRoot();
        File dotGit = folder.newFile(".git");
        Files.write(dotGit.toPath(), "file named .git".getBytes(StandardCharsets.UTF_8), APPEND);
        GitClient defaultClient =
                Git.with(TaskListener.NULL, new EnvVars()).in(dir).using("jgit").getClient();
        JGitInternalException e = assertThrows(
                JGitInternalException.class,
                () -> defaultClient.init_().workspace(dir.getAbsolutePath()).execute());
        assertThat(e.getCause(), isA(IOException.class));
    }

    /**
     * inline ${@link hudson.Functions#isWindows()} to prevent a transient
     * remote classloader issue
     */
    private static boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
