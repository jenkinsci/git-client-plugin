package org.jenkinsci.plugins.gitclient;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import hudson.plugins.git.Tag;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RemoteConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyCompatibleGitAPIImplTest {

    @TempDir
    private File tempFolder;

    private LegacyCompatibleGitAPIImpl git;
    private File repo;

    private final hudson.EnvVars env = new hudson.EnvVars();
    private final TaskListener listener = StreamTaskListener.fromStdout();
    private final ObjectId gitClientCommit = ObjectId.fromString("d771d97f1e126b1b01ea214ef245d2d5f432200e");
    private final ObjectId taggedCommit = ObjectId.fromString("2db88a20bba8e98b6710f06213f3b60940a63c7c");

    private static String defaultBranchName = "mast" + "er"; // Intentionally split string

    protected final String gitImpl = getGitImplementation();

    protected String getGitImplementation() {
        return "git";
    }

    /**
     * Determine the global default branch name.
     * Command line git is moving towards more inclusive naming.
     * Git 2.32.0 honors the configuration variable `init.defaultBranch` and uses it for the name of the initial branch.
     * This method reads the global configuration and uses it to set the value of `defaultBranchName`.
     */
    @BeforeAll
    static void computeDefaultBranchName() throws Exception {
        File configDir = Files.createTempDirectory("readGitConfig").toFile();
        CliGitCommand getDefaultBranchNameCmd = new CliGitCommand(Git.with(TaskListener.NULL, new hudson.EnvVars())
                .in(configDir)
                .using("git")
                .getClient());
        String[] output = getDefaultBranchNameCmd.runWithoutAssert("config", "--get", "init.defaultBranch");
        for (String s : output) {
            String result = s.trim();
            if (result != null && !result.isEmpty()) {
                defaultBranchName = result;
            }
        }
        assertTrue(configDir.delete(), "Failed to delete temporary readGitConfig directory");
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = newFolder(tempFolder, "junit");
        assertNotGitRepo(repo);
        git = (LegacyCompatibleGitAPIImpl)
                Git.with(listener, env).in(repo).using(gitImpl).getClient();
        assertNotGitRepo(repo);
        git.init();
        CliGitCommand gitCmd = new CliGitCommand(git);
        gitCmd.initializeRepository("Vojtěch legacy Zweibrücken-Šafařík", "email.from.git.client.test@example.com");
        assertIsGitRepo(repo);
    }

    private void assertNotGitRepo(File dir) {
        assertTrue(dir.isDirectory(), dir + " is not a directory");
        File gitDir = new File(dir, ".git");
        assertFalse(gitDir.isDirectory(), gitDir + " is a directory");
    }

    private void assertIsGitRepo(File dir) {
        assertTrue(dir.isDirectory(), dir + " is not a directory");
        File gitDir = new File(dir, ".git");
        assertTrue(gitDir.isDirectory(), gitDir + " is not a directory");
    }

    private File touch(String path, String content) throws Exception {
        File f = new File(repo, path);
        Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    @Deprecated
    void testCloneRemoteConfig() throws Exception {
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
        git.clone(remoteConfig);
        File[] files = git.workspace.listFiles();
        assertEquals(1, files.length, files.length + "files in " + Arrays.toString(files));
        assertEquals(".git", files[0].getName(), "Wrong file name");
    }

    @Test
    @Deprecated
    void testHasGitModules_default_ignored_arg() {
        assertFalse((new File(repo, ".gitmodules")).exists());
        assertFalse(git.hasGitModules("ignored treeIsh argument 1"));
    }

    @Test
    @Deprecated
    void testHasGitModules_default_no_arg() {
        assertFalse((new File(repo, ".gitmodules")).exists());
        assertFalse(git.hasGitModules());
    }

    private File commitTrackedFile() throws Exception {
        File trackedFile = touch("tracked-file", "tracked content " + UUID.randomUUID());
        git.add("tracked-file");
        git.commit("First commit");
        assertEquals(trackedFile.getParentFile(), repo); /* Is tracked file in correct directory */

        return trackedFile;
    }

    @Test
    @Deprecated
    void testShowRevisionThrowsGitException() throws Exception {
        commitTrackedFile();
        assertThrows(GitException.class, () -> git.showRevision(new Revision(gitClientCommit)));
    }

    @Test
    @Deprecated
    void testShowRevisionTrackedFile() throws Exception {
        commitTrackedFile();
        ObjectId head = git.getHeadRev(repo.getPath(), defaultBranchName);
        List<String> revisions = git.showRevision(new Revision(head));
        assertEquals("commit " + head.name(), revisions.get(0));
    }

    @Test
    @Deprecated
    void testGetTagsOnCommit_empty() throws Exception {
        List<Tag> result = git.getTagsOnCommit(taggedCommit.name());
        assertTrue(result.isEmpty(), "Tag list not empty: " + result);
    }

    @Test
    @Deprecated
    void testGetTagsOnCommit_non_empty() throws Exception {
        commitTrackedFile();
        List<Tag> result = git.getTagsOnCommit(taggedCommit.name());
        assertTrue(result.isEmpty(), "Tag list not empty: " + result);
    }

    @Test
    @Deprecated
    void testGetTagsOnCommit_SHA1() throws Exception {
        LegacyCompatibleGitAPIImpl myGit = (LegacyCompatibleGitAPIImpl)
                Git.with(listener, env).in(repo).using(gitImpl).getClient();
        List<Tag> result = myGit.getTagsOnCommit(taggedCommit.name());
        assertTrue(result.isEmpty(), "Tag list not empty: " + result);
    }

    @Test
    @Deprecated
    void testGetTagsOnCommit() throws Exception {
        LegacyCompatibleGitAPIImpl myGit = (LegacyCompatibleGitAPIImpl)
                Git.with(listener, env).in(repo).using(gitImpl).getClient();
        commitTrackedFile();
        final String uniqueTagName = "testGetTagsOnCommit-" + UUID.randomUUID();
        final String tagMessage = "Tagged with " + uniqueTagName;
        myGit.tag(uniqueTagName, tagMessage);
        List<Tag> result = myGit.getTagsOnCommit(uniqueTagName);
        myGit.deleteTag(uniqueTagName);
        assertFalse(result.isEmpty(), "Tag list empty for " + uniqueTagName);
        assertNull(
                result.get(0).getCommitSHA1(),
                "Unexpected SHA1 for commit: " + result.get(0).getCommitMessage());
        assertNull(
                result.get(0).getCommitMessage(),
                "Unexpected message for commit: " + result.get(0).getCommitSHA1());
    }

    @Test
    @Deprecated
    void testGetTagsOnCommit_sha1() throws Exception {
        LegacyCompatibleGitAPIImpl myGit = (LegacyCompatibleGitAPIImpl)
                Git.with(listener, env).in(repo).using(gitImpl).getClient();
        String revName = "2db88a20bba8e98b6710f06213f3b60940a63c7c";
        List<Tag> result = myGit.getTagsOnCommit(revName);
        assertTrue(result.isEmpty(), "Tag list not empty for " + revName);
    }

    @Test
    void testLsTreeThrows() {
        Class<? extends Exception> expectedExceptionClass =
                git instanceof CliGitAPIImpl ? GitException.class : NullPointerException.class;
        assertThrows(expectedExceptionClass, () -> git.lsTree("HEAD"));
    }

    @Test
    void testLsTreeOneCommit() throws Exception {
        commitTrackedFile();
        List<IndexEntry> lsTree = git.lsTree("HEAD");
        assertEquals(1, lsTree.size(), "lsTree wrong size - " + lsTree);
        assertEquals("tracked-file", lsTree.get(0).getFile());
    }

    @Test
    void testExtractBranchNameFromBranchSpec() {
        assertEquals(defaultBranchName, git.extractBranchNameFromBranchSpec(defaultBranchName));
        assertEquals(defaultBranchName, git.extractBranchNameFromBranchSpec("origin/" + defaultBranchName));
        assertEquals(defaultBranchName, git.extractBranchNameFromBranchSpec("*/" + defaultBranchName));
        assertEquals("maste*", git.extractBranchNameFromBranchSpec("ori*/maste*"));
        assertEquals(
                "refs/heads/" + defaultBranchName,
                git.extractBranchNameFromBranchSpec("remotes/origin/" + defaultBranchName));
        assertEquals(
                "refs/heads/" + defaultBranchName,
                git.extractBranchNameFromBranchSpec("refs/heads/" + defaultBranchName));
        assertEquals(
                "refs/heads/origin/" + defaultBranchName,
                git.extractBranchNameFromBranchSpec("refs/heads/origin/" + defaultBranchName));
        assertEquals(defaultBranchName, git.extractBranchNameFromBranchSpec("other/" + defaultBranchName));
        assertEquals(
                "refs/heads/" + defaultBranchName,
                git.extractBranchNameFromBranchSpec("refs/remotes/origin/" + defaultBranchName));
        assertEquals("refs/tags/mytag", git.extractBranchNameFromBranchSpec("refs/tags/mytag"));
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
