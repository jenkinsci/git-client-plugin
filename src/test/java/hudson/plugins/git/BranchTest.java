package hudson.plugins.git;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;

import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import nl.jqno.equalsverifier.EqualsVerifier;

public class BranchTest {

    private final String branchSHA1;
    private final String branchName;
    private final ObjectId branchHead;
    private final Branch branch;
    private final String refPrefix;
    private final Ref branchRef;
    private final Branch branchFromRef;

    public BranchTest() {
        this.branchSHA1 = "fa71f704f9b90fa1f857d1623f3fe33fa2277ca9";
        this.branchName = "origin/master";
        this.branchHead = ObjectId.fromString(branchSHA1);
        this.refPrefix = "refs/remotes/";
        this.branchRef = new ObjectIdRef.PeeledNonTag(Ref.Storage.NEW, refPrefix + branchName, branchHead);
        this.branch = new Branch(branchName, branchHead);
        this.branchFromRef = new Branch(branchRef);
    }

    @Test
    public void testToString() {
        assertThat(branch.toString(), is(branchFromRef.toString()));
    }

    @Test
    public void testToString_Contents() {
        String expected = "Branch " + branchName + "(" + branchSHA1 + ")";
        assertThat(branch.toString(), is(expected));
    }

    @Test
    public void hashCodeContract() {
        assertThat(branch, is(branchFromRef));
        assertThat(branch.hashCode(), is(branchFromRef.hashCode()));
    }

    @Test
    public void constructorRefArgStripped() {
        Ref ref = new ObjectIdRef.PeeledNonTag(Ref.Storage.LOOSE, refPrefix + branchName, branchHead);
        Branch strippedBranch = new Branch(ref);
        assertThat(strippedBranch.getName(), is(branchName));
    }

    @Test
    public void equalsContract() {
        EqualsVerifier.forClass(Branch.class)
                .usingGetClass()
                .withRedefinedSuperclass()
                .verify();
    }
}
