package org.jenkinsci.plugins.gitclient;

import hudson.FilePath;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import org.eclipse.jgit.lib.ObjectId;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Trivial tests of defaults asserted in the javadoc of Git class.
 *
 * @author Mark Waite
 */
@RunWith(Parameterized.class)
public class GitTest {

    /* A known commit from the git-client-plugin repository */
    private ObjectId expectedCommit = null;

    @Parameterized.Parameters(name = "{0}")
    public static Collection gitImplementation() {
        return Arrays.asList(new Object[][]{
            {"git"},
            {"jgit"}
        });
    }

    private final String implementation;

    public GitTest(String implementation) throws Exception {
        this.implementation = implementation;
        Git git = new Git(null, null).using(implementation).in(new File("."));
        expectedCommit = git.getClient().revParse("HEAD");
    }

    @Test
    public void testWith() throws IOException, InterruptedException {
        Git git = Git.with(null, null);
        assertTrue("Wrong default client type", git.getClient() instanceof JGitAPIImpl);
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
        git = Git.with(null, null).using(implementation);
        assertTrue("Wrong client type", implementation.equals("git") ? git.getClient() instanceof CliGitAPIImpl : git.getClient() instanceof JGitAPIImpl);
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

    @Test
    public void testIn_File() throws IOException, InterruptedException {
        Git git = new Git(null, null).in(new File("."));
        assertTrue("Wrong client type", git.getClient() instanceof JGitAPIImpl);
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

    @Test
    public void testIn_FileUsing() throws IOException, InterruptedException {
        Git git = new Git(null, null).in(new File(".")).using(implementation);
        assertTrue("Wrong client type", implementation.equals("git") ? git.getClient() instanceof CliGitAPIImpl : git.getClient() instanceof JGitAPIImpl);
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

    @Test
    public void testIn_FilePath() throws IOException, InterruptedException {
        Git git = new Git(null, null).in(new FilePath(new File(".")));
        assertTrue("Wrong client type", git.getClient() instanceof JGitAPIImpl);
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

    @Test
    public void testIn_FilePathUsing() throws IOException, InterruptedException {
        Git git = new Git(null, null).in(new File(".")).using(implementation);
        assertTrue("Wrong client type", implementation.equals("git") ? git.getClient() instanceof CliGitAPIImpl : git.getClient() instanceof JGitAPIImpl);
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

    @Test
    public void testUsing() throws IOException, InterruptedException {
        Git git = new Git(null, null).using(implementation);
        assertTrue("Wrong client type", implementation.equals("git") ? git.getClient() instanceof CliGitAPIImpl : git.getClient() instanceof JGitAPIImpl);
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

}
