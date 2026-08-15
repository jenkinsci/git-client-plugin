package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.Issue;

/**
 * Tests the removal of whitespace which surrounds a refspec.
 *
 * @author Akash Manna
 * @see LegacyCompatibleGitAPIImpl#trimRefSpec(RefSpec)
 */
@Issue("JENKINS-70303")
class RefSpecTrimTest {

    private static final String DEFAULT_REFSPEC = "+refs/heads/*:refs/remotes/origin/*";

    @ParameterizedTest
    @ValueSource(
            strings = {
                " +refs/heads/*:refs/remotes/origin/*",
                "+refs/heads/*:refs/remotes/origin/* ",
                "  +refs/heads/*:refs/remotes/origin/*  ",
                "\t+refs/heads/*:refs/remotes/origin/*\t",
                "\n+refs/heads/*:refs/remotes/origin/*\n",
                "+refs/heads/* : refs/remotes/origin/*",
            })
    void surroundingWhitespaceIsRemovedFromString(String refSpec) {
        assertThat(LegacyCompatibleGitAPIImpl.trimRefSpec(refSpec), is(DEFAULT_REFSPEC));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                " +refs/heads/*:refs/remotes/origin/*",
                "+refs/heads/*:refs/remotes/origin/* ",
                "  +refs/heads/*:refs/remotes/origin/*  ",
                "\t+refs/heads/*:refs/remotes/origin/*\t",
                "+refs/heads/* : refs/remotes/origin/*",
            })
    void surroundingWhitespaceIsRemovedFromRefSpec(String refSpec) {
        RefSpec trimmed = LegacyCompatibleGitAPIImpl.trimRefSpec(new RefSpec(refSpec));
        assertThat(trimmed.toString(), is(DEFAULT_REFSPEC));
        assertThat(trimmed.getSource(), is("refs/heads/*"));
        assertThat(trimmed.getDestination(), is("refs/remotes/origin/*"));
        assertThat("refspec is not forced", trimmed.isForceUpdate(), is(true));
    }

    /** Leading whitespace hides the '+' from JGit, so the source ref does not exist. */
    @Test
    void untrimmedRefSpecIsMisparsedByJGit() {
        RefSpec untrimmed = new RefSpec(" " + DEFAULT_REFSPEC);
        assertThat(untrimmed.getSource(), is(" +refs/heads/*"));
        assertThat(untrimmed.isForceUpdate(), is(false));
    }

    /** A trimmed wildcard refspec must still expand, JGit fetch relies on it. */
    @Test
    void trimmedWildcardRefSpecExpands() {
        RefSpec trimmed = LegacyCompatibleGitAPIImpl.trimRefSpec(new RefSpec(" " + DEFAULT_REFSPEC + " "));
        assertThat(trimmed.isWildcard(), is(true));
        RefSpec expanded = trimmed.expandFromSource("refs/heads/main");
        assertThat(expanded.getDestination(), is("refs/remotes/origin/main"));
    }

    @Test
    void surroundingWhitespaceIsRemovedFromNegativeRefSpec() {
        RefSpec trimmed = LegacyCompatibleGitAPIImpl.trimRefSpec(new RefSpec(" ^refs/heads/dev/private "));
        assertThat(trimmed.toString(), is("^refs/heads/dev/private"));
        assertThat("refspec is not negative", trimmed.isNegative(), is(true));
    }

    @Test
    void refSpecWithoutDestinationIsTrimmed() {
        assertThat(LegacyCompatibleGitAPIImpl.trimRefSpec(" refs/heads/main "), is("refs/heads/main"));
        RefSpec trimmed = LegacyCompatibleGitAPIImpl.trimRefSpec(new RefSpec(" refs/heads/main "));
        assertThat(trimmed.getSource(), is("refs/heads/main"));
        assertThat(trimmed.getDestination(), is(nullValue()));
    }

    /** Only the last colon separates source from destination, as in JGit. */
    @Test
    void onlyLastColonSeparatesSourceFromDestination() {
        assertThat(LegacyCompatibleGitAPIImpl.trimRefSpec(" a:b : c "), is("a:b:c"));
    }

    @Test
    void refSpecWithoutSurroundingWhitespaceIsUnchanged() {
        assertThat(LegacyCompatibleGitAPIImpl.trimRefSpec(DEFAULT_REFSPEC), is(DEFAULT_REFSPEC));
        RefSpec refSpec = new RefSpec(DEFAULT_REFSPEC);
        assertThat(LegacyCompatibleGitAPIImpl.trimRefSpec(refSpec), is(sameInstance(refSpec)));
    }

    @Test
    void nullRefSpecIsNull() {
        assertThat(LegacyCompatibleGitAPIImpl.trimRefSpec((String) null), is(nullValue()));
        assertThat(LegacyCompatibleGitAPIImpl.trimRefSpec((RefSpec) null), is(nullValue()));
    }

    @Test
    void refSpecListIsTrimmed() {
        List<RefSpec> refSpecs = Arrays.asList(
                new RefSpec(" +refs/heads/*:refs/remotes/origin/* "),
                new RefSpec("+refs/tags/*:refs/tags/*"),
                new RefSpec(" refs/heads/main:refs/remotes/origin/main"));
        List<String> trimmed = new ArrayList<>();
        for (RefSpec refSpec : LegacyCompatibleGitAPIImpl.trimRefSpecs(refSpecs)) {
            trimmed.add(refSpec.toString());
        }
        assertThat(
                trimmed,
                contains(DEFAULT_REFSPEC, "+refs/tags/*:refs/tags/*", "refs/heads/main:refs/remotes/origin/main"));
    }

    @Test
    void nullEntriesOfRefSpecListArePreserved() {
        List<RefSpec> trimmed = LegacyCompatibleGitAPIImpl.trimRefSpecs(Collections.singletonList((RefSpec) null));
        assertThat(trimmed.size(), is(1));
        assertThat(trimmed.get(0), is(nullValue()));
    }

    /** Trimming must not turn a mismatched wildcard refspec into an IllegalArgumentException. */
    @Test
    void mismatchedWildcardRefSpecIsTrimmed() {
        RefSpec mismatched =
                new RefSpec(" +refs/heads/*:refs/remotes/origin/main ", RefSpec.WildcardMode.ALLOW_MISMATCH);
        RefSpec trimmed = LegacyCompatibleGitAPIImpl.trimRefSpec(mismatched);
        assertThat(trimmed.toString(), is("+refs/heads/*:refs/remotes/origin/main"));
    }
}
