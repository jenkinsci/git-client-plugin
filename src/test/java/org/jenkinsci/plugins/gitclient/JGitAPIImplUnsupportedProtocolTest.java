package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import java.util.List;
import java.util.Random;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * JGit supports the 'amazon-s3' protocol but the Jenkins git client
 * plugin is not tested with that protocol.  Command line git does not
 * support the 'amazon-s3' protocol.  Since command line git is the
 * reference implementation for the git client plugin, there is no
 * reason to support the 'amazon-s3' protocol.  Plugin releases 6.2.0
 * and before would report a class cast exception if a user used the
 * 'amazon-s3' protocol.  It was not usable by users.
 */
public class JGitAPIImplUnsupportedProtocolTest {

    private JGitAPIImpl jgit = null;

    private URIish url = null;
    private String urlStr = null;

    private String expectedMessage = null;

    private final Random random = new Random();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Before
    public void setup() throws Exception {
        url = new URIish("amazon-s3://@host/path-" + random.nextInt());
        urlStr = url.toString();
        jgit = new JGitAPIImpl(folder.getRoot(), TaskListener.NULL);
        expectedMessage = "unsupported protocol in URL " + urlStr;
    }

    @Test
    public void testFetchCommand() throws Exception {
        List<RefSpec> refspecs = null;
        var thrown = assertThrows(
                GitException.class, () -> jgit.fetch_().from(url, refspecs).execute());
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    public void testGetRemoteReferences() throws Exception {
        String pattern = ".*";
        boolean headsOnly = random.nextBoolean();
        boolean tagsOnly = random.nextBoolean();
        var thrown =
                assertThrows(GitException.class, () -> jgit.getRemoteReferences(urlStr, pattern, headsOnly, tagsOnly));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    public void testGetRemoteSymbolicReferences() throws Exception {
        String pattern = ".*";
        var thrown = assertThrows(GitException.class, () -> jgit.getRemoteSymbolicReferences(urlStr, pattern));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    public void testGetHeadRev() throws Exception {
        String branchSpec = "origin/my-branch-" + random.nextInt();
        var thrown = assertThrows(GitException.class, () -> jgit.getHeadRev(urlStr, branchSpec));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    public void testSetRemoteUrl() throws Exception {
        String name = "upstream-" + random.nextInt();
        var thrown = assertThrows(GitException.class, () -> jgit.setRemoteUrl(name, urlStr));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    public void testAddRemoteUrl() throws Exception {
        String name = "upstream-" + random.nextInt();
        var thrown = assertThrows(GitException.class, () -> jgit.addRemoteUrl(name, urlStr));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    public void testCloneCommand() throws Exception {
        var thrown =
                assertThrows(GitException.class, () -> jgit.clone_().url(urlStr).execute());
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    public void testAddSubmodule() throws Exception {
        var thrown = assertThrows(GitException.class, () -> jgit.addSubmodule(urlStr, "subdir"));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    public void testPushCommand() throws Exception {
        var thrown = assertThrows(GitException.class, () -> jgit.push().to(url).execute());
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    public void testPrune() throws Exception {
        // Create a local git repository
        jgit.init_().workspace(folder.getRoot().getAbsolutePath()).execute();
        // Locally modify the remote URL inside existing local git repository
        String remoteName = "amazons3-remote";
        jgit.config(GitClient.ConfigLevel.LOCAL, "remote." + remoteName + ".url", urlStr);
        // Confirm that locally modified remote URL with amazon-s3 protocol throws
        var thrown = assertThrows(GitException.class, () -> jgit.prune(new RemoteConfig(new Config(), remoteName)));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    @Deprecated
    public void testSetSubmoduleUrl() throws Exception {
        var thrown = assertThrows(GitException.class, () -> jgit.setSubmoduleUrl("submodule-name", urlStr));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }

    @Test
    @Deprecated
    public void testSetRemoteUrl3Args() throws Exception {
        // Create a local git repository so that remote URL is not altered in working directory
        String repoDir = folder.getRoot().getAbsolutePath();
        jgit.init_().workspace(repoDir).execute();
        var thrown = assertThrows(GitException.class, () -> jgit.setRemoteUrl("remote-name", urlStr, repoDir));
        assertThat(thrown.getMessage(), is(expectedMessage));
    }
}
