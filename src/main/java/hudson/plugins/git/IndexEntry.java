package hudson.plugins.git;

import org.eclipse.jgit.submodule.SubmoduleWalk;

import java.io.Serializable;

/**
 * Git index / tree entry.
 *
 * @author nigelmagnay
 */
public class IndexEntry implements Serializable {
    private String mode, type, object, file;

    /**
     * Returns the mode of this entry as a String.
     *
     * @return mode of this entry as a {@link java.lang.String}.
     */
    public String getMode() {
        return mode;
    }

    /**
     * Sets the mode of this Entry.
     *
     * @param mode value to be assigned
     */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * Getter for the field <code>type</code>.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getType() {
        return type;
    }

    /**
     * Setter for the field <code>type</code>.
     *
     * @param type a {@link java.lang.String} object.
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * Getter for the field <code>object</code>.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getObject() {
        return object;
    }

    /**
     * Setter for the field <code>object</code>.
     *
     * @param object a {@link java.lang.String} object.
     */
    public void setObject(String object) {
        this.object = object;
    }

    /**
     * Getter for the field <code>file</code>.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getFile() {
        return file;
    }

    /**
     * Setter for the field <code>file</code>.
     *
     * @param file a {@link java.lang.String} object.
     */
    public void setFile(String file) {
        this.file = file;
    }

    /**
     * toString.
     *
     * @return a {@link java.lang.String} object.
     */
    public String toString() {
        return String.format("IndexEntry[mode=%s,type=%s,file=%s,object=%s]",mode,type,file,object);
    }
  
    /**
     * Constructor for IndexEntry.
     *
     * @param mode a {@link java.lang.String} object.
     * @param type a {@link java.lang.String} object.
     * @param object a {@link java.lang.String} object.
     * @param file a {@link java.lang.String} object.
     */
    public IndexEntry(String mode, String type, String object, String file) {
        this.mode = mode;
        this.type = type;
        this.file = file;
        this.object = object;
    }

    /**
     * Populates an {@link hudson.plugins.git.IndexEntry} from the current node that {@link org.eclipse.jgit.submodule.SubmoduleWalk} is pointing to.
     *
     * @param walk a {@link org.eclipse.jgit.submodule.SubmoduleWalk} object.
     */
    public IndexEntry(SubmoduleWalk walk) {
        this("160000","commit",walk.getObjectId().name(),walk.getPath());
    }

    private static final long serialVersionUID = 1L;
}
