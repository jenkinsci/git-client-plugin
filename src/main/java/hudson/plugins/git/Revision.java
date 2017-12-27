package hudson.plugins.git;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
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

    ObjectId           sha1;
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
    public ObjectId getSha1() {
        return sha1;
    }

    /**
     * getSha1String.
     *
     * @return a {@link java.lang.String} object.
     */
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
     * containsBranchName.
     *
     * @param name a {@link java.lang.String} object.
     * @return true if this repository is bare
     */
    public boolean containsBranchName(String name) {
        for (Branch b : branches) {
            if (b.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * toString.
     *
     * @return a {@link java.lang.String} object.
     */
    public String toString() {
        final String revisionName = sha1 != null ? sha1.name() : "null";
        StringBuilder s = new StringBuilder("Revision " + revisionName + " (");
        if (branches != null) {
            Joiner.on(", ").appendTo(s,
                    Iterables.transform(branches, (Branch from) -> Util.fixNull(from.getName())));
        }
        s.append(')');
        return s.toString();
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return sha1 != null ? 31 + sha1.hashCode() : 1;
    }

    /** {@inheritDoc} */
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
