package hudson.plugins.git;

import java.util.Objects;
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

    /**
     * Returns a hash code value for the object. Considers sha1 and name in the
     * calculation.
     *
     * @return a hash code value for this object.
     */
    @Override
    public int hashCode() {
        return super.hashCode();
    }

    /**
     * Indicates whether some other object is "equal to" this one. Includes sha1
     * and name in the comparison. Objects of subclasses of this object are not
     * equal to objects of this class, even if they add no fields.
     *
     * @param obj the reference object with which to compare.
     * @return true if this object is the same as the obj argument; false
     * otherwise
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Tag other = (Tag) obj;
        return Objects.equals(this.name, other.name)
                && Objects.equals(this.sha1, other.sha1);
    }
}
