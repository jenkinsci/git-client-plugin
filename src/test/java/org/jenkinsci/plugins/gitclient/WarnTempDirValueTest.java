package org.jenkinsci.plugins.gitclient;

import static org.junit.jupiter.api.Assertions.*;

import hudson.EnvVars;
import hudson.util.LogTaskListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
@ParameterizedClass(name = "{0}")
@MethodSource("envVarsToCheck")
class WarnTempDirValueTest {

    @Parameter(0)
    private String envVarName;

    @TempDir
    private File tempFolder;

    private File repo = null;
    private int logCount = 0;
    private LogHandler handler = null;
    private LogTaskListener listener = null;
    private static final String LOGGING_STARTED = "** Logging started **";

    @BeforeEach
    void createRepo() throws Exception {
        repo = newFolder(tempFolder, "junit");
    }

    @BeforeEach
    void createLogger() {
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        listener.getLogger().println(LOGGING_STARTED);
    }

    @AfterEach
    void checkLogger() {
        assertTrue(handler.containsMessageSubstring(LOGGING_STARTED));
    }

    static List<Arguments> envVarsToCheck() {
        List<Arguments> envVarNames = new ArrayList<>();
        Arguments tmp = Arguments.of("TMP");
        envVarNames.add(tmp);
        Arguments temp = Arguments.of("TEMP");
        envVarNames.add(temp);
        return envVarNames;
    }

    @Test
    void noWarningForDefaultValue() throws Exception {
        EnvVars env = new hudson.EnvVars();
        assertFalse(env.get(envVarName, "/tmp").contains(" "));
        GitClient git = Git.with(listener, env).in(repo).using("git").getClient();
        git.init();
        assertFalse(handler.containsMessageSubstring(" contains an embedded space"));
    }

    @Test
    @Issue("JENKINS-22706")
    void warnWhenValueContainsSpaceCharacter() throws Exception {
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
        return File.pathSeparatorChar == ';';
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
