package hudson.plugins.git;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import static org.hamcrest.CoreMatchers.is;

public class GitLockFailedExceptionTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void throwsGitLockFailedException() {
        String message = null;
        thrown.expect(GitLockFailedException.class);
        thrown.expectMessage(is(message));
        throw new GitLockFailedException();
    }

    @Test
    public void throwsGitLockFailedExceptionWithMessage() {
        String message = "My local exception message";
        thrown.expect(GitLockFailedException.class);
        thrown.expectMessage(is(message));
        throw new GitLockFailedException(message);
    }

    @Test
    public void throwsGitLockFailedExceptionWithCause() {
        String message = "My git exception message";
        GitException e = new GitException(message);
        thrown.expect(GitLockFailedException.class);
        thrown.expectMessage(is("hudson.plugins.git.GitException: " + message));
        throw new GitLockFailedException(e);
    }

    @Test
    public void throwsGitLockFailedExceptionWithCauseAndMessage() {
        String message = "My git exception message";
        GitException e = new GitException(message);
        String lockMessage = "My lock message";
        thrown.expect(GitLockFailedException.class);
        thrown.expectMessage(is(lockMessage));
        throw new GitLockFailedException(lockMessage, e);
    }

}
