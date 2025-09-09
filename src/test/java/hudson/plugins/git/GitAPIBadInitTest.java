package hudson.plugins.git;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitAPIBadInitTest {

    @TempDir
    private File tempFolder;

    private final EnvVars env = new EnvVars();

    private File tempDir;
    private TaskListener listener;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = newFolder(tempFolder, "junit");
        listener = StreamTaskListener.fromStderr();
    }

    @Test
    void testInitExistingDirectory() throws Exception {
        GitClient git = new GitAPI("git", tempDir, listener, env);
        git.init();
        File gitDir = new File(tempDir, ".git");
        assertTrue(gitDir.exists(), gitDir + " not created");
        assertTrue(gitDir.isDirectory(), gitDir + " not a directory");
    }

    @Test
    void testInitExistingFile() throws Exception {
        File existingFile = new File(tempDir, "file-exists");
        Files.writeString(existingFile.toPath(), "git init should fail due to this file", StandardCharsets.UTF_8);
        GitClient git = new GitAPI("git", existingFile, listener, env);
        GitException e = assertThrows(GitException.class, git::init);
        assertThat(e.getMessage(), is("Could not init " + existingFile.getAbsolutePath()));
    }

    private static File newFolder(File root, String... subDirs) throws Exception {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + result);
        }
        return result;
    }
}
