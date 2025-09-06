package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import java.io.File;
import java.util.List;
import java.util.Random;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JGit supports the 'amazon-s3' protocol but the Jenkins git client
 * plugin is not tested with that protocol.  Command line git does not
 * support the 'amazon-s3' protocol.  Since command line git is the
 * reference implementation for the git client plugin, there is no
 * reason to support the 'amazon-s3' protocol.  Plugin releases 6.3.2
 * and before would report a class cast exception if a user used the
 * 'amazon-s3' protocol.  It was not usable by users.  Plugin releases
 * since 6.3.3 report that the amazon-s3 protocol is not supported.
 */
public class JGitAPIImplUnsupportedProtocolTest {

    private static JGitAPIImpl jgit = null;

    private static URIish url = null;
    private static String urlStr = null;

    private static String expectedMessage = null;

    private static final Random random = new Random();

    @TempDir
    private static File folder;

    @BeforeAll
    static void setup() throws Exception {
        url = new URIish("amazon-s3://@host/path-" + random.nextInt());
        urlStr = url.toString();
        jgit = new JGitAPIImpl(folder, TaskListener.NULL);
        expectedMessage = "unsupported protocol in URL " + urlStr;
    }

    @Test
    void testFetchCommand() throws Exception {
        List<RefSpec> refspecs = null;
        var thrown = assertThrows(
                GitException.class, () -> jgit.fetch_().from(url, refspecs).execute());
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    void testGetRemoteReferences() throws Exception {
        String pattern = ".*";
        boolean headsOnly = random.nextBoolean();
        boolean tagsOnly = random.nextBoolean();
        var thrown =
                assertThrows(GitException.class, () -> jgit.getRemoteReferences(urlStr, pattern, headsOnly, tagsOnly));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    void testGetRemoteSymbolicReferences() throws Exception {
        String pattern = ".*";
        var thrown = assertThrows(GitException.class, () -> jgit.getRemoteSymbolicReferences(urlStr, pattern));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    void testGetHeadRev() throws Exception {
        String branchSpec = "origin/my-branch-" + random.nextInt();
        var thrown = assertThrows(GitException.class, () -> jgit.getHeadRev(urlStr, branchSpec));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    void testSetRemoteUrl() throws Exception {
        String name = "upstream-" + random.nextInt();
        var thrown = assertThrows(GitException.class, () -> jgit.setRemoteUrl(name, urlStr));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    void testAddRemoteUrl() throws Exception {
        String name = "upstream-" + random.nextInt();
        var thrown = assertThrows(GitException.class, () -> jgit.addRemoteUrl(name, urlStr));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    void testCloneCommand() throws Exception {
        var thrown =
                assertThrows(GitException.class, () -> jgit.clone_().url(urlStr).execute());
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    void testAddSubmodule() throws Exception {
        var thrown = assertThrows(GitException.class, () -> jgit.addSubmodule(urlStr, "subdir"));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    void testPushCommand() throws Exception {
        var thrown = assertThrows(GitException.class, () -> jgit.push().to(url).execute());
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    void testPrune() throws Exception {
        // Create a local git repository
        jgit.init_().workspace(folder.getAbsolutePath()).execute();
        // Locally modify the remote URL inside existing local git repository
        String remoteName = "amazons3-remote";
        jgit.config(GitClient.ConfigLevel.LOCAL, "remote." + remoteName + ".url", urlStr);
        // Confirm that locally modified remote URL with amazon-s3 protocol throws
        var thrown = assertThrows(GitException.class, () -> jgit.prune(new RemoteConfig(new Config(), remoteName)));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    @Deprecated
    void testSetSubmoduleUrl() throws Exception {
        var thrown = assertThrows(GitException.class, () -> jgit.setSubmoduleUrl("submodule-name", urlStr));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    @Deprecated
    void testSetRemoteUrl3Args() throws Exception {
        // Create a local git repository so that remote URL is not altered in working directory
        String repoDir = folder.getAbsolutePath();
        jgit.init_().workspace(repoDir).execute();
        var thrown = assertThrows(GitException.class, () -> jgit.setRemoteUrl("remote-name", urlStr, repoDir));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }
}
