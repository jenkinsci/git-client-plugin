package hudson.plugins.git;

import java.io.Serial;

/**
 * Records exception information related to git operations. This exception is
 * used to encapsulate command line git errors, JGit errors, and other errors
 * related to git operations.
 */
public class GitException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructor for GitException.
     */
    public GitException() {
        super();
    }

    /**
     * Constructor for GitException.
     *
     * @param message {@link java.lang.String} description to associate with this exception
     */
    public GitException(String message) {
        super(message);
    }

    /**
     * Constructor for GitException.
     *
     * @param cause {@link java.lang.Throwable} which caused this exception
     */
    public GitException(Throwable cause) {
        super(cause);
    }

    /**
     * Constructor for GitException.
     *
     * @param message {@link java.lang.String} description to associate with this exception
     * @param cause {@link java.lang.Throwable} which caused this exception
     */
    public GitException(String message, Throwable cause) {
        super(message, cause);
    }
}
