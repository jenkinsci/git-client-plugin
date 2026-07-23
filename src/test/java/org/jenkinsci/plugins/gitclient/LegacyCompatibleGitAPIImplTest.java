package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
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
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

    // -----------------------------------------------------------------------
    // getSubmodulesUrls() and findParameterizedReferenceRepository() tests [JENKINS-64383]
    // NOTE: The name "getSubmodulesUrls" is a bit misleading here, so far.
    // It's named for the use case (a fanout reference repo that *might* contain
    // submodule-like repos), but the implementation currently looks for
    // "any subdir with a git objects dir", not for registered git submodules.
    // Code has a "TODO" that is not implemented yet:
    //   If current repo is NOT bare, also check git submodules
    //   registered in .gitmodules for a faster possible answer
    // -----------------------------------------------------------------------

    /**
     * Creates a subdirectory inside {@code parent}, initializes a git repo
     * there, and registers a remote "origin" pointing to {@code remoteUrl}.
     */
    private File createSubdirWithRemote(File parent, String dirName, String remoteUrl) throws Exception {
        File subDir = newFolder(parent, dirName);
        GitClient subGit = Git.with(listener, env).in(subDir).using(gitImpl).getClient();
        subGit.init();
        new CliGitCommand(subGit).run("remote", "add", "origin", remoteUrl);
        return subDir;
    }

    @Test
    void testGetSubmodulesUrls_noNeedle_returnsAllSubdirs() throws Exception {
        File base = newFolder(tempFolder, "refrepo-noneedle");
        File repoAlpha = createSubdirWithRemote(base, "alpha-repo", "https://github.com/example/alpha-project.git");
        File repoBeta = createSubdirWithRemote(base, "beta-repo", "https://github.com/example/beta-project.git");

        AbstractMap.SimpleEntry<Boolean, LinkedHashSet<List<String>>> result =
                git.getSubmodulesUrls(base.getAbsolutePath());

        assertFalse(result.getKey(), "No needle: flag should be false");
        LinkedHashSet<List<String>> entries = result.getValue();
        assertFalse(entries.isEmpty(), "Should find at least one subdir");
        Set<String> foundDirs =
                entries.stream().map(e -> new File(e.get(0)).getAbsolutePath()).collect(Collectors.toSet());
        assertThat("alpha-repo should appear in results", foundDirs, hasItem(repoAlpha.getAbsolutePath()));
        assertThat("beta-repo should appear in results", foundDirs, hasItem(repoBeta.getAbsolutePath()));
    }

    @Test
    void testGetSubmodulesUrls_exactNeedleMatch_promotedToFirst() throws Exception {
        // "target-basename" dir is named to match the needle's URL basename, so it is
        // probed in the prefix-search phase (before any listFiles() walk).  It holds a
        // *different* URL -> no exact match -> accumulated into result first.
        // "aaa-exact" holds the exact needle URL and is found in the general walk that
        // follows.  The exact-match return promotes it to result_best[0] with all
        // previously accumulated entries after it, giving at least 2 entries total.
        String needleUrl = "https://github.com/example/target-basename.git";
        File base = newFolder(tempFolder, "refrepo-exactmatch");
        createSubdirWithRemote(base, "target-basename", "https://github.com/other-org/not-the-target.git");
        File repoExact = createSubdirWithRemote(base, "aaa-exact", needleUrl);

        AbstractMap.SimpleEntry<Boolean, LinkedHashSet<List<String>>> result =
                git.getSubmodulesUrls(base.getAbsolutePath(), needleUrl, true);

        assertTrue(result.getKey(), "Exact URL match: flag should be true");
        LinkedHashSet<List<String>> entries = result.getValue();
        assertThat(
                "Exact-match entry + previously accumulated entry = at least 2",
                entries.size(),
                greaterThanOrEqualTo(2));
        String firstDir = entries.iterator().next().get(0);
        assertThat(
                "Exact-match repo is promoted to first position",
                new File(firstDir).getAbsolutePath(),
                is(repoExact.getAbsolutePath()));
    }

    @Test
    void testFindParameterizedReferenceRepository_submodules_exactMatchPickedAsResult() throws Exception {
        // Same two-repo setup: prefix-search accumulates "target-basename" (non-exact),
        // then the general walk finds "aaa-exact" (exact).  The consuming code uses the
        // first entry in the result set, which is the exact match.
        String needleUrl = "https://github.com/example/target-basename.git";
        File base = newFolder(tempFolder, "refrepo-find-exact");
        createSubdirWithRemote(base, "target-basename", "https://github.com/other-org/not-the-target.git");
        File repoExact = createSubdirWithRemote(base, "aaa-exact", needleUrl);
        String reference = base.getAbsolutePath() + "/${GIT_SUBMODULES}";

        File result = git.findParameterizedReferenceRepository(reference, needleUrl);

        assertNotNull(result, "Should return a result for an exact URL match");
        assertThat(
                "Exact-match subdir should be returned", result.getCanonicalPath(), is(repoExact.getCanonicalPath()));
    }

    @Test
    void testFindParameterizedReferenceRepository_submodules_firstEntryUsedForNonExact() throws Exception {
        // "cool-project" dir matches the needle's URL basename and is probed first in
        // the prefix-search phase -> ends up first in the ordered suggestion set.
        // "another-fork" is found in the general walk with the same URL basename.
        // With no exact match, findParameterizedReferenceRepository iterates the set
        // and returns the first entry that is an existing git repo -> "cool-project".
        String needleUrl = "https://github.com/example/cool-project.git";
        File base = newFolder(tempFolder, "refrepo-find-nonexact");
        File repoFirst = createSubdirWithRemote(base, "cool-project", "https://github.com/fork-a/cool-project.git");
        createSubdirWithRemote(base, "another-fork", "https://github.com/fork-b/cool-project.git");
        String reference = base.getAbsolutePath() + "/${GIT_SUBMODULES}";

        File result = git.findParameterizedReferenceRepository(reference, needleUrl);

        assertNotNull(result, "Should return a result when non-exact URL-basename matches exist");
        assertThat(
                "First entry from the ordered suggestion set should be returned",
                result.getCanonicalPath(),
                is(repoFirst.getCanonicalPath()));
    }

    @Test
    void testGetSubmodulesUrls_mapFileHit_skipsDirectoryWalk() throws Exception {
        String needleUrl = "ssh://git@example.com/org/mapped-project.git";
        File base = newFolder(tempFolder, "refrepo-map-hit");
        File repoMapped = createSubdirWithRemote(base, "mapped-project.git", needleUrl);
        // A decoy that would be found first by the ordinary directory walk if
        // the mapping file were not consulted, so a wrong hit here would fail
        // the assertion on the returned directory below.
        createSubdirWithRemote(base, "aaa-decoy", needleUrl);
        Files.writeString(
                new File(base, LegacyCompatibleGitAPIImpl.GITCACHE_MAP_FILENAME).toPath(),
                "repo-1717010012\t" + needleUrl + "\tmapped-project.git\n",
                StandardCharsets.UTF_8);

        AbstractMap.SimpleEntry<Boolean, LinkedHashSet<List<String>>> result =
                git.getSubmodulesUrls(base.getAbsolutePath(), needleUrl, true);

        assertTrue(result.getKey(), "Map file exact match: flag should be true");
        LinkedHashSet<List<String>> entries = result.getValue();
        assertEquals(1, entries.size(), "Map-file hit should short-circuit with exactly one entry");
        List<String> hit = entries.iterator().next();
        assertThat(
                "Map-file hit should point at the mapped dir",
                new File(hit.get(0)).getAbsolutePath(),
                is(repoMapped.getAbsolutePath()));
        assertEquals(
                "origin",
                hit.get(3),
                "remoteName should come from verifying the actual dir's remotes, not the map file's first column");
    }

    @Test
    void testGetSubmodulesUrls_mapFileStaleEntry_fallsBackToDirectoryWalk() throws Exception {
        String needleUrl = "ssh://git@example.com/org/still-findable.git";
        File base = newFolder(tempFolder, "refrepo-map-stale");
        File repoReal = createSubdirWithRemote(base, "still-findable.git", needleUrl);
        // Map file points at a subdirectory that does not actually exist (as if
        // the cache had been pruned/renamed after the map was last saved).
        Files.writeString(
                new File(base, LegacyCompatibleGitAPIImpl.GITCACHE_MAP_FILENAME).toPath(),
                "repo-0000000000\t" + needleUrl + "\tgone-missing.git\n",
                StandardCharsets.UTF_8);

        AbstractMap.SimpleEntry<Boolean, LinkedHashSet<List<String>>> result =
                git.getSubmodulesUrls(base.getAbsolutePath(), needleUrl, true);

        assertTrue(result.getKey(), "Directory-walk fallback should still find the real dir");
        LinkedHashSet<List<String>> entries = result.getValue();
        assertThat(
                "Fallback should find the real dir, not the stale map entry",
                new File(entries.iterator().next().get(0)).getAbsolutePath(),
                is(repoReal.getAbsolutePath()));
    }

    @Test
    void testGetSubmodulesUrls_mapFileEntryWithoutMatchingRemote_fallsBackToDirectoryWalk() throws Exception {
        // The mapped directory exists and is a real git repo, but its remote
        // was changed (or the dir was repurposed) since the map was saved, so
        // it no longer actually has the needle URL configured - the map's
        // dirname-existence check alone would wrongly trust this hit.
        String needleUrl = "ssh://git@example.com/org/moved-away.git";
        File base = newFolder(tempFolder, "refrepo-map-wrong-remote");
        createSubdirWithRemote(base, "moved-away.git", "ssh://git@example.com/org/renamed-elsewhere.git");
        File repoReal = createSubdirWithRemote(base, "aaa-actual-hit", needleUrl);
        Files.writeString(
                new File(base, LegacyCompatibleGitAPIImpl.GITCACHE_MAP_FILENAME).toPath(),
                "repo-1234567890\t" + needleUrl + "\tmoved-away.git\n",
                StandardCharsets.UTF_8);

        AbstractMap.SimpleEntry<Boolean, LinkedHashSet<List<String>>> result =
                git.getSubmodulesUrls(base.getAbsolutePath(), needleUrl, true);

        assertTrue(result.getKey(), "Directory-walk fallback should still find the real dir");
        LinkedHashSet<List<String>> entries = result.getValue();
        assertThat(
                "Fallback should find the dir that actually has the needle remote, not the mismatched map entry",
                new File(entries.iterator().next().get(0)).getAbsolutePath(),
                is(repoReal.getAbsolutePath()));
    }

    @Test
    void testGetSubmodulesUrls_noMapFile_behavesAsBefore() throws Exception {
        String needleUrl = "ssh://git@example.com/org/no-map-here.git";
        File base = newFolder(tempFolder, "refrepo-no-map");
        File repoReal = createSubdirWithRemote(base, "no-map-here.git", needleUrl);
        assertFalse(
                new File(base, LegacyCompatibleGitAPIImpl.GITCACHE_MAP_FILENAME).exists(),
                "Precondition: no map file present");

        AbstractMap.SimpleEntry<Boolean, LinkedHashSet<List<String>>> result =
                git.getSubmodulesUrls(base.getAbsolutePath(), needleUrl, true);

        assertTrue(result.getKey(), "Directory walk should still find the exact match without a map file");
        assertThat(
                new File(result.getValue().iterator().next().get(0)).getAbsolutePath(), is(repoReal.getAbsolutePath()));
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
