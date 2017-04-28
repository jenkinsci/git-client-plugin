package hudson.plugins.git;

import org.eclipse.jgit.lib.ObjectId;

/**
 * Git tag including SHA1 and message of the associated commit.
 */
public class Tag extends GitObject {
    private static final long serialVersionUID = 1L;
    /** SHA1 hash of the tagged commit */
    public String commitSHA1;
    /** Commit message of the tagged commit */
    public String commitMessage;

    /**
     * Getter for the field <code>commitMessage</code>.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getCommitMessage() {
        return commitMessage;
    }

    /**
     * Setter for the field <code>commitMessage</code>.
     *
     * @param commitMessage a {@link java.lang.String} object.
     */
    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }

    /**
     * Constructor for Tag.
     *
     * @param name a {@link java.lang.String} object.
     * @param sha1 a {@link org.eclipse.jgit.lib.ObjectId} object.
     */
    public Tag(String name, ObjectId sha1) {
        super(name, sha1);
    }

    /**
     * Get the sha1 of the commit associated with this tag
     *
     * @return a {@link java.lang.String} object.
     */
    public String getCommitSHA1() {
        return commitSHA1;
    }

    /**
     * Setter for the field <code>commitSHA1</code>.
     *
     * @param commitSHA1 a {@link java.lang.String} object.
     */
    public void setCommitSHA1(String commitSHA1) {
        this.commitSHA1 = commitSHA1;
    }
}
