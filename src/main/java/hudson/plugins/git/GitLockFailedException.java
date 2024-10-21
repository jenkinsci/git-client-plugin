package hudson.plugins.git;

import java.io.Serial;

/**
 * Exception which reports failure to lock a git repository. Lock failures are
 * a special case and may indicate that a retry attempt might succeed.
 */
public class GitLockFailedException extends GitException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructor for GitLockFailedException.
     */
    public GitLockFailedException() {
        super();
    }

    /**
     * Constructor for GitLockFailedException.
     *
     * @param message {@link java.lang.String} description to associate with this exception
     */
    public GitLockFailedException(String message) {
        super(message);
    }

    /**
     * Constructor for GitLockFailedException.
     *
     * @param cause {@link java.lang.Throwable} which caused this exception
     */
    public GitLockFailedException(Throwable cause) {
        super(cause);
    }

    /**
     * Constructor for GitLockFailedException.
     *
     * @param message {@link java.lang.String} description to associate with this exception
     * @param cause {@link java.lang.Throwable} which caused this exception
     */
    public GitLockFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
