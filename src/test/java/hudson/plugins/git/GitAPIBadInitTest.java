package hudson.plugins.git;

import hudson.EnvVars;
import hudson.Functions;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import static junit.framework.TestCase.assertTrue;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.LogHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;

public class GitAPIBadInitTest {

    private final TemporaryDirectoryAllocator temporaryDirectoryAllocator;
    private final EnvVars env;
    private List<String> expectedLogSubstrings;

    public GitAPIBadInitTest() {
        this.expectedLogSubstrings = new ArrayList<String>();
        temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();
        env = new EnvVars();
    }

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private File tempDir;
    private File tempFile;

    private GitClient git;

    private TaskListener listener;
    private LogHandler handler;
    private int logCount = 0;
    private static final String LOGGING_STARTED = "Logging started";
    private static final String LOGGING_COMPLETED = "Logging completed";

    @Before
    public void setUp() throws IOException, InterruptedException {
        tempDir = temporaryDirectoryAllocator.allocate();
        tempFile = new File(tempDir, "tempFile");
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        listener.getLogger().println(LOGGING_STARTED);
        git = new GitAPI("git", tempDir, listener, env);
    }

    @Before
    public void clearExpectedException() {
        expectedLogSubstrings = new ArrayList<String>();
    }

    @Before
    public void clearLogRecordingResults() {
        this.expectedLogSubstrings = new ArrayList<String>();
    }

    @After
    public void removeTemporaryDirectory() throws InterruptedException {
        try {
            temporaryDirectoryAllocator.dispose();
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }

    @After
    public void verifyLogRecordingResults() throws InterruptedException {
        listener.getLogger().println(LOGGING_COMPLETED);
        try {
            String messages = StringUtils.join(handler.getMessages(), ";");
            assertTrue("Logging not started: " + messages, handler.containsMessageSubstring(LOGGING_STARTED));
            assertTrue("Logging not finished: " + messages, handler.containsMessageSubstring(LOGGING_COMPLETED));
            for (String expectedLogSubstring : expectedLogSubstrings) {
                assertTrue("No '" + expectedLogSubstring + "' in " + messages,
                        handler.containsMessageSubstring(expectedLogSubstring));
            }
        } finally {
            handler.close();
        }
    }

    protected void addExpectedLogSubstring(String expectedLogSubstring) {
        this.expectedLogSubstrings.add(expectedLogSubstring);
    }

    @Test
    public void testInitExistingDirectory() throws Exception {
        git = new GitAPI("git", tempDir, listener, env);
        git.init();
        File gitDir = new File(tempDir, ".git");
        assertTrue(gitDir + " not created", gitDir.exists());
        assertTrue(gitDir + " not a directory", gitDir.isDirectory());
    }

    @Test
    public void testInitNoDirectory() throws Exception {
        File nonExistentDirectory = new File(tempDir, "dir-does-not-exist");
        git = new GitAPI("git", nonExistentDirectory, listener, env);
        thrown.expect(GitException.class);
        thrown.expectMessage("Could not init " + nonExistentDirectory.getAbsolutePath());
        addExpectedLogSubstring(Functions.isWindows() ? "The directory name is invalid" : "No such file or directory");
        git.init();
    }

    @Test
    public void testInitExistingFile() throws Exception {
        File existingFile = new File(tempDir, "file-exists");
        FileUtils.writeStringToFile(existingFile, "git init should fail due to this file", "UTF-8");
        git = new GitAPI("git", existingFile, listener, env);
        thrown.expect(GitException.class);
        thrown.expectMessage("Could not init " + existingFile.getAbsolutePath());
        addExpectedLogSubstring(Functions.isWindows() ? "The directory name is invalid" : "Not a directory");
        git.init();
    }
}
