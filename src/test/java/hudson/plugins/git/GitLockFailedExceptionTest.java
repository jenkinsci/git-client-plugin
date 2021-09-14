package hudson.plugins.git;

import org.junit.Test;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;

public class GitLockFailedExceptionTest {

    @Test
    public void throwsGitLockFailedException() {
        GitLockFailedException lockFailed = assertThrows(GitLockFailedException.class,
                                                         () -> {
                                                             throw new GitLockFailedException();
                                                         });
        assertThat(lockFailed.getMessage(), is(nullValue()));
    }

    @Test
    public void throwsGitLockFailedExceptionWithMessage() {
        String message = "My local exception message";
        GitLockFailedException lockFailed = assertThrows(GitLockFailedException.class,
                                                         () -> {
                                                             throw new GitLockFailedException(message);
                                                         });
        assertThat(lockFailed.getMessage(), is(message));
    }

    @Test
    public void throwsGitLockFailedExceptionWithCause() {
        String message = "My git exception message";
        GitException e = new GitException(message);
        GitLockFailedException lockFailed = assertThrows(GitLockFailedException.class,
                                                         () -> {
                                                             throw new GitLockFailedException(e);
                                                         });
        assertThat(lockFailed.getMessage(), is("hudson.plugins.git.GitException: " + message));
    }

    @Test
    public void throwsGitLockFailedExceptionWithCauseAndMessage() {
        String message = "My git exception message";
        GitException e = new GitException(message);
        String lockMessage = "My lock message that is not part of the GitException";
        GitLockFailedException lockFailed = assertThrows(GitLockFailedException.class,
                                                         () -> {
                                                             throw new GitLockFailedException(lockMessage, e);
                                                         });
        assertThat(lockFailed.getMessage(), is(lockMessage));
    }

}
