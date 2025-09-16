package org.jenkinsci.plugins.gitclient;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import java.io.File;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Trivial tests of defaults asserted in the javadoc of Git class.
 *
 * @author Mark Waite
 */
@ParameterizedClass(name = "{0}")
@MethodSource("gitImplementation")
class GitTest {

    /* A known commit from the git-client-plugin repository */
    private ObjectId expectedCommit;

    static List<Arguments> gitImplementation() {
        return List.of(Arguments.of("git"), Arguments.of("jgit"));
    }

    @Parameter(0)
    private String implementation;

    @BeforeEach
    void setUp() throws Exception {
        expectedCommit = new Git(null, null)
                .using(implementation)
                .in(new File("."))
                .getClient()
                .revParse("HEAD");
    }

    @Test
    void testWith() throws Exception {
        Git git = Git.with(null, null);
        assertInstanceOf(JGitAPIImpl.class, git.getClient(), "Wrong default client type");
        assertTrue(git.getClient().isCommitInRepo(expectedCommit), "Missing expected commit");
        git = Git.with(null, null).using(implementation);
        assertTrue(
                implementation.equals("git")
                        ? git.getClient() instanceof CliGitAPIImpl
                        : git.getClient() instanceof JGitAPIImpl,
                "Wrong client type");
        assertTrue(git.getClient().isCommitInRepo(expectedCommit), "Missing expected commit");
    }

    @Test
    void testIn_File() throws Exception {
        Git git = new Git(null, null).in(new File("."));
        assertInstanceOf(JGitAPIImpl.class, git.getClient(), "Wrong client type");
        assertTrue(git.getClient().isCommitInRepo(expectedCommit), "Missing expected commit");
    }

    @Test
    void testIn_FileUsing() throws Exception {
        Git git = new Git(null, null).in(new File(".")).using(implementation);
        assertTrue(
                implementation.equals("git")
                        ? git.getClient() instanceof CliGitAPIImpl
                        : git.getClient() instanceof JGitAPIImpl,
                "Wrong client type");
        assertTrue(git.getClient().isCommitInRepo(expectedCommit), "Missing expected commit");
    }

    @Test
    void testIn_FilePath() throws Exception {
        Git git = new Git(null, null).in(new FilePath(new File(".")));
        assertInstanceOf(JGitAPIImpl.class, git.getClient(), "Wrong client type");
        assertTrue(git.getClient().isCommitInRepo(expectedCommit), "Missing expected commit");
    }

    @Test
    void testIn_FilePathUsing() throws Exception {
        Git git = new Git(null, null).in(new File(".")).using(implementation);
        assertTrue(
                implementation.equals("git")
                        ? git.getClient() instanceof CliGitAPIImpl
                        : git.getClient() instanceof JGitAPIImpl,
                "Wrong client type");
        assertTrue(git.getClient().isCommitInRepo(expectedCommit), "Missing expected commit");
    }

    @Test
    void testUsing() throws Exception {
        Git git = new Git(null, null).using(implementation);
        assertTrue(
                implementation.equals("git")
                        ? git.getClient() instanceof CliGitAPIImpl
                        : git.getClient() instanceof JGitAPIImpl,
                "Wrong client type");
        assertTrue(git.getClient().isCommitInRepo(expectedCommit), "Missing expected commit");
    }
}
