package hudson.plugins.git;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import org.jenkinsci.plugins.gitclient.GitClient;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;

import static junit.framework.TestCase.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

public class GitAPIBadInitTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final EnvVars env;

    public GitAPIBadInitTest() {
        env = new EnvVars();
    }

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private File tempDir;
    private TaskListener listener;

    @Before
    public void setUp() throws IOException, InterruptedException {
        tempDir = tempFolder.newFolder();
        listener = StreamTaskListener.fromStderr();
    }

    @Test
    public void testInitExistingDirectory() throws Exception {
        GitClient git = new GitAPI("git", tempDir, listener, env);
        git.init();
        File gitDir = new File(tempDir, ".git");
        assertTrue(gitDir + " not created", gitDir.exists());
        assertTrue(gitDir + " not a directory", gitDir.isDirectory());
    }

    @Test
    public void testInitExistingFile() throws Exception {
        File existingFile = new File(tempDir, "file-exists");
        FileUtils.writeStringToFile(existingFile, "git init should fail due to this file", "UTF-8");
        GitClient git = new GitAPI("git", existingFile, listener, env);
        thrown.expect(GitException.class);
        thrown.expectMessage("Could not init " + existingFile.getAbsolutePath());
        git.init();
    }
}
