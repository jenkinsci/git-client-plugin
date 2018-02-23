package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import static org.hamcrest.Matchers.hasItems;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * CliGitAPIImpl authorization specific tests.
 *
 * @author Mark Waite
 */
public class CliGitAPIImplAuthTest {

    private final Launcher launcher;

    public CliGitAPIImplAuthTest() {
        launcher = new Launcher.LocalLauncher(TaskListener.NULL);
    }

    private CliGitAPIImpl git;

    private final Random random = new Random();

    private final String[] CARET_SPECIALS = {"^", "&", "\\", "<", ">", "|", " ", "\"", "\t"};
    private final String[] PERCENT_SPECIALS = {"%"};

    @Before
    public void setUp() {
        git = new CliGitAPIImpl("git", new File("."), TaskListener.NULL, new EnvVars());
    }

    @Test
    public void testQuotedUsernamePasswordCredentials() throws Exception {
        assertEquals("", quoteCredentials(""));
        for (String special : CARET_SPECIALS) {
            String expected = expectedQuoting(special);
            assertEquals(expected, quoteCredentials(special));
            checkWindowsCommandOutput(special);
            assertEquals(expected + expected, quoteCredentials(special + special));
            checkWindowsCommandOutput(special + special);
        }
        for (String special : PERCENT_SPECIALS) {
            String expected = expectedQuoting(special);
            assertEquals(expected, quoteCredentials(special));
            checkWindowsCommandOutput(special);
            assertEquals(expected + expected, quoteCredentials(special + special));
            checkWindowsCommandOutput(special + special);
        }
        for (String startSpecial : CARET_SPECIALS) {
            for (String endSpecial : PERCENT_SPECIALS) {
                for (String middle : randomStrings()) {
                    String source = startSpecial + middle + endSpecial;
                    String expected = expectedQuoting(source);
                    assertEquals(expected, quoteCredentials(source));
                    checkWindowsCommandOutput(source);
                    assertEquals(expected + expected, quoteCredentials(source + source));
                    checkWindowsCommandOutput(source + source);
                }
            }
        }
        for (String startSpecial : PERCENT_SPECIALS) {
            for (String endSpecial : CARET_SPECIALS) {
                for (String middle : randomStrings()) {
                    String source = startSpecial + middle + endSpecial;
                    String expected = expectedQuoting(source);
                    assertEquals(expected, quoteCredentials(source));
                    checkWindowsCommandOutput(source);
                    assertEquals(expected + expected, quoteCredentials(source + source));
                    checkWindowsCommandOutput(source + source);
                }
            }
        }
        for (String startSpecial : PERCENT_SPECIALS) {
            for (String endSpecial : PERCENT_SPECIALS) {
                for (String middle : randomStrings()) {
                    String source = startSpecial + middle + endSpecial;
                    String expected = expectedQuoting(source);
                    assertEquals(expected, quoteCredentials(source));
                    checkWindowsCommandOutput(source);
                    assertEquals(expected + expected, quoteCredentials(source + source));
                    checkWindowsCommandOutput(source + source);
                }
            }
        }
    }

    private String quoteCredentials(String password) {
        return git.escapeWindowsCharsForUnquotedString(password);
    }

    private String expectedQuoting(String password) {
        for (String needsCaret : CARET_SPECIALS) {
            password = password.replace(needsCaret, "^" + needsCaret);
        }
        for (String needsPercent : PERCENT_SPECIALS) {
            password = password.replace(needsPercent, "%" + needsPercent);
        }
        return password;
    }

    private void checkWindowsCommandOutput(String password) throws Exception {
        if (!isWindows() || password == null || password.trim().isEmpty()) {
            /* ArgumentListBuilder can't pass spaces or tabs as arguments */
            return;
        }
        String userName = "git";
        File batFile = git.createWindowsBatFile(userName, password);
        assertTrue(batFile.exists());
        ArgumentListBuilder args = new ArgumentListBuilder(batFile.getAbsolutePath(), "Password");
        String[] output = run(args);
        assertThat(Arrays.asList(output), hasItems(password));
        if (batFile.delete() == false) {
            /* Retry delete only once */
            Thread.sleep(501); /* Wait up to 0.5 second for Windows virus scanners, etc. */
            assertTrue("Failed retry of delete test batch file", batFile.delete());
        }
        assertFalse(batFile.exists());
    }

    private String[] run(ArgumentListBuilder args) throws IOException, InterruptedException {
        String[] output;
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        ByteArrayOutputStream bytesErr = new ByteArrayOutputStream();
        Launcher.ProcStarter p = launcher.launch().cmds(args).envs(new EnvVars()).stdout(bytesOut).stderr(bytesErr).pwd(new File("."));
        int status = p.start().joinWithTimeout(1, TimeUnit.MINUTES, TaskListener.NULL);
        String result = bytesOut.toString("UTF-8");
        if (bytesErr.size() > 0) {
            result = result + "\nstderr not empty:\n" + bytesErr.toString("UTF-8");
        }
        output = result.split("[\\n\\r]");
        Assert.assertEquals(args.toString() + " command failed and reported '" + Arrays.toString(output) + "'", 0, status);
        return output;
    }

    /* Strings may contain ' ' but should not contain other escaped chars */
    private final String[] sourceData = {
        "ЁЂЃЄЅ",
        "Miloš Šafařík",
        "ЌЍЎЏАБВГД",
        "ЕЖЗИЙКЛМНОПРСТУФ",
        "фхцчшщъыьэюя",
        "الإطلاق",
        "1;DROP TABLE users",
        "C:",
        "' OR '1'='1",
        "He said, \"Hello!\", didn't he?",
        "ZZ:",
        "Roses are \u001b[0;31mred\u001b[0m"
    };

    private String randomString() {
        int index = random.nextInt(sourceData.length);
        return sourceData[index];
    }

    private String[] randomStrings() {
        if (TEST_ALL_CREDENTIALS) {
            return sourceData;
        }
        int index = random.nextInt(sourceData.length);
        return new String[]{sourceData[index]};
    }

    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }

    /* If not in a Jenkins job, then default to run all credentials tests. */
    private static final String NOT_JENKINS = System.getProperty("JOB_NAME") == null ? "true" : "false";
    private static final boolean TEST_ALL_CREDENTIALS = Boolean.valueOf(System.getProperty("TEST_ALL_CREDENTIALS", NOT_JENKINS));
}
