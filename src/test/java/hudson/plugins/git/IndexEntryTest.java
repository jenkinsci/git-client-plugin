package hudson.plugins.git;

import java.io.File;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class IndexEntryTest {

    private final String mode = "160000";
    private final String type = "commit";
    private final String object = "index-entry-object";
    private final String file = ".git" + File.separator + "index-entry-file";
    private IndexEntry entry;

    public IndexEntryTest() {
    }

    @Before
    public void setUp() {
        entry = new IndexEntry(mode, type, object, file);
    }

    @Test
    public void testGetMode() {
        assertEquals(mode, entry.getMode());
    }

    @Test
    public void testSetMode() {
        String myMode = "100777";
        entry.setMode(myMode);
        assertEquals(myMode, entry.getMode());
    }

    @Test
    public void testGetType() {
        assertEquals(type, entry.getType());
    }

    @Test
    public void testSetType() {
        String myType = "tag";
        entry.setType(myType);
        assertEquals(myType, entry.getType());
    }

    @Test
    public void testGetObject() {
        assertEquals(object, entry.getObject());
    }

    @Test
    public void testSetObject() {
        String myObject = "my-object";
        entry.setObject(myObject);
        assertEquals(myObject, entry.getObject());
    }

    @Test
    public void testGetFile() {
        assertEquals(file, entry.getFile());
    }

    @Test
    public void testSetFile() {
        String myFile = "my-file";
        entry.setFile(myFile);
        assertEquals(myFile, entry.getFile());
    }

    @Test
    public void testToString() {
        String expected = "IndexEntry[mode=" + mode + ",type=" + type + ",file=" + file + ",object=" + object + "]";
        assertEquals(expected, entry.toString());
    }

}
