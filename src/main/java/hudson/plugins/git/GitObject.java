package hudson.plugins.git;

import org.eclipse.jgit.lib.ObjectId;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import java.io.Serializable;

/**
 * An object in a git repository. Includes the SHA1 and name of the
 * object stored in git (tag, branch, etc.).
 */
@ExportedBean(defaultVisibility = 999)
public class GitObject implements Serializable {

    private static final long serialVersionUID = 1L;

    ObjectId sha1;
    String name;

    /**
     * Constructor for GitObject, a named SHA1 (tag, branch, etc.).
     *
     * @param name {@link java.lang.String} name of this object
     * @param sha1 {@link org.eclipse.jgit.lib.ObjectId} which uniquely identifies this object
     */
    public GitObject(String name, ObjectId sha1) {
        this.name = name;
        this.sha1 = sha1;
    }

    /**
     * Returns the SHA1 hash of this git object as an {@link org.eclipse.jgit.lib.ObjectId}.
     *
     * @return {@link org.eclipse.jgit.lib.ObjectId} SHA1 of the object.
     */
    public ObjectId getSHA1() {
        return sha1;
    }

    /**
     * Returns the name of this git object (branch name, tag name, etc.).
     *
     * @return {@link java.lang.String} name of the object.
     */
    @Exported
    public String getName() {
        return name;
    }

    /**
     * Returns the SHA1 hash of this git object as a String.
     *
     * @return {@link java.lang.String} SHA1 of the object.
     */
    @Exported(name="SHA1")
    public String getSHA1String() {
        return sha1.name();
    }
}
