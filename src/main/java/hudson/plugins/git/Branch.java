package hudson.plugins.git;

import org.eclipse.jgit.lib.Constants;
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
    public @Override String toString() {
        return "Branch " + name + "(" + sha1 + ")";
    }
}
