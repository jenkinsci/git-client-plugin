package org.jenkinsci.plugins.gitclient;

import static org.junit.Assert.*;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Run a command line git command, return output as array of String, optionally
 * assert on contents of command output.
 *
 * @author Mark Waite
 */
class CliGitCommand {

    private final TaskListener listener;
    private final transient Launcher launcher;
    private final EnvVars env;
    private final File dir;
    private String[] output;
    private ArgumentListBuilder args;

    CliGitCommand(GitClient client, String... arguments) {
        args = new ArgumentListBuilder("git");
        args.add(arguments);
        listener = StreamTaskListener.NULL;
        launcher = new Launcher.LocalLauncher(listener);
        env = new EnvVars();
        if (client != null) {
            dir = client.getRepository().getWorkTree();
        } else {
            dir = new File(".");
        }
    }

    String[] run(String... arguments) throws IOException, InterruptedException {
        args = new ArgumentListBuilder("git");
        args.add(arguments);
        return run(true);
    }

    String[] run() throws IOException, InterruptedException {
        return run(true);
    }

    private String[] run(boolean assertProcessStatus) throws IOException, InterruptedException {
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        ByteArrayOutputStream bytesErr = new ByteArrayOutputStream();
        Launcher.ProcStarter p = launcher.launch().cmds(args).envs(env).stdout(bytesOut).stderr(bytesErr).pwd(dir);
        int status = p.start().joinWithTimeout(1, TimeUnit.MINUTES, listener);
        String result = bytesOut.toString("UTF-8");
        if (bytesErr.size() > 0) {
            result = result + "\nstderr not empty:\n" + bytesErr.toString("UTF-8");
        }
        output = result.split("[\\n\\r]");
        if (assertProcessStatus) {
            assertEquals(args.toString() + " command failed and reported '" + Arrays.toString(output) + "'", 0, status);
        }
        return output;
    }

    void assertOutputContains(String... expectedRegExes) {
        List<String> notFound = new ArrayList<>();
        boolean modified = notFound.addAll(Arrays.asList(expectedRegExes));
        assertTrue("Missing regular expressions in assertion", modified);
        for (String line : output) {
            notFound.removeIf(line::matches);
        }
        if (!notFound.isEmpty()) {
            fail(Arrays.toString(output) + " did not match all strings in notFound: " + Arrays.toString(expectedRegExes));
        }
    }

    private String[] runWithoutAssert(String... arguments) throws IOException, InterruptedException {
        args = new ArgumentListBuilder("git");
        args.add(arguments);
        return run(false);
    }
}
