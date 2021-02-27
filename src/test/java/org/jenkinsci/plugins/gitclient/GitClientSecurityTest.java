package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.jvnet.hudson.test.Issue;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Git client security tests,
 *
 * @author Mark Waite
 */
@RunWith(Parameterized.class)
public class GitClientSecurityTest {

    /* Test parameter - remote URL that is expected to fail with known exception */
    private final String badRemoteUrl;
    /* Test parameter - should remote URL check be enabled */
    private final boolean enableRemoteCheckUrl;

    /* Git client plugin repository directory. */
    private static final File SRC_REPO_DIR = new File(".git");

    /* Instance of object under test */
    private GitClient gitClient = null;

    /* Marker file used to check for SECURITY-1534 */
    private static String markerFileName = "/tmp/iwantmore-%d.pizza";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private File repoRoot = null;

    public GitClientSecurityTest(final String badRemoteUrl, final boolean enableRemoteCheckUrl) throws IOException, InterruptedException {
        this.badRemoteUrl = badRemoteUrl;
        this.enableRemoteCheckUrl = enableRemoteCheckUrl;
    }

    /* Capabilities of command line git in current environment */
    private static final boolean CLI_GIT_SUPPORTS_OPERAND_SEPARATOR;
    private static final boolean CLI_GIT_SUPPORTS_SYMREF;

    static {
        CliGitAPIImpl tempGitClient;
        try {
            tempGitClient = (CliGitAPIImpl) Git.with(TaskListener.NULL, new EnvVars()).in(SRC_REPO_DIR).using("git").getClient();
        } catch (Exception e) {
            tempGitClient = null;
        }
        if (tempGitClient != null) {
            CLI_GIT_SUPPORTS_OPERAND_SEPARATOR = tempGitClient.isAtLeastVersion(2, 8, 0, 0);
            CLI_GIT_SUPPORTS_SYMREF = tempGitClient.isAtLeastVersion(2, 8, 0, 0);
        } else {
            CLI_GIT_SUPPORTS_OPERAND_SEPARATOR = false;
            CLI_GIT_SUPPORTS_SYMREF = false;
        }
    }

    private static final Random CONFIG_RANDOM = new Random();

    /*
     * SECURITY-1534 notes that repository URL's provided by the user
     * were not sanity checked before being passed to git ls-remote
     * and git fetch. A sanity check is now enabled by default.
     *
     * As a backwards compatibility 'escape hatch', a Jenkins command
     * line argument can disable the sanity checks. Disabling the
     * checks then relies on command line git to perform the sanity
     * checks.
     *
     * This function returns a randomly selected value to enable or
     * disable the repository URL check based on the contents of the
     * attack string. If remote check is selected to be disabled and
     * the command line git implementation does not have full support
     * for the '--' separator between options and operands and the
     * attack is one of a known list of strings, then this function
     * will always return 'true' so that the remote checks will be
     * enabled.
     *
     * Returning 'false' in those cases on certain older command line
     * git implementations (git 1.8.3 on CentOS 7, git 2.7.4 on Ubuntu
     * 16) would cause the tested code to not throw an exception
     * because those command line git versions do not fully support
     * '--' to separate options and operands.
     */
    private static boolean enableRemoteCheck(String attack) {
        boolean enabled = CONFIG_RANDOM.nextBoolean();
        if (!enabled &&
            !CLI_GIT_SUPPORTS_OPERAND_SEPARATOR &&
            (attack.equals("-q")
             || attack.equals("--quiet")
             || attack.equals("-t")
             || attack.equals("--tags")
             || attack.startsWith("--upload-pack=")
            )) {
            enabled = true;
        }
        return enabled;
    }

    @Parameterized.Parameters(name = "{1},{0}")
    public static Collection testConfiguration() throws Exception {
        markerFileName = String.format(markerFileName, CONFIG_RANDOM.nextInt()); // Unique enough file name
        List<Object[]> arguments = new ArrayList<>();
        for (String prefix : BAD_REMOTE_URL_PREFIXES) {
            /* insert markerFileName into test data */
            String formattedPrefix = String.format(prefix, markerFileName);

            /* Random remote URL with prefix */
            String firstChar = CONFIG_RANDOM.nextBoolean() ? " " : "";
            String middleChar = CONFIG_RANDOM.nextBoolean() ? " " : "";
            String lastChar = CONFIG_RANDOM.nextBoolean() ? " " : "";
            int remoteIndex = CONFIG_RANDOM.nextInt(VALID_REMOTES.length);
            String remoteUrl = firstChar + formattedPrefix + middleChar + VALID_REMOTES[remoteIndex] + lastChar;
            Object[] remoteUrlItem = {remoteUrl, enableRemoteCheck(formattedPrefix)};
            arguments.add(remoteUrlItem);

            /* Random remote URL with prefix separated by a space */
            remoteIndex = CONFIG_RANDOM.nextInt(VALID_REMOTES.length);
            remoteUrl = formattedPrefix + " " + VALID_REMOTES[remoteIndex];
            Object[] remoteUrlItemOneSpace = {remoteUrl, enableRemoteCheck(formattedPrefix)};
            arguments.add(remoteUrlItemOneSpace);

            /* Random remote URL with prefix and no separator */
            remoteIndex = CONFIG_RANDOM.nextInt(VALID_REMOTES.length);
            remoteUrl = formattedPrefix + VALID_REMOTES[remoteIndex];
            Object[] noSpaceItem = {remoteUrl, enableRemoteCheck(formattedPrefix)};
            arguments.add(noSpaceItem);

            /* Remote URL with only the prefix */
            Object[] prefixItem = {formattedPrefix, enableRemoteCheck(formattedPrefix)};
            arguments.add(prefixItem);
        }
        Collections.shuffle(arguments);
        return arguments.subList(0, 25);
    }

    @AfterClass
    public static void resetRemoteCheckUrl() {
        org.jenkinsci.plugins.gitclient.CliGitAPIImpl.CHECK_REMOTE_URL = true;
    }

    @Before
    public void setRemoteCheckUrl() {
        org.jenkinsci.plugins.gitclient.CliGitAPIImpl.CHECK_REMOTE_URL = enableRemoteCheckUrl;
    }

    @Before
    public void setGitClient() throws IOException, InterruptedException {
        repoRoot = tempFolder.newFolder();
        gitClient = Git.with(TaskListener.NULL, new EnvVars()).in(repoRoot).using("git").getClient();
        File gitDir = gitClient.getRepository().getDirectory();
        assertFalse("Already found " + gitDir, gitDir.isDirectory());
        gitClient.init_().workspace(repoRoot.getAbsolutePath()).execute();
        assertTrue("Missing " + gitDir, gitDir.isDirectory());
        gitClient.setRemoteUrl("origin", SRC_REPO_DIR.getAbsolutePath());
    }

    private final Random random = new Random();

    private static final String[] BAD_REMOTE_URL_PREFIXES = {
        "--sort version:refname",
        "--upload-pack=/usr/bin/id",
        "--upload-pack=`touch %s`",
        "-o",
        "-q",
        "-t",
        "-v",
        "`echo %s`",
        "--all",
        "-a",
        "--append",
        "--depth=1",
        "--shallow-since=2019-09-01",
        "--shallow-exclude=HEAD",
        "--unshallow",
        "--update-shallow",
        "--negotiation-tip=master",
        "--dry-run",
        "--force",
        "-f",
        "--keep",
        "-k",
        "--multiple",
        "--no-auto-gc",
        "-p",
        "--prune",
        "-P",
        "--prune-tags",
        "-n",
        "--no-tags",
        "--ref-map=+refs/heads/abc/*:refs/remotes/origin/abc/*",
        "--tags",
        "--recurse-submodules",
        "-j",
        "--jobs",
        "--no-recurse-submodules",
        "--recurse-submodules-default=on-demand",
        "--update-head-ok",
        "--upload-pack /usr/bin/id",
        "--progress",
        "--quiet",
        "-o /usr/bin/id",
        "--server-option=/usr/bin/id",
        "--show-forced-updates",
        "--no-show-forced-updates",
        "-4",
        "-6"
    };

    private static final String[] VALID_REMOTES = {
        "https://github.com/jenkinsci/platformlabeler-plugin.git",
        "git://github.com/jenkinsci/platformlabeler-plugin.git",
        "https://github.com/jenkinsci/archetypes.git",
        "git://github.com/jenkinsci/archetypes.git",
        "https://github.com/jenkinsci/archetypes.git",
        "https://git.assembla.com/git-plugin.3.git",
        "https://markewaite@bitbucket.org/markewaite/jenkins-pipeline-utils.git",
        "https://jenkins-git-plugin.git.beanstalkapp.com/git-client-plugin.git",
        "https://gitlab.com/MarkEWaite/git-client-plugin.git",
        "origin"
    };

    @Before
    public void removeMarkerFile() throws Exception {
        File markerFile = new File(markerFileName);
        Files.deleteIfExists(markerFile.toPath());
    }

    @After
    public void checkMarkerFile() {
        if (enableRemoteCheckUrl) { /* If remote checking is disabled, marker file is expected in several cases */
            File markerFile = new File(markerFileName);
            assertFalse("Marker file '" + markerFileName + "' detected after test", markerFile.exists());
        }
    }

    @Test
    @Issue("SECURITY-1534")
    public void testGetHeadRev_String_SECURITY_1534() throws Exception {
        String expectedMessage = enableRemoteCheckUrl ? "Invalid remote URL: " + badRemoteUrl : badRemoteUrl.trim();
        GitException e = assertThrows(GitException.class,
                                      () -> {
                                          gitClient.getHeadRev(badRemoteUrl);
                                      });
        assertThat(e.getMessage(), containsString(expectedMessage));
    }

    @Test
    @Issue("SECURITY-1534")
    public void testGetHeadRev_String_String_SECURITY_1534() throws Exception {
        String expectedMessage = enableRemoteCheckUrl ? "Invalid remote URL: " + badRemoteUrl : badRemoteUrl.trim();
        GitException e = assertThrows(GitException.class,
                                      () -> {
                                          gitClient.getHeadRev(badRemoteUrl, "master");
                                      });
        assertThat(e.getMessage(), containsString(expectedMessage));
    }

    @Test
    @Issue("SECURITY-1534")
    public void testGetRemoteReferences_SECURITY_1534() throws Exception {
        boolean headsOnly = random.nextBoolean();
        boolean tagsOnly = random.nextBoolean();
        String expectedMessage = enableRemoteCheckUrl ? "Invalid remote URL: " + badRemoteUrl : badRemoteUrl.trim();
        GitException e = assertThrows(GitException.class,
                                      () -> {
                                          gitClient.getRemoteReferences(badRemoteUrl, "*master", headsOnly, tagsOnly);
                                      });
        assertThat(e.getMessage(), containsString(expectedMessage));
    }

    @Test
    @Issue("SECURITY-1534")
    public void testGetRemoteSymbolicReferences_SECURITY_1534() throws Exception {
        if (!CLI_GIT_SUPPORTS_SYMREF) {
            return;
        }
        String expectedMessage = enableRemoteCheckUrl ? "Invalid remote URL: " + badRemoteUrl : badRemoteUrl.trim();
        GitException e = assertThrows(GitException.class,
                                      () -> {
                                          gitClient.getRemoteSymbolicReferences(badRemoteUrl, "master");
                                      });
        assertThat(e.getMessage(), containsString(expectedMessage));
    }

    @Test
    @Issue("SECURITY-1534")
    public void testFetch_URIish_SECURITY_1534() throws Exception {
        String refSpecString = "+refs/heads/*:refs/remotes/origin/*";
        List<RefSpec> refSpecs = new ArrayList<>();
        RefSpec refSpec = new RefSpec(refSpecString);
        refSpecs.add(refSpec);
        URIish badRepoUrl = new URIish(badRemoteUrl.trim());
        GitException e = assertThrows(GitException.class,
                                      () -> {
                                          gitClient.fetch_().from(badRepoUrl, refSpecs).execute();	
                                      });
        if (enableRemoteCheckUrl) {
            assertThat(e.getMessage(), containsString("Invalid remote URL: " + badRepoUrl.toPrivateASCIIString()));
        }
    }

    @Test
    @Issue("SECURITY-1534")
    public void testFetch_String_SECURITY_1534() throws Exception {
        RefSpec refSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        gitClient.setRemoteUrl("origin", badRemoteUrl);
        GitException e = assertThrows(GitException.class,
                                      () -> {
                                          gitClient.fetch("origin", refSpec);
                                      });
        if (enableRemoteCheckUrl) {
            assertThat(e.getMessage(), containsString("Invalid remote URL: " + badRemoteUrl.trim()));
        }
    }

    @Test
    @Issue("SECURITY-1534")
    public void testFetch_String_RefSpec_SECURITY_1534() throws Exception {
        RefSpec refSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        gitClient.setRemoteUrl("origin", badRemoteUrl);
        GitException e = assertThrows(GitException.class,
                                      () -> {
                                          gitClient.fetch("origin", refSpec, refSpec, refSpec);
                                      });
        if (enableRemoteCheckUrl) {
            assertThat(e.getMessage(), containsString("Invalid remote URL: " + badRemoteUrl.trim()));
        }
    }
}
