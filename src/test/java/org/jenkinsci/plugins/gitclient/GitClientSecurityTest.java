package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterAll;
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
 * Git client security tests,
 *
 * @author Mark Waite
 */
@ParameterizedClass(name = "{1},{0}")
@MethodSource("testConfiguration")
class GitClientSecurityTest {

    /* Test parameter - remote URL that is expected to fail with known exception */
    @Parameter(0)
    private String badRemoteUrl;
    /* Test parameter - should remote URL check be enabled */
    @Parameter(1)
    private boolean enableRemoteCheckUrl;

    /* Git client plugin repository directory. */
    private static final File SRC_REPO_DIR = new File(".git");

    /* Instance of object under test */
    private GitClient gitClient = null;

    /* Marker file used to check for SECURITY-1534 */
    private static String markerFileName = "/tmp/iwantmore-%d.pizza";

    private static final String DEFAULT_BRANCH_NAME = "master";

    @TempDir
    private File tempFolder;

    private File repoRoot = null;

    static {
        CliGitAPIImpl tempGitClient;
        try {
            tempGitClient = (CliGitAPIImpl) Git.with(TaskListener.NULL, new EnvVars())
                    .in(SRC_REPO_DIR)
                    .using("git")
                    .getClient();
        } catch (Exception e) {
            tempGitClient = null;
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
     * attack string. If remote check is selected to be disabled and the
     * attack is one of a known list of strings, then this function
     * will always return 'true' so that the remote checks will be
     * enabled.
     */
    private static boolean enableRemoteCheck(String attack) {
        boolean enabled = CONFIG_RANDOM.nextBoolean();
        if (!enabled
                && (attack.equals("-q")
                        || attack.equals("--quiet")
                        || attack.equals("-t")
                        || attack.equals("--tags")
                        || attack.startsWith("--upload-pack="))) {
            enabled = true;
        }
        return enabled;
    }

    static List<Arguments> testConfiguration() {
        markerFileName = markerFileName.formatted(CONFIG_RANDOM.nextInt()); // Unique enough file name
        List<Arguments> arguments = new ArrayList<>();
        for (String prefix : BAD_REMOTE_URL_PREFIXES) {
            /* insert markerFileName into test data */
            String formattedPrefix = prefix.formatted(markerFileName);

            /* Random remote URL with prefix */
            String firstChar = CONFIG_RANDOM.nextBoolean() ? " " : "";
            String middleChar = CONFIG_RANDOM.nextBoolean() ? " " : "";
            String lastChar = CONFIG_RANDOM.nextBoolean() ? " " : "";
            int remoteIndex = CONFIG_RANDOM.nextInt(VALID_REMOTES.length);
            String remoteUrl = firstChar + formattedPrefix + middleChar + VALID_REMOTES[remoteIndex] + lastChar;
            Arguments remoteUrlItem = Arguments.of(remoteUrl, enableRemoteCheck(formattedPrefix));
            arguments.add(remoteUrlItem);

            /* Random remote URL with prefix separated by a space */
            remoteIndex = CONFIG_RANDOM.nextInt(VALID_REMOTES.length);
            remoteUrl = formattedPrefix + " " + VALID_REMOTES[remoteIndex];
            Arguments remoteUrlItemOneSpace = Arguments.of(remoteUrl, enableRemoteCheck(formattedPrefix));
            arguments.add(remoteUrlItemOneSpace);

            /* Random remote URL with prefix and no separator */
            remoteIndex = CONFIG_RANDOM.nextInt(VALID_REMOTES.length);
            remoteUrl = formattedPrefix + VALID_REMOTES[remoteIndex];
            Arguments noSpaceItem = Arguments.of(remoteUrl, enableRemoteCheck(formattedPrefix));
            arguments.add(noSpaceItem);

            /* Remote URL with only the prefix */
            Arguments prefixItem = Arguments.of(formattedPrefix, enableRemoteCheck(formattedPrefix));
            arguments.add(prefixItem);
        }
        Collections.shuffle(arguments);
        return arguments.subList(0, 25);
    }

    @AfterAll
    static void resetRemoteCheckUrl() {
        org.jenkinsci.plugins.gitclient.CliGitAPIImpl.CHECK_REMOTE_URL = true;
    }

    @BeforeEach
    void setRemoteCheckUrl() {
        org.jenkinsci.plugins.gitclient.CliGitAPIImpl.CHECK_REMOTE_URL = enableRemoteCheckUrl;
    }

    @BeforeEach
    void setGitClient() throws Exception {
        repoRoot = newFolder(tempFolder, "junit");
        gitClient = Git.with(TaskListener.NULL, new EnvVars())
                .in(repoRoot)
                .using("git")
                .getClient();
        File gitDir = gitClient.getRepository().getDirectory();
        assertFalse(gitDir.isDirectory(), "Already found " + gitDir);
        gitClient.init_().workspace(repoRoot.getAbsolutePath()).execute();
        assertTrue(gitDir.isDirectory(), "Missing " + gitDir);
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
        "--negotiation-tip=" + DEFAULT_BRANCH_NAME,
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
        "https://github.com/jenkinsci/archetypes.git",
        "https://github.com/jenkinsci/archetypes.git",
        "https://git.assembla.com/git-plugin.3.git",
        "https://markewaite@bitbucket.org/markewaite/jenkins-pipeline-utils.git",
        "https://gitlab.com/MarkEWaite/git-client-plugin.git",
        "origin"
    };

    @BeforeEach
    void removeMarkerFile() throws Exception {
        File markerFile = new File(markerFileName);
        Files.deleteIfExists(markerFile.toPath());
    }

    @AfterEach
    void checkMarkerFile() {
        if (enableRemoteCheckUrl) {
            /* If remote checking is disabled, marker file is expected in several cases */
            File markerFile = new File(markerFileName);
            assertFalse(markerFile.exists(), "Marker file '" + markerFileName + "' detected after test");
        }
    }

    @Test
    @Issue("SECURITY-1534")
    void testGetHeadRev_String_SECURITY_1534() {
        String expectedMessage = enableRemoteCheckUrl ? "Invalid remote URL: " + badRemoteUrl : badRemoteUrl.trim();
        GitException e = assertThrows(GitException.class, () -> gitClient.getHeadRev(badRemoteUrl));
        assertThat(e.getMessage(), containsString(expectedMessage));
    }

    @Test
    @Issue("SECURITY-1534")
    void testGetHeadRev_String_String_SECURITY_1534() {
        String expectedMessage = enableRemoteCheckUrl ? "Invalid remote URL: " + badRemoteUrl : badRemoteUrl.trim();
        GitException e =
                assertThrows(GitException.class, () -> gitClient.getHeadRev(badRemoteUrl, DEFAULT_BRANCH_NAME));
        assertThat(e.getMessage(), containsString(expectedMessage));
    }

    @Test
    @Issue("SECURITY-1534")
    void testGetRemoteReferences_SECURITY_1534() {
        boolean headsOnly = random.nextBoolean();
        boolean tagsOnly = random.nextBoolean();
        String expectedMessage = enableRemoteCheckUrl ? "Invalid remote URL: " + badRemoteUrl : badRemoteUrl.trim();
        GitException e = assertThrows(
                GitException.class,
                () -> gitClient.getRemoteReferences(badRemoteUrl, "*" + DEFAULT_BRANCH_NAME, headsOnly, tagsOnly));
        assertThat(e.getMessage(), containsString(expectedMessage));
    }

    @Test
    @Issue("SECURITY-1534")
    void testGetRemoteSymbolicReferences_SECURITY_1534() {
        String expectedMessage = enableRemoteCheckUrl ? "Invalid remote URL: " + badRemoteUrl : badRemoteUrl.trim();
        GitException e = assertThrows(
                GitException.class, () -> gitClient.getRemoteSymbolicReferences(badRemoteUrl, DEFAULT_BRANCH_NAME));
        assertThat(e.getMessage(), containsString(expectedMessage));
    }

    @Test
    @Issue("SECURITY-1534")
    void testFetch_URIish_SECURITY_1534() throws Exception {
        String refSpecString = "+refs/heads/*:refs/remotes/origin/*";
        List<RefSpec> refSpecs = new ArrayList<>();
        RefSpec refSpec = new RefSpec(refSpecString);
        refSpecs.add(refSpec);
        URIish badRepoUrl = new URIish(badRemoteUrl.trim());
        GitException e = assertThrows(
                GitException.class,
                () -> gitClient.fetch_().from(badRepoUrl, refSpecs).execute());
        if (enableRemoteCheckUrl) {
            assertThat(e.getMessage(), containsString("Invalid remote URL: " + badRepoUrl.toPrivateASCIIString()));
        }
    }

    @Test
    @Issue("SECURITY-1534")
    void testFetch_String_SECURITY_1534() throws Exception {
        RefSpec refSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        gitClient.setRemoteUrl("origin", badRemoteUrl);
        GitException e = assertThrows(GitException.class, () -> gitClient.fetch("origin", refSpec));
        if (enableRemoteCheckUrl) {
            assertThat(e.getMessage(), containsString("Invalid remote URL: " + badRemoteUrl.trim()));
        }
    }

    @Test
    @Issue("SECURITY-1534")
    void testFetch_String_RefSpec_SECURITY_1534() throws Exception {
        RefSpec refSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        gitClient.setRemoteUrl("origin", badRemoteUrl);
        GitException e = assertThrows(GitException.class, () -> gitClient.fetch("origin", refSpec, refSpec, refSpec));
        if (enableRemoteCheckUrl) {
            assertThat(e.getMessage(), containsString("Invalid remote URL: " + badRemoteUrl.trim()));
        }
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
