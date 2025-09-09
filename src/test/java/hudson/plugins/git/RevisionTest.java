package hudson.plugins.git;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

class RevisionTest {

    private final String sha1 = "3725b67f3daa6621dd01356c96c08a1f85b90c61";
    private final ObjectId objectId = ObjectId.fromString(sha1);
    private final Revision revision1 = new Revision(objectId);

    private final Collection<Branch> emptyCollection = new ArrayList<>();
    private final Revision revision2 = new Revision(objectId, emptyCollection);

    private final String branchName = "origin/tests/getSubmodules";
    private final String sha1a = "9ac446c472a6433fe503d294ebb7d5691b590269";
    private final Branch branch = new Branch(branchName, ObjectId.fromString(sha1a));
    private final Collection<Branch> branchCollection = List.of(branch);
    private final Revision revisionWithBranches = new Revision(ObjectId.fromString(sha1a), branchCollection);

    @Test
    void testEquals() {
        assertEquals(revision1, revision1);
        assertNotEquals(revision1, null);
        assertNotEquals(null, revision1);
        assertNotEquals(objectId, revision1);
        assertEquals(revision1, revision2);

        revision2.setBranches(branchCollection);
        assertEquals(revision1, revision2);
        assertNotEquals(revision1, revisionWithBranches);
        assertNotEquals(revision2, revisionWithBranches);
    }

    @Test
    void testGetSha1() {
        assertEquals(objectId, revision1.getSha1());
        assertEquals(objectId, revision2.getSha1());
    }

    @Test
    void testGetSha1String() {
        assertEquals(sha1, revision1.getSha1String());
        assertEquals(sha1, revision2.getSha1String());
    }

    @Test
    void testSetSha1() {
        String newSha1 = "b397392d6d00af263583edeaf8f7773a619d1cf8";
        ObjectId newObjectId = ObjectId.fromString(newSha1);
        Revision rev = new Revision(objectId);
        assertEquals(objectId, rev.getSha1());

        rev.setSha1(newObjectId);
        assertEquals(newObjectId, rev.getSha1());
        assertEquals(newSha1, rev.getSha1String());

        rev.setSha1(null);
        assertNull(rev.getSha1());
        assertEquals("", rev.getSha1String());
    }

    @Test
    void testGetBranches() {
        assertEquals(0, revision1.getBranches().size());
        assertEquals(0, revision2.getBranches().size());

        Collection<Branch> branches = revisionWithBranches.getBranches();
        assertTrue(branches.contains(branch));
        assertEquals(1, branches.size());
    }

    @Test
    void testSetBranches() {
        Revision rev = new Revision(objectId);

        rev.setBranches(emptyCollection);
        Collection<Branch> branches = rev.getBranches();
        assertEquals(0, branches.size());

        rev.setBranches(branchCollection);
        branches = rev.getBranches();
        assertTrue(branches.contains(branch));
        assertEquals(1, branches.size());
    }

    @Test
    void testContainsBranchName() {
        assertFalse(revision1.containsBranchName(branchName));

        assertFalse(revision2.containsBranchName(branchName));

        assertTrue(revisionWithBranches.containsBranchName(branchName));
        String myBranchName = "working-branch-name";
        assertFalse(revisionWithBranches.containsBranchName(myBranchName));

        String mySHA1 = "aaaaaaaa72a6433fe503d294ebb7d5691b590269";
        Branch myBranch = new Branch(myBranchName, ObjectId.fromString(mySHA1));
        Collection<Branch> branches = new ArrayList<>();
        Revision rev = new Revision(ObjectId.fromString(sha1a), branches);
        assertFalse(rev.containsBranchName(myBranchName));

        branches.add(myBranch);
        rev.setBranches(branches);
        assertTrue(rev.containsBranchName(myBranchName));
        assertFalse(rev.containsBranchName(branchName));

        branches.add(branch);
        rev.setBranches(branches);
        assertTrue(rev.containsBranchName(branchName));
    }

    @Test
    void testToString() {
        assertEquals("Revision " + sha1 + " ()", revision1.toString());
        assertEquals("Revision " + sha1 + " ()", revision2.toString());
        assertEquals("Revision " + sha1a + " (" + branchName + ")", revisionWithBranches.toString());
    }

    @Test
    void testToStringNullOneArgument() {
        Revision nullRevision = new Revision(null);
        assertEquals("Revision null ()", nullRevision.toString());
    }

    @Test
    void testToStringNullTwoArguments() {
        Revision nullRevision = new Revision(null, null);
        assertEquals("Revision null ()", nullRevision.toString());
    }

    @Test
    void testClone() {
        Revision revision1Clone = revision1.clone();
        assertEquals(objectId, revision1Clone.getSha1());

        Revision revision2Clone = revision2.clone();
        assertEquals(sha1, revision2Clone.getSha1String());

        Revision nullRevision = new Revision(null);
        Revision clonedRevision = nullRevision.clone();
        assertEquals(nullRevision, clonedRevision);

        Revision revisionWithBranchesClone = revisionWithBranches.clone();
        assertTrue(revisionWithBranchesClone.containsBranchName(branchName));
    }

    @Test
    void testHashCode() {
        assertEquals(revision1, revision2);
        assertEquals(revision1.hashCode(), revision2.hashCode());

        Revision nullRevision1 = new Revision(null);
        Revision nullRevision2 = new Revision(null);
        assertEquals(nullRevision1.hashCode(), nullRevision2.hashCode());
    }
}
