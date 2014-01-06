package hudson.plugins.git;

public class GitLockFailedException extends GitException {
    private static final long serialVersionUID = 1L;

    public GitLockFailedException() {
        super();
    }

    public GitLockFailedException(String message) {
        super(message);
    }

    public GitLockFailedException(Throwable cause) {
        super(cause);
    }

    public GitLockFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
