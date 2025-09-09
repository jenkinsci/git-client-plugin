package hudson.plugins.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TagTest {

    private Tag tag;

    @BeforeEach
    void assignTag() {
        String tagName = "git-client-1.8.1";
        ObjectId tagSHA1 = ObjectId.fromString("3725b67f3daa6621dd01356c96c08a1f85b90c61");
        tag = new Tag(tagName, tagSHA1);
    }

    @Test
    void testGetCommitMessage() {
        assertNull(tag.getCommitMessage());
    }

    @Test
    void testSetCommitMessage() {
        String tagMessage = "My commit message";
        tag.setCommitMessage(tagMessage);
        assertEquals(tagMessage, tag.getCommitMessage());
    }

    @Test
    void testGetCommitSHA1() {
        assertNull(tag.getCommitSHA1());
    }

    @Test
    void testSetCommitSHA1() {
        String mySHA1 = "7d34e076db3364912ec35f1ef06a3d638e6ab075";
        tag.setCommitSHA1(mySHA1);
        assertEquals(mySHA1, tag.getCommitSHA1());
    }

    @Test
    void equalsContract() {
        EqualsVerifier.forClass(Tag.class)
                .withIgnoredFields("commitSHA1", "commitMessage")
                .usingGetClass()
                .withRedefinedSuperclass()
                .verify();
    }
}
