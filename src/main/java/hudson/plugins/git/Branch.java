package hudson.plugins.git;

import java.util.Objects;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;

/**
 * Represents a git branch.
 */
public class Branch extends GitObject {
    private static final long serialVersionUID = 1L;

    /**
     * Constructor for Branch.
     *
     * @param name branch name
     * @param sha1 object ID to which branch name points
     */
    public Branch(String name, ObjectId sha1) {
        super(name, sha1);
    }

    /**
     * Constructor for Branch.
     *
     * @param candidate reference to which branch points (or should point)
     */
    public Branch(Ref candidate) {
        super(strip(candidate.getName()), candidate.getObjectId());
    }

    private static String strip(String name) {
        return name.substring(name.indexOf('/', 5) + 1);
    }

    /**
     * Returns branch name and SHA1 hash.
     * @return branch name and SHA1 hash
     */
    @Override
    public String toString() {
        return "Branch " + name + "(" + getSHA1String() + ")";
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
        final Branch other = (Branch) obj;
        return Objects.equals(this.name, other.name)
                && Objects.equals(this.sha1, other.sha1);
    }
}
