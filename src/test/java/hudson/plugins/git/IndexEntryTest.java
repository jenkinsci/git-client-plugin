package hudson.plugins.git;

import static org.junit.Assert.*;

import java.io.File;
import org.junit.Before;
import org.junit.Test;

public class IndexEntryTest {

    private final String mode = "160000";
    private final String type = "commit";
    private final String object = "index-entry-object";
    private final String file = ".git" + File.separator + "index-entry-file";
    private IndexEntry entry;

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

    @Test
    public void testHashCode() {
        assertEquals(entry.hashCode(), entry.hashCode());

        IndexEntry entryClone = new IndexEntry(entry.getMode(), entry.getType(), entry.getObject(), entry.getFile());
        assertEquals(entryClone.hashCode(), entry.hashCode());

        /* hashCode contract does not guarantee these inequalities */
        IndexEntry entryNull = new IndexEntry(null, entry.getType(), entry.getObject(), entry.getFile());
        assertNotEquals(entry.hashCode(), entryNull.hashCode());
        entryNull = new IndexEntry(entry.getMode(), null, entry.getObject(), entry.getFile());
        assertNotEquals(entry.hashCode(), entryNull.hashCode());
        entryNull = new IndexEntry(entry.getMode(), entry.getType(), null, entry.getFile());
        assertNotEquals(entry.hashCode(), entryNull.hashCode());
        entryNull = new IndexEntry(entry.getMode(), entry.getType(), entry.getObject(), null);
        assertNotEquals(entry.hashCode(), entryNull.hashCode());
    }

    @Test
    public void testEquals() {
        assertEquals(entry, entry);

        IndexEntry entryClone = new IndexEntry(entry.getMode(), entry.getType(), entry.getObject(), entry.getFile());
        assertEquals(entry, entryClone);

        IndexEntry entryNull1 = new IndexEntry(null, entry.getType(), entry.getObject(), entry.getFile());
        IndexEntry entryNull1a = new IndexEntry(null, entry.getType(), entry.getObject(), entry.getFile());
        assertNotEquals(entry, entryNull1);
        assertNotEquals(entryNull1, entry);
        assertEquals(entryNull1, entryNull1a);
        assertEquals(entryNull1a, entryNull1);

        IndexEntry entryNull2 = new IndexEntry(entry.getMode(), null, entry.getObject(), entry.getFile());
        IndexEntry entryNull2a = new IndexEntry(entry.getMode(), null, entry.getObject(), entry.getFile());
        assertNotEquals(entry, entryNull2);
        assertNotEquals(entryNull1, entryNull2);
        assertNotEquals(entryNull2, entryNull1);
        assertEquals(entryNull2, entryNull2a);
        assertEquals(entryNull2a, entryNull2);

        IndexEntry entryNull3 = new IndexEntry(entry.getMode(), entry.getType(), null, entry.getFile());
        IndexEntry entryNull3a = new IndexEntry(entry.getMode(), entry.getType(), null, entry.getFile());
        assertNotEquals(entry, entryNull3);
        assertNotEquals(entryNull1, entryNull3);
        assertNotEquals(entryNull2, entryNull3);
        assertNotEquals(entryNull3, entryNull1);
        assertNotEquals(entryNull3, entryNull2);
        assertEquals(entryNull3, entryNull3a);
        assertEquals(entryNull3a, entryNull3);

        IndexEntry entryNull4 = new IndexEntry(entry.getMode(), entry.getType(), entry.getObject(), null);
        IndexEntry entryNull4a = new IndexEntry(entry.getMode(), entry.getType(), entry.getObject(), null);
        assertNotEquals(entry, entryNull4);
        assertNotEquals(entryNull1, entryNull4);
        assertNotEquals(entryNull2, entryNull4);
        assertNotEquals(entryNull3, entryNull4);
        assertNotEquals(entryNull4, entryNull1);
        assertNotEquals(entryNull4, entryNull2);
        assertNotEquals(entryNull4, entryNull3);
        assertEquals(entryNull4, entryNull4a);
        assertEquals(entryNull4a, entryNull4);

        assertNotEquals(entry, null);
        assertNotEquals(entry, "not an IndexEntry object");
    }
}
