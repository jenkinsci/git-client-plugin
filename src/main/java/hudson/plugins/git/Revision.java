package hudson.plugins.git;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static java.util.stream.Collectors.joining;

import hudson.Util;
import org.eclipse.jgit.lib.ObjectId;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import java.util.ArrayList;
import java.util.Collection;

/**
 * SHA1 in the object tree and the collection of branches that
 * share this SHA1. Unlike other SCMs, git can have &gt;1 branches point at the
 * _same_ commit.
 *
 * @author magnayn
 */
@ExportedBean(defaultVisibility = 999)
public class Revision implements java.io.Serializable, Cloneable {
    private static final long serialVersionUID = -7203898556389073882L;

    ObjectId sha1;
    Collection<Branch> branches;

    /**
     * Constructor for Revision.
     *
     * @param sha1 a {@link org.eclipse.jgit.lib.ObjectId} object.
     */
    public Revision(ObjectId sha1) {
        this.sha1 = sha1;
        this.branches = new ArrayList<>();
    }

    /**
     * Constructor for Revision.
     *
     * @param sha1 a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @param branches a {@link java.util.Collection} object.
     */
    public Revision(ObjectId sha1, Collection<Branch> branches) {
        this.sha1 = sha1;
        this.branches = branches;
    }

    /**
     * Getter for the field <code>sha1</code>.
     *
     * @return a {@link org.eclipse.jgit.lib.ObjectId} object.
     */
    @SuppressFBWarnings(value = "NM_CONFUSING", justification = "Published API in GitObject and Revision")
    public ObjectId getSha1() {
        return sha1;
    }

    /**
     * getSha1String.
     *
     * @return a {@link java.lang.String} object.
     */
    @SuppressFBWarnings(value = "NM_CONFUSING", justification = "Published API in GitObject and Revision")
    @Exported(name = "SHA1")
    public String getSha1String() {
        return sha1 == null ? "" : sha1.name();
    }

    /**
     * Setter for the field <code>sha1</code>.
     *
     * @param sha1 a {@link org.eclipse.jgit.lib.ObjectId} object.
     */
    public void setSha1(ObjectId sha1) {
        this.sha1 = sha1;
    }

    /**
     * Getter for the field <code>branches</code>.
     *
     * @return a {@link java.util.Collection} object.
     */
    @Exported(name = "branch")
    public Collection<Branch> getBranches() {
        return branches;
    }

    /**
     * Setter for the field <code>branches</code>.
     *
     * @param branches a {@link java.util.Collection} object.
     */
    public void setBranches(Collection<Branch> branches) {
        this.branches = branches;
    }

    /**
     * Returns whether the revision contains the specified branch.
     *
     * @param name the name of the branch
     * @return whether the revision contains the branch
     */
    public boolean containsBranchName(String name) {
        return branches.stream().anyMatch(branch -> branch.getName().equals(name));
    }

    @Override
    public String toString() {
        final String revisionName = sha1 != null ? sha1.name() : "null";
        StringBuilder s = new StringBuilder("Revision " + revisionName + " (");
        if (branches != null) {
            s.append(branches.stream().map(Branch::getName).map(Util::fixNull).collect(joining(", ")));
        }
        s.append(')');
        return s.toString();
    }

    @Override
    public Revision clone() {
        Revision clone;
        try {
            clone = (Revision) super.clone();
        }
        catch (CloneNotSupportedException e) {
            throw new RuntimeException("Error cloning Revision", e);
        }
        clone.branches = new ArrayList<>(branches);
        return clone;
    }

    @Override
    public int hashCode() {
        return sha1 != null ? 31 + sha1.hashCode() : 1;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Revision)) {
            return false;
        }
        Revision other = (Revision) obj;
        if (other.sha1 != null) {
            return other.sha1.equals(sha1);
        }
        return sha1 == null;
    }
}
