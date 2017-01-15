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

    private final String[] CARET_SPECIALS = {"&", "\\", "<", ">", "^", "|", " ", "\t"};
    private final String[] PERCENT_SPECIALS = {"%"};

    @Before
    public void setUp() {
        git = new CliGitAPIImpl("git", new File("."), TaskListener.NULL, new EnvVars());
    }

    @Test
    public void testQuoteWindowsCredentials() throws Exception {
        assertEquals("", git.quoteWindowsCredentials(""));
        for (String special : CARET_SPECIALS) {
            String expected = "^" + special;
            assertEquals(expected, git.quoteWindowsCredentials(special));
            assertEquals(expected + expected, git.quoteWindowsCredentials(special + special));
            checkWindowsCommandOutput(special);
        }
        for (String special : PERCENT_SPECIALS) {
            String expected = "%" + special;
            assertEquals(expected, git.quoteWindowsCredentials(special));
            assertEquals(expected + expected, git.quoteWindowsCredentials(special + special));
            checkWindowsCommandOutput(special);
        }
        for (String startSpecial : CARET_SPECIALS) {
            for (String endSpecial : PERCENT_SPECIALS) {
                String middle = randomString();
                String source = startSpecial + middle + endSpecial;
                String expected = "^" + startSpecial + middle.replace(" ", "^ ") + "%" + endSpecial;
                assertEquals(expected, git.quoteWindowsCredentials(source));
                assertEquals(expected + expected, git.quoteWindowsCredentials(source + source));
                checkWindowsCommandOutput(source);
            }
        }
        for (String startSpecial : PERCENT_SPECIALS) {
            for (String endSpecial : CARET_SPECIALS) {
                String middle = randomString();
                String source = startSpecial + middle + endSpecial;
                String expected = "%" + startSpecial + middle.replace(" ", "^ ") + "^" + endSpecial;
                assertEquals(expected, git.quoteWindowsCredentials(source));
                assertEquals(expected + expected, git.quoteWindowsCredentials(source + source));
                checkWindowsCommandOutput(source);
            }
        }
        for (String startSpecial : PERCENT_SPECIALS) {
            for (String endSpecial : PERCENT_SPECIALS) {
                String middle = randomString();
                String source = startSpecial + middle + endSpecial;
                String expected = "%" + startSpecial + middle.replace(" ", "^ ") + "%" + endSpecial;
                assertEquals(expected, git.quoteWindowsCredentials(source));
                assertEquals(expected + expected, git.quoteWindowsCredentials(source + source));
                checkWindowsCommandOutput(source);
            }
        }
    }

    private void checkWindowsCommandOutput(String password) throws Exception {
        if (!isWindows() || password == null || password.isEmpty() || password.equals(" ") || password.equals("\t")) {
            /* ArgumentListBuilder can't pass a single space or a single tab argument */
            return;
        }
        String userName = "git";
        File batFile = git.createWindowsBatFile(userName, password);
        assertTrue(batFile.exists());
        ArgumentListBuilder args = new ArgumentListBuilder(batFile.getAbsolutePath(), "Password");
        String[] output = run(args);
        assertThat(Arrays.asList(output), hasItems(password));
        assertTrue("Failed to delete test batch file", batFile.delete());
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

    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
