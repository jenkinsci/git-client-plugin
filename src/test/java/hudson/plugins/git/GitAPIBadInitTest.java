package hudson.plugins.git;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import org.jenkinsci.plugins.gitclient.GitClient;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;

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
        FileUtils.writeStringToFile(existingFile, "git init should fail due to this file", "UTF-8");
        GitClient git = new GitAPI("git", existingFile, listener, env);
        GitException e = assertThrows(GitException.class,
                                      () -> {
                                          git.init();
                                      });
        assertThat(e.getMessage(), is("Could not init " + existingFile.getAbsolutePath()));
    }
}
