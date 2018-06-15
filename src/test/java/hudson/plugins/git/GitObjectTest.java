package hudson.plugins.git;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

import nl.jqno.equalsverifier.EqualsVerifier;

@RunWith(Parameterized.class)
public class GitObjectTest {

    private final String sha1String;
    private final String name;
    private final ObjectId sha1;

    private final GitObject gitObject;

    public GitObjectTest(String name, String sha1String) {
        this.name = name;
        this.sha1String = sha1String;
        this.sha1 = sha1String != null ? ObjectId.fromString(sha1String) : null;
        gitObject = new GitObject(name, sha1);
    }

    @Parameterized.Parameters(name = "{0}-{1}")
    public static Collection gitObjects() {
        List<Object[]> arguments = new ArrayList<>();
        String tagStr = "git-client-1.16.0";
        String tagSHA1 = "b24875cb995865a9e3a802dc0e9c8041640df0a7";
        String[] names = {tagStr, ObjectId.zeroId().getName(), "", null};
        String[] hashes = {tagSHA1, ObjectId.zeroId().name(), null};
        for (String name : names) {
            for (String sha1 : hashes) {
                Object[] item = {name, sha1};
                arguments.add(item);
            }
        }
        return arguments;
    }

    @Test
    public void testGetSHA1() {
        assertEquals(sha1, gitObject.getSHA1());
    }

    @Test
    public void testGetName() {
        assertEquals(name, gitObject.getName());
    }

    @Test
    public void testGetSHA1String() {
        assertEquals(sha1String, gitObject.getSHA1String());
    }

    @Test
    public void equalsContract() {
        EqualsVerifier.forClass(GitObject.class)
                .usingGetClass()
                .withRedefinedSubclass(Tag.class)
                .verify();
    }
}
