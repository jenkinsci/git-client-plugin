package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.FilePath;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import hudson.model.TaskListener;
import hudson.plugins.git.GitTool;

import org.eclipse.jgit.lib.ObjectId;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

import org.junit.BeforeClass;
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

    /* Default class of GitClient absent other modifiers */
    private static final Class DEFAULT_CLASS = JGitAPIImpl.class;

    /* A known commit from the git-client-plugin repository */
    private static ObjectId expectedCommit = null;

    static {
        Git git = new Git(null, null).using("git").in(new File("."));
        try {
            expectedCommit = git.getClient().revParse("HEAD");
        } catch (IOException | InterruptedException e) {
            fail("Exception computing SHA1 of HEAD commit " + e);
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection gitImplementation() {
        return Arrays.asList(new Object[][]{
            {"git",           CliGitAPIImpl.class},
            {"jgit",          JGitAPIImpl.class  },
            {"jgitapache",    JGitAPIImpl.class  },
            // {GitTool.DEFAULT, CliGitAPIImpl.class},
        });
    }

    private final String implementation;
    private Class expectedClass;

    public GitTest(String implementation, Class expectedClass) throws Exception {
        this.implementation = implementation;
        this.expectedClass = expectedClass;
    }

    @BeforeClass
    public static void setMainIsUnitTest() {
        // Configure git client for unit test execution
        hudson.Main.isUnitTest = true;
    }

    @Test
    public void testWith() throws IOException, InterruptedException {
        Git git = Git.with(null, null);
        assertThat(git.getClient(), is(instanceOf(DEFAULT_CLASS)));
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

    @Test
    public void testWithUsing() throws IOException, InterruptedException {
        Git git = Git.with(null, null).using(implementation);
        assertThat(git.getClient(), is(instanceOf(expectedClass)));
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

    @Test
    public void testIn_File() throws IOException, InterruptedException {
        Git git = new Git(null, null).in(new File("."));
        assertThat(git.getClient(), is(instanceOf(DEFAULT_CLASS)));
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

    @Test
    public void testIn_FileUsing() throws IOException, InterruptedException {
        Git git = new Git(null, null).in(new File(".")).using(implementation);
        assertThat(git.getClient(), is(instanceOf(expectedClass)));
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

    @Test
    public void testIn_FilePath() throws IOException, InterruptedException {
        Git git = new Git(null, null).in(new FilePath(new File(".")));
        assertThat(git.getClient(), is(instanceOf(DEFAULT_CLASS)));
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

    @Test
    public void testIn_FilePathUsing() throws IOException, InterruptedException {
        Git git = new Git(null, null).in(new File(".")).using(implementation);
        assertThat(git.getClient(), is(instanceOf(expectedClass)));
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

    @Test
    public void testUsing() throws IOException, InterruptedException {
        Git git = new Git(null, null).using(implementation);
        assertThat(git.getClient(), is(instanceOf(expectedClass)));
        assertTrue("Missing expected commit", git.getClient().isCommitInRepo(expectedCommit));
    }

}
