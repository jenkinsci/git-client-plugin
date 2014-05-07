package hudson.plugins.git;

import java.util.ArrayList;
import java.util.Collection;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;
import static org.junit.Assert.*;

public class RevisionTest {

    final String SHA1;
    final ObjectId objectId;
    final Revision revision1;

    final Collection<Branch> emptyCollection;
    final Revision revision2;

    final String branchName;
    final String SHA1a;
    final Branch branch;
    final Collection<Branch> branchCollection;
    final Revision revisionWithBranches;

    public RevisionTest() {
        this.SHA1 = "3725b67f3daa6621dd01356c96c08a1f85b90c61";
        this.objectId = ObjectId.fromString(SHA1);
        this.revision1 = new Revision(objectId);

        this.emptyCollection = new ArrayList<Branch>();
        this.revision2 = new Revision(objectId, emptyCollection);

        this.branchName = "origin/tests/getSubmodules";
        this.SHA1a = "9ac446c472a6433fe503d294ebb7d5691b590269";
        this.branch = new Branch(branchName, ObjectId.fromString(this.SHA1a));
        this.branchCollection = new ArrayList<Branch>();
        this.branchCollection.add(this.branch);
        this.revisionWithBranches = new Revision(ObjectId.fromString(this.SHA1a), branchCollection);
    }

    @Test
    public void testGetSha1() {
        assertEquals(revision1.getSha1(), objectId);
        assertEquals(revision2.getSha1(), objectId);
    }

    @Test
    public void testGetSha1String() {
        assertEquals(revision1.getSha1String(), SHA1);
        assertEquals(revision2.getSha1String(), SHA1);
    }

    @Test
    public void testSetSha1() {
        final String newSHA1 = "b397392d6d00af263583edeaf8f7773a619d1cf8";
        final ObjectId newObjectId = ObjectId.fromString(newSHA1);
        Revision rev = new Revision(objectId);
        assertEquals(rev.getSha1(), objectId);
        rev.setSha1(newObjectId);
        assertEquals(rev.getSha1(), newObjectId);
        assertEquals(rev.getSha1String(), newSHA1);
        rev.setSha1(null);
        assertEquals(rev.getSha1(), null);
        assertEquals(rev.getSha1String(), "");
    }

    @Test
    public void testGetBranches() {
        Collection<Branch> branches = revision1.getBranches();
        assertTrue(branches.isEmpty());

        branches = revision2.getBranches();
        assertTrue(branches.isEmpty());

        branches = revisionWithBranches.getBranches();
        assertFalse(branches.isEmpty());
        assertTrue(branches.contains(branch));
        assertEquals(branches.size(), 1);
    }

    @Test
    public void testSetBranches() {
        final String newSHA1 = "b397392d6d00af263583edeaf8f7773a619d1cf8";
        final ObjectId newObjectId = ObjectId.fromString(newSHA1);
        Revision rev = new Revision(objectId);
        rev.setBranches(emptyCollection);
        Collection<Branch> branches = rev.getBranches();
        assertTrue(branches.isEmpty());
        rev.setBranches(branchCollection);
        branches = rev.getBranches();
        assertFalse(branches.isEmpty());
        assertTrue(branches.contains(branch));
        assertEquals(branches.size(), 1);
    }

    @Test
    public void testContainsBranchName() {
        assertFalse(revision1.containsBranchName(branchName));

        assertFalse(revision2.containsBranchName(branchName));

        assertTrue(revisionWithBranches.containsBranchName(branchName));
        String myBranchName = "working-branch-name";
        assertFalse(revisionWithBranches.containsBranchName(myBranchName));

        String mySHA1 = "aaaaaaaa72a6433fe503d294ebb7d5691b590269";
        Branch myBranch = new Branch(myBranchName, ObjectId.fromString(mySHA1));
        Collection<Branch> branches = new ArrayList();
        Revision rev = new Revision(ObjectId.fromString(this.SHA1a), branches);
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
    public void testToString() {
        assertEquals("Revision " + SHA1 + " ()", revision1.toString());
        assertEquals("Revision " + SHA1 + " ()", revision2.toString());
        assertEquals("Revision " + SHA1a + " (" + branchName + ")", revisionWithBranches.toString());
    }

    @Test
    public void testClone() {
        Revision revision1Clone = revision1.clone();
        assertEquals(revision1Clone.getSha1(), objectId);

        Revision revision2Clone = revision2.clone();
        assertEquals(revision2Clone.getSha1String(), SHA1);

        Collection<Branch> branches = revisionWithBranches.getBranches();
        Revision revisionWithBranchesClone = revisionWithBranches.clone();
        Collection<Branch> branchesCloned = revisionWithBranchesClone.getBranches();
        assertTrue(revisionWithBranchesClone.containsBranchName(branchName));
    }
}
