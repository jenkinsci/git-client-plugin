package hudson.plugins.git;

import org.eclipse.jgit.submodule.SubmoduleWalk;

import java.io.Serializable;

/**
 * An Entry in the Index / Tree
 * 
 * @author nigelmagnay
 */
public class IndexEntry implements Serializable {
    String mode, type, object, file;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public String toString() {
        return String.format("IndexEntry[mode=%s,type=%s,file=%s,object=%s]",mode,type,file,object);
    }
  
    public IndexEntry(String mode, String type, String object, String file) {
        this.mode = mode;
        this.type = type;
        this.file = file;
        this.object = object;
    }

    /**
     * Populates an {@link IndexEntry} from the current node that {@link SubmoduleWalk} is pointing to.
     */
    public IndexEntry(SubmoduleWalk walk) {
        this("160000","commit",walk.getObjectId().name(),walk.getPath());
    }

    private static final long serialVersionUID = 1L;
}
