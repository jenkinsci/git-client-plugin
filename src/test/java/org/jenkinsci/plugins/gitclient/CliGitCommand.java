package org.jenkinsci.plugins.gitclient;

import static org.junit.Assert.*;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.util.ArgumentListBuilder;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private final GitClient gitClient;
    private String[] output;
    private ArgumentListBuilder args;

    CliGitCommand(GitClient client) throws GitException {
        listener = StreamTaskListener.NULL;
        launcher = new Launcher.LocalLauncher(listener);
        env = new EnvVars();
        if (client != null) {
            dir = client.getRepository().getWorkTree();
            gitClient = client;
        } else {
            dir = new File(".");
            try {
                client = Git.with(TaskListener.NULL, new EnvVars())
                        .in(dir)
                        .using("git")
                        .getClient();
            } catch (IOException | InterruptedException e) {
                // Will assign null to gitClient
            }
            gitClient = client;
        }
        args = null;
    }

    CliGitCommand(GitClient client, String... arguments) throws GitException {
        this(client);
        args = new ArgumentListBuilder("git");
        args.add(arguments);
    }

    void initializeRepository() throws GitException, IOException, InterruptedException {
        initializeRepository("git-client-user", "git-client-user@example.com");
    }

    void initializeRepository(String userName, String userEmail)
            throws GitException, IOException, InterruptedException {
        gitClient.config(GitClient.ConfigLevel.LOCAL, "user.name", userName);
        gitClient.config(GitClient.ConfigLevel.LOCAL, "user.email", userEmail);
        gitClient.config(GitClient.ConfigLevel.LOCAL, "commit.gpgsign", "false");
        gitClient.config(GitClient.ConfigLevel.LOCAL, "tag.gpgSign", "false");
        // if the system running the tests has gpg.format=ssh then
        // this will fail as GpgConf does not support the enum so just
        // set it to something valid - even if it is not usable
        gitClient.config(GitClient.ConfigLevel.LOCAL, "gpg.format", "openpgp");
    }

    void removeRepositorySettings() throws GitException, IOException, InterruptedException {
        gitClient.config(GitClient.ConfigLevel.LOCAL, "user.name", null);
        gitClient.config(GitClient.ConfigLevel.LOCAL, "user.email", null);
        gitClient.config(GitClient.ConfigLevel.LOCAL, "commit.gpgsign", null);
        gitClient.config(GitClient.ConfigLevel.LOCAL, "tag.gpgSign", null);
        gitClient.config(GitClient.ConfigLevel.LOCAL, "gpg.format", null);
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
        Launcher.ProcStarter p = launcher.launch()
                .cmds(args)
                .envs(env)
                .stdout(bytesOut)
                .stderr(bytesErr)
                .pwd(dir);
        int status = p.start().joinWithTimeout(1, TimeUnit.MINUTES, listener);
        String result = bytesOut.toString(StandardCharsets.UTF_8);
        if (bytesErr.size() > 0) {
            result = result + "\nstderr not empty:\n" + bytesErr.toString(StandardCharsets.UTF_8);
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
            fail(Arrays.toString(output) + " did not match all strings in notFound: "
                    + Arrays.toString(expectedRegExes));
        }
    }

    String[] runWithoutAssert(String... arguments) throws IOException, InterruptedException {
        args = new ArgumentListBuilder("git");
        args.add(arguments);
        return run(false);
    }
}
