package hudson.plugins.git;

import static java.nio.file.StandardOpenOption.APPEND;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.EnvVars;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitExceptionTest {

    @TempDir
    private File folder;

    @Test
    void throwsGitException() {
        GitException e = assertThrows(GitException.class, () -> {
            throw new GitException();
        });
        assertThat(e.getMessage(), is(nullValue()));
    }

    @Test
    void throwsGitExceptionExpectedMessage() {
        String message = "My custom git exception message";
        GitException e = assertThrows(GitException.class, () -> {
            throw new GitException(message);
        });
        assertThat(e.getMessage(), is(message));
    }

    @Test
    void throwsGitExceptionExpectedMessageWithCause() {
        String message = "My custom git exception message";
        GitException e = assertThrows(GitException.class, () -> {
            throw new GitException(message, new IOException("Custom IOException message"));
        });
        assertThat(e.getMessage(), is(message));
        assertThat(e.getCause(), isA(IOException.class));
    }

    @Test
    void initCliImplThrowsGitException() throws Exception {
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
    void initJGitImplThrowsGitException() throws Exception {
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
    void initCliImplCollisionThrowsGitException() throws Exception {
        File dir = folder;
        File dotGit = newFile(folder, ".git");
        Files.writeString(dotGit.toPath(), "file named .git", APPEND);
        GitClient defaultClient =
                Git.with(TaskListener.NULL, new EnvVars()).in(dir).using("git").getClient();
        assertThrows(
                GitException.class,
                () -> defaultClient.init_().workspace(dir.getAbsolutePath()).execute());
    }

    @Test
    void initJGitImplCollisionThrowsGitException() throws Exception {
        File dir = folder;
        File dotGit = newFile(folder, ".git");
        Files.writeString(dotGit.toPath(), "file named .git", APPEND);
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

    private static File newFile(File parent, String child) throws Exception {
        File result = new File(parent, child);
        if (!result.createNewFile()) {
            throw new IOException("Couldn't create file " + result);
        }
        return result;
    }
}
