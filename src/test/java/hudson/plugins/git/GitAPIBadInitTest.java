package hudson.plugins.git;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;

import static junit.framework.TestCase.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.ExpectedException;

public class GitAPIBadInitTest {

    private final TemporaryDirectoryAllocator temporaryDirectoryAllocator;
    private final EnvVars env;

    public GitAPIBadInitTest() {
        temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();
        env = new EnvVars();
    }

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private File tempDir;
    private TaskListener listener;

    @Before
    public void setUp() throws IOException, InterruptedException {
        tempDir = temporaryDirectoryAllocator.allocate();
        listener = StreamTaskListener.fromStderr();
    }

    @After
    public void tearDown() throws InterruptedException {
        temporaryDirectoryAllocator.disposeAsync();
    }

    @Test
    public void testInitExistingDirectory() throws Exception {
        GitClient git = Git.with(listener, env).using("git").in(tempDir).getClient();
        git.init();
        File gitDir = new File(tempDir, ".git");
        assertTrue(gitDir + " not created", gitDir.exists());
        assertTrue(gitDir + " not a directory", gitDir.isDirectory());
    }

    @Test
    public void testInitNoDirectory() throws Exception {
        File nonExistentDirectory = new File(tempDir, "dir-does-not-exist");
        GitClient git = Git.with(listener, env).using("git").in(nonExistentDirectory).getClient();
        thrown.expect(GitException.class);
        thrown.expectMessage("Could not init " + nonExistentDirectory.getAbsolutePath());
        git.init();
    }

    @Test
    public void testInitExistingFile() throws Exception {
        File existingFile = new File(tempDir, "file-exists");
        FileUtils.writeStringToFile(existingFile, "git init should fail due to this file", "UTF-8");
        GitClient git = Git.with(listener, env).using("git").in(existingFile).getClient();
        thrown.expect(GitException.class);
        thrown.expectMessage("Could not init " + existingFile.getAbsolutePath());
        git.init();
    }
}
