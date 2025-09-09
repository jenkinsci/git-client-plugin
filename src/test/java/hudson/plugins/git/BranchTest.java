package hudson.plugins.git;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.Test;

class BranchTest {

    private static final String BRANCH_SHA_1 = "fa71f704f9b90fa1f857d1623f3fe33fa2277ca9";
    private static final String BRANCH_NAME = "origin/master";
    private static final ObjectId BRANCH_HEAD = ObjectId.fromString(BRANCH_SHA_1);
    private static final Branch BRANCH = new Branch(BRANCH_NAME, BRANCH_HEAD);
    private static final String REF_PREFIX = "refs/remotes/";
    private static final Ref BRANCH_REF =
            new ObjectIdRef.PeeledNonTag(Ref.Storage.NEW, REF_PREFIX + BRANCH_NAME, BRANCH_HEAD);
    private static final Branch BRANCH_FROM_REF = new Branch(BRANCH_REF);

    @Test
    void testToString() {
        assertThat(BRANCH.toString(), is(BRANCH_FROM_REF.toString()));
    }

    @Test
    void testToString_Contents() {
        String expected = "Branch " + BRANCH_NAME + "(" + BRANCH_SHA_1 + ")";
        assertThat(BRANCH.toString(), is(expected));
    }

    @Test
    void hashCodeContract() {
        assertThat(BRANCH, is(BRANCH_FROM_REF));
        assertThat(BRANCH.hashCode(), is(BRANCH_FROM_REF.hashCode()));
    }

    @Test
    void constructorRefArgStripped() {
        Ref ref = new ObjectIdRef.PeeledNonTag(Ref.Storage.LOOSE, REF_PREFIX + BRANCH_NAME, BRANCH_HEAD);
        Branch strippedBranch = new Branch(ref);
        assertThat(strippedBranch.getName(), is(BRANCH_NAME));
    }

    @Test
    void equalsContract() {
        EqualsVerifier.forClass(Branch.class)
                .usingGetClass()
                .withRedefinedSuperclass()
                .verify();
    }
}
