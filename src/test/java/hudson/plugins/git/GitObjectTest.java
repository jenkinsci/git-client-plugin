package hudson.plugins.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@ParameterizedClass(name = "{0}-{1}")
@MethodSource("gitObjects")
class GitObjectTest {

    @Parameter(0)
    private String name;

    @Parameter(1)
    private String sha1String;

    @Parameter(2)
    private ObjectId sha1;

    @Parameter(3)
    private GitObject gitObject;

    static List<Arguments> gitObjects() {
        List<Arguments> arguments = new ArrayList<>();
        String tagStr = "git-client-1.16.0";
        String tagSHA1 = "b24875cb995865a9e3a802dc0e9c8041640df0a7";
        String[] names = {tagStr, ObjectId.zeroId().getName(), "", null};
        String[] hashes = {tagSHA1, ObjectId.zeroId().name(), null};
        for (String name : names) {
            for (String sha1String : hashes) {
                ObjectId sha1 = sha1String != null ? ObjectId.fromString(sha1String) : null;
                GitObject gitObject = new GitObject(name, sha1);
                arguments.add(Arguments.of(name, sha1String, sha1, gitObject));
            }
        }
        return arguments;
    }

    @Test
    void testGetSHA1() {
        assertEquals(sha1, gitObject.getSHA1());
    }

    @Test
    void testGetName() {
        assertEquals(name, gitObject.getName());
    }

    @Test
    void testGetSHA1String() {
        assertEquals(sha1String, gitObject.getSHA1String());
    }

    @Test
    void equalsContract() {
        EqualsVerifier.forClass(GitObject.class)
                .usingGetClass()
                .withRedefinedSubclass(Tag.class)
                .verify();
    }
}
