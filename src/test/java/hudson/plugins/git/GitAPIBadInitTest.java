package hudson.plugins.git;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import org.jenkinsci.plugins.gitclient.GitClient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GitAPIBadInitTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final EnvVars env;

    public GitAPIBadInitTest() {
        env = new EnvVars();
    }

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
        Files.writeString(existingFile.toPath(), "git init should fail due to this file", StandardCharsets.UTF_8);
        GitClient git = new GitAPI("git", existingFile, listener, env);
        GitException e = assertThrows(GitException.class, git::init);
        assertThat(e.getMessage(), is("Could not init " + existingFile.getAbsolutePath()));
    }
}
