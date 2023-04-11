package hudson.plugins.git;

import static org.junit.Assert.*;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Before;
import org.junit.Test;

public class TagTest {

    private Tag tag;

    @Before
    public void assignTag() {
        String tagName = "git-client-1.8.1";
        ObjectId tagSHA1 = ObjectId.fromString("3725b67f3daa6621dd01356c96c08a1f85b90c61");
        tag = new Tag(tagName, tagSHA1);
    }

    @Test
    public void testGetCommitMessage() {
        assertNull(tag.getCommitMessage());
    }

    @Test
    public void testSetCommitMessage() {
        String tagMessage = "My commit message";
        tag.setCommitMessage(tagMessage);
        assertEquals(tagMessage, tag.getCommitMessage());
    }

    @Test
    public void testGetCommitSHA1() {
        assertNull(tag.getCommitSHA1());
    }

    @Test
    public void testSetCommitSHA1() {
        String mySHA1 = "7d34e076db3364912ec35f1ef06a3d638e6ab075";
        tag.setCommitSHA1(mySHA1);
        assertEquals(mySHA1, tag.getCommitSHA1());
    }

    @Test
    public void equalsContract() {
        EqualsVerifier.forClass(Tag.class)
                .withIgnoredFields("commitSHA1", "commitMessage")
                .usingGetClass()
                .withRedefinedSuperclass()
                .verify();
    }
}
