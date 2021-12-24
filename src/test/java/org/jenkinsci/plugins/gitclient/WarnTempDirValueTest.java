package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.util.LogTaskListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;

/**
 * The msysgit implementation (through at least 1.9.0) fails some credential
 * related operations if the path to the temporary directory contains spaces.
 *
 * This test checks that a warning is logged for Windows users if the temporary
 * directory path contains a space.
 *
 * Refer to JENKINS-22706
 *
 * @author Mark Waite
 */
@RunWith(Parameterized.class)
public class WarnTempDirValueTest {

    private final String envVarName;

    public WarnTempDirValueTest(String envVarName) {
        this.envVarName = envVarName;
    }

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File repo = null;
    private int logCount = 0;
    private LogHandler handler = null;
    private LogTaskListener listener = null;
    private static final String LOGGING_STARTED = "** Logging started **";

    @Before
    public void createRepo() throws IOException {
        repo = tempFolder.newFolder();
    }

    @Before
    public void createLogger() {
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        listener.getLogger().println(LOGGING_STARTED);
    }

    @After
    public void checkLogger() {
        assertTrue(handler.containsMessageSubstring(LOGGING_STARTED));
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> envVarsToCheck() {
        List<Object[]> envVarNames = new ArrayList<>();
        Object[] tmp = {"TMP"};
        envVarNames.add(tmp);
        Object[] temp = {"TEMP"};
        envVarNames.add(temp);
        return envVarNames;
    }

    @Test
    public void noWarningForDefaultValue() throws IOException, InterruptedException {
        EnvVars env = new hudson.EnvVars();
        assertFalse(env.get(envVarName, "/tmp").contains(" "));
        GitClient git = Git.with(listener, env).in(repo).using("git").getClient();
        git.init();
        assertFalse(handler.containsMessageSubstring(" contains an embedded space"));
    }

    @Test
    @Issue("JENKINS-22706")
    public void warnWhenValueContainsSpaceCharacter() throws IOException, InterruptedException {
        EnvVars env = new hudson.EnvVars();
        assertFalse(env.get(envVarName, "/tmp").contains(" "));
        env.put(envVarName, "/tmp/has a space/");
        assertTrue(env.get(envVarName, "/tmp").contains(" "));
        GitClient git = Git.with(listener, env).in(repo).using("git").getClient();
        git.init();
        assertEquals(isWindows(), handler.containsMessageSubstring(" contains an embedded space"));
    }

    /** inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue */
    private boolean isWindows() {
        return File.pathSeparatorChar==';';
    }
}
