package org.jenkinsci.plugins.gitclient;

import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import hudson.plugins.git.Tag;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LegacyCompatibleGitAPIImplTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private LegacyCompatibleGitAPIImpl git;
    private File repo;

    private final hudson.EnvVars env = new hudson.EnvVars();
    private final TaskListener listener = StreamTaskListener.fromStdout();
    private final ObjectId gitClientCommit = ObjectId.fromString("d771d97f1e126b1b01ea214ef245d2d5f432200e");
    private final ObjectId taggedCommit = ObjectId.fromString("2db88a20bba8e98b6710f06213f3b60940a63c7c");

    protected String gitImpl;

    public LegacyCompatibleGitAPIImplTest() {
        gitImpl = "git";
    }

    @Before
    public void setUp() throws IOException, InterruptedException {
        repo = tempFolder.newFolder();
        assertNotGitRepo(repo);
        git = (LegacyCompatibleGitAPIImpl) Git.with(listener, env).in(repo).using(gitImpl).getClient();
        assertNotGitRepo(repo);
        git.init();
        CliGitCommand gitCmd = new CliGitCommand(git);
        gitCmd.run("config", "user.name", "Vojtěch legacy Zweibrücken-Šafařík");
        gitCmd.run("config", "user.email", "email.from.git.client.test@example.com");
        assertIsGitRepo(repo);
    }

    private void assertNotGitRepo(File dir) {
        assertTrue(dir + " is not a directory", dir.isDirectory());
        File gitDir = new File(dir, ".git");
        assertFalse(gitDir + " is a directory", gitDir.isDirectory());
    }

    private void assertIsGitRepo(File dir) {
        assertTrue(dir + " is not a directory", dir.isDirectory());
        File gitDir = new File(dir, ".git");
        assertTrue(gitDir + " is not a directory", gitDir.isDirectory());
    }

    private File touch(String path, String content) throws IOException {
        File f = new File(repo, path);
        FileUtils.writeStringToFile(f, content, "UTF-8");
        return f;
    }

    private String cmd(boolean ignoreError, String... args) throws IOException, InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int st = new Launcher.LocalLauncher(listener).launch().pwd(repo).cmds(args).
                envs(env).stdout(out).join();
        String s = out.toString();
        if (!ignoreError) {
            if (s == null || s.isEmpty()) {
                s = StringUtils.join(args, ' ');
            }
            assertEquals(s, 0, st); /* Reports full output of failing commands */

        }
        return s;
    }

    private String log() throws IOException, InterruptedException {
        return cmd(false, "git", "log", "-n", "3");
    }

    private String log(ObjectId rev) throws IOException, InterruptedException {
        return cmd(false, "git", "log", "-n", "3", rev.name());
    }

    private String contentOf(File file) throws IOException {
        return FileUtils.readFileToString(file, "UTF-8");
    }

    @Test
    @Deprecated
    public void testCloneRemoteConfig() throws URISyntaxException, InterruptedException, IOException, ConfigInvalidException {
        if (gitImpl.equals("jgit")) {
            return;
        }
        Config config = new Config();
        /* Use local git-client-plugin repository as source for clone test */
        String remoteName = "localCopy";
        String localRepoPath = (new File(".")).getCanonicalPath().replace("\\", "/");
        String configText = "[remote \"" + remoteName + "\"]\n"
                + "url = " + localRepoPath + "\n"
                + "fetch = +refs/heads/*:refs/remotes/" + remoteName + "/*\n";
        config.fromText(configText);
        RemoteConfig remoteConfig = new RemoteConfig(config, remoteName);
        List<URIish> list = remoteConfig.getURIs();
        git.clone(remoteConfig);
        File[] files = git.workspace.listFiles();
        assertEquals(files.length + "files in " + Arrays.toString(files), 1, files.length);
        assertEquals("Wrong file name", ".git", files[0].getName());
    }

    @Test
    @Deprecated
    public void testHasGitModules_default_ignored_arg() {
        assertFalse((new File(repo, ".gitmodules")).exists());
        assertFalse(git.hasGitModules("ignored treeIsh argument 1"));
    }

    @Test
    @Deprecated
    public void testHasGitModules_default_no_arg() {
        assertFalse((new File(repo, ".gitmodules")).exists());
        assertFalse(git.hasGitModules());
    }

    private File commitTrackedFile() throws IOException, GitException, InterruptedException {
        File trackedFile = touch("tracked-file", "tracked content " + UUID.randomUUID().toString());
        git.add("tracked-file");
        git.commit("First commit");
        assertEquals(trackedFile.getParentFile(), repo); /* Is tracked file in correct directory */

        return trackedFile;
    }

    @Test
    @Deprecated
    public void testShowRevisionThrowsGitException() throws Exception {
        File trackedFile = commitTrackedFile();
        assertThrows(GitException.class,
                     () -> {
                         git.showRevision(new Revision(gitClientCommit));
                     });
    }

    @Test
    @Deprecated
    public void testShowRevisionTrackedFile() throws Exception {
        File trackedFile = commitTrackedFile();
        ObjectId head = git.getHeadRev(repo.getPath(), "master");
        List<String> revisions = git.showRevision(new Revision(head));
        assertEquals("commit " + head.name(), revisions.get(0));
    }

    @Test
    @Deprecated
    public void testGetTagsOnCommit_empty() throws Exception {
        List<Tag> result = git.getTagsOnCommit(taggedCommit.name());
        assertTrue("Tag list not empty: " + result, result.isEmpty());
    }

    @Test
    @Deprecated
    public void testGetTagsOnCommit_non_empty() throws Exception {
        File trackedFile = commitTrackedFile();
        List<Tag> result = git.getTagsOnCommit(taggedCommit.name());
        assertTrue("Tag list not empty: " + result, result.isEmpty());
    }

    @Test
    @Deprecated
    public void testGetTagsOnCommit_SHA1() throws Exception {
        LegacyCompatibleGitAPIImpl myGit = (LegacyCompatibleGitAPIImpl) Git.with(listener, env).in(repo).using(gitImpl).getClient();
        List<Tag> result = myGit.getTagsOnCommit(taggedCommit.name());
        assertTrue("Tag list not empty: " + result, result.isEmpty());
    }

    @Test
    @Deprecated
    public void testGetTagsOnCommit() throws Exception {
        LegacyCompatibleGitAPIImpl myGit = (LegacyCompatibleGitAPIImpl) Git.with(listener, env).in(repo).using(gitImpl).getClient();
        File trackedFile = commitTrackedFile();
        final String uniqueTagName = "testGetTagsOnCommit-" + UUID.randomUUID().toString();
        final String tagMessage = "Tagged with " + uniqueTagName;
        myGit.tag(uniqueTagName, tagMessage);
        List<Tag> result = myGit.getTagsOnCommit(uniqueTagName);
        myGit.deleteTag(uniqueTagName);
        assertFalse("Tag list empty for " + uniqueTagName, result.isEmpty());
        assertNull("Unexpected SHA1 for commit: " + result.get(0).getCommitMessage(), result.get(0).getCommitSHA1());
        assertNull("Unexpected message for commit: " + result.get(0).getCommitSHA1(), result.get(0).getCommitMessage());
    }

    @Test
    @Deprecated
    public void testGetTagsOnCommit_sha1() throws Exception {
        LegacyCompatibleGitAPIImpl myGit = (LegacyCompatibleGitAPIImpl) Git.with(listener, env).in(repo).using(gitImpl).getClient();
        String revName = "2db88a20bba8e98b6710f06213f3b60940a63c7c";
        List<Tag> result = myGit.getTagsOnCommit(revName);
        assertTrue("Tag list not empty for " + revName, result.isEmpty());
    }

    @Test
    public void testLsTreeThrows() throws Exception {
        Class expectedExceptionClass = git instanceof CliGitAPIImpl ? GitException.class : NullPointerException.class;
        assertThrows(expectedExceptionClass,
                     () -> {
                         git.lsTree("HEAD");
                     });
    }

    @Test
    public void testLsTreeOneCommit() throws Exception {
        File trackedFile = commitTrackedFile();
        List<IndexEntry> lsTree = git.lsTree("HEAD");
        assertEquals("lsTree wrong size - " + lsTree, 1, lsTree.size());
        assertEquals("tracked-file", lsTree.get(0).getFile());
    }

    @Test
    public void testExtractBranchNameFromBranchSpec() {
        assertEquals("master", git.extractBranchNameFromBranchSpec("master"));
        assertEquals("master", git.extractBranchNameFromBranchSpec("origin/master"));
        assertEquals("master", git.extractBranchNameFromBranchSpec("*/master"));
        assertEquals("maste*", git.extractBranchNameFromBranchSpec("ori*/maste*"));
        assertEquals("refs/heads/master", git.extractBranchNameFromBranchSpec("remotes/origin/master"));
        assertEquals("refs/heads/master", git.extractBranchNameFromBranchSpec("refs/heads/master"));
        assertEquals("refs/heads/origin/master", git.extractBranchNameFromBranchSpec("refs/heads/origin/master"));
        assertEquals("master", git.extractBranchNameFromBranchSpec("other/master"));
        assertEquals("refs/heads/master", git.extractBranchNameFromBranchSpec("refs/remotes/origin/master"));
        assertEquals("refs/tags/mytag", git.extractBranchNameFromBranchSpec("refs/tags/mytag"));
    }
}
