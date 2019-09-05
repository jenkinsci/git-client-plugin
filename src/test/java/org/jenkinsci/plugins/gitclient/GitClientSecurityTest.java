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
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.jvnet.hudson.test.Issue;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/**
 * Git client security tests,
 *
 * @author Mark Waite
 */
@RunWith(Parameterized.class)
public class GitClientSecurityTest {

    /* Test parameter - remote URL that is expected to fail with known exception */
    private final String badRemoteUrl;

    /* Git client plugin repository directory. */
    private static final File SRC_REPO_DIR = new File(".git");

    /* GitClient for plugin development repository. */
    private final GitClient srcGitClient;

    /* Instance of object under test */
    private GitClient gitClient = null;

    /* Capabilities of command line git in current environment */
    private final boolean CLI_GIT_SUPPORTS_SYMREF;

    /* Marker file used to check for SECURITY-1534 */
    private static String markerFileName = "/tmp/iwantmore-%d.pizza";

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private File repoRoot = null;

    public GitClientSecurityTest(final String badRemoteUrl) throws IOException, InterruptedException {
        this.badRemoteUrl = badRemoteUrl;
        this.srcGitClient = Git.with(TaskListener.NULL, new EnvVars()).in(SRC_REPO_DIR).using("git").getClient();

        CliGitAPIImpl cliGitClient;
        if (this.srcGitClient instanceof CliGitAPIImpl) {
            cliGitClient = (CliGitAPIImpl) this.srcGitClient;
        } else {
            cliGitClient = (CliGitAPIImpl) Git.with(TaskListener.NULL, new EnvVars()).in(SRC_REPO_DIR).using("git").getClient();
        }
        CLI_GIT_SUPPORTS_SYMREF = cliGitClient.isAtLeastVersion(2, 8, 0, 0);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection testConfiguration() {
        final Random configRandom = new Random();
        markerFileName = String.format(markerFileName, configRandom.nextInt()); // Unique enough file name
        List<Object[]> arguments = new ArrayList<>();
        for (String prefix : BAD_REMOTE_URL_PREFIXES) {
            String formattedPrefix = String.format(prefix, markerFileName);
            /* insert markerFileName into test data */
            int remoteIndex = configRandom.nextInt(VALID_REMOTES.length);
            /* Choose a random remote URL */
            String firstChar = configRandom.nextBoolean() ? " " : "";
            String middleChar = configRandom.nextBoolean() ? " " : "";
            String lastChar = configRandom.nextBoolean() ? " " : "";
            String remoteUrl = firstChar + formattedPrefix + middleChar + VALID_REMOTES[remoteIndex] + lastChar;
            remoteIndex = configRandom.nextInt(VALID_REMOTES.length);
            Object[] remoteUrlItem = {remoteUrl};
            arguments.add(remoteUrlItem);
            Object[] remoteUrlItemOneSpace = {formattedPrefix + " " + VALID_REMOTES[remoteIndex]};
            remoteIndex = configRandom.nextInt(VALID_REMOTES.length);
            arguments.add(remoteUrlItemOneSpace);
            Object[] noSpaceItem = {formattedPrefix + VALID_REMOTES[remoteIndex]};
            arguments.add(noSpaceItem);
            Object[] prefixItem = {formattedPrefix};
            arguments.add(prefixItem);
        }
        Collections.shuffle(arguments);
        return arguments.subList(0, 25);
    }

    @BeforeClass
    public static void setCliGitDefaults() throws Exception {
        /* Command line git commands fail unless certain default values are set */
        CliGitCommand gitCmd = new CliGitCommand(null);
        gitCmd.setDefaults();
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
        "--get-url",
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
        "--prune",
        "--prune-tags",
        "--no-tags",
        "-t",
        "--tags",
        "--recurse-submodules",
        "--jobs",
        "--no-recurse-submodules",
        "--update-head-ok",
        "--progress",
        "--quiet"
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
        File markerFile = new File(markerFileName);
        assertThat(markerFileName + " unexpectedly detected after test", markerFile.exists(), is(false));
    }

    @Test
    @Issue("SECURITY-1534")
    public void testGetHeadRev_String_SECURITY_1534() throws Exception {
        thrown.expect(GitException.class);
        thrown.expectMessage(containsString("Invalid remote URL: " + badRemoteUrl));
        gitClient.getHeadRev(badRemoteUrl);
    }

    @Test
    @Issue("SECURITY-1534")
    public void testGetHeadRev_String_String_SECURITY_1534() throws Exception {
        thrown.expect(GitException.class);
        thrown.expectMessage(containsString("Invalid remote URL: " + badRemoteUrl));
        gitClient.getHeadRev(badRemoteUrl, "master");
    }

    @Test
    @Issue("SECURITY-1534")
    public void testGetRemoteReferences_SECURITY_1534() throws Exception {
        boolean headsOnly = random.nextBoolean();
        boolean tagsOnly = random.nextBoolean();
        thrown.expect(GitException.class);
        thrown.expectMessage(containsString("Invalid remote URL: " + badRemoteUrl));
        gitClient.getRemoteReferences(badRemoteUrl, "*master", headsOnly, tagsOnly);
    }

    @Test
    @Issue("SECURITY-1534")
    public void testGetRemoteSymbolicReferences_SECURITY_1534() throws Exception {
        assumeTrue(CLI_GIT_SUPPORTS_SYMREF);
        thrown.expect(GitException.class);
        thrown.expectMessage(containsString("Invalid remote URL: " + badRemoteUrl));
        gitClient.getRemoteSymbolicReferences(badRemoteUrl, "master");
    }

    @Test
    @Issue("SECURITY-1534")
    public void testFetch_URIish_SECURITY_1534() throws Exception {
        String refSpecString = "+refs/heads/*:refs/remotes/origin/*";
        List<RefSpec> refSpecs = new ArrayList<>();
        RefSpec refSpec = new RefSpec(refSpecString);
        refSpecs.add(refSpec);
        URIish badRepoUrl = new URIish(badRemoteUrl.trim());
        thrown.expect(GitException.class);
        thrown.expectMessage(containsString("Invalid remote URL: " + badRepoUrl.toPrivateASCIIString()));
        gitClient.fetch_().from(badRepoUrl, refSpecs).execute();
    }

    @Test
    @Issue("SECURITY-1534")
    public void testFetch_String_SECURITY_1534() throws Exception {
        RefSpec refSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        gitClient.setRemoteUrl("origin", badRemoteUrl);
        thrown.expect(GitException.class);
        thrown.expectMessage(containsString("Invalid remote URL: " + badRemoteUrl.trim()));
        gitClient.fetch("origin", refSpec);
    }

    @Test
    @Issue("SECURITY-1534")
    public void testFetch_String_RefSpec_SECURITY_1534() throws Exception {
        RefSpec refSpec = new RefSpec("+refs/heads/*:refs/remotes/origin/*");
        gitClient.setRemoteUrl("origin", badRemoteUrl);
        thrown.expect(GitException.class);
        thrown.expectMessage(containsString("Invalid remote URL: " + badRemoteUrl.trim()));
        gitClient.fetch("origin", refSpec, refSpec, refSpec);
    }
}
