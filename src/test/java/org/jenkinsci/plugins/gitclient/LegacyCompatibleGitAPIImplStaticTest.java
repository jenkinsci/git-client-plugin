package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.EnvVars;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the static/utility methods of {@link LegacyCompatibleGitAPIImpl}:
 * {@code getObjectsFile()}, {@code isParameterizedReferenceRepository()},
 * and {@code normalizeGitUrl()}.
 */
class LegacyCompatibleGitAPIImplStaticTest {

    @TempDir
    private File tempDir;

    private File repoDir;

    @BeforeEach
    void setUp() throws Exception {
        repoDir = newFolder(tempDir, "repo");
    }

    // -----------------------------------------------------------------------
    // getObjectsFile()
    // -----------------------------------------------------------------------

    @Test
    void getObjectsFile_nullString_returnsNull() {
        assertNull(LegacyCompatibleGitAPIImpl.getObjectsFile((String) null));
    }

    @Test
    void getObjectsFile_emptyString_returnsNull() {
        assertNull(LegacyCompatibleGitAPIImpl.getObjectsFile(""));
    }

    @Test
    void getObjectsFile_nullFile_returnsNull() {
        assertNull(LegacyCompatibleGitAPIImpl.getObjectsFile((File) null));
    }

    @Test
    void getObjectsFile_nonExistentPath_returnsNull() {
        File missing = new File(tempDir, "does-not-exist");
        assertNull(LegacyCompatibleGitAPIImpl.getObjectsFile(missing));
    }

    @Test
    void getObjectsFile_regularDirectory_returnsNull() {
        // A plain directory without any git metadata should return null
        assertNull(LegacyCompatibleGitAPIImpl.getObjectsFile(repoDir));
    }

    @Test
    void getObjectsFile_normalWorkspace_returnsObjectsDir() throws Exception {
        // A standard (non-bare) git repo has .git/objects/
        Git.with(TaskListener.NULL, new EnvVars())
                .in(repoDir)
                .using("git")
                .getClient()
                .init();
        File expected = new File(repoDir, ".git/objects");
        File result = LegacyCompatibleGitAPIImpl.getObjectsFile(repoDir);
        assertNotNull(result, "getObjectsFile() returned null for a normal workspace");
        assertThat(result.getAbsolutePath(), is(expected.getAbsolutePath()));
        assertTrue(result.isDirectory(), "objects dir should be a directory");
    }

    @Test
    void getObjectsFile_bareRepository_returnsObjectsDir() throws Exception {
        // A bare repo has objects/ directly in the repo root
        Git.with(TaskListener.NULL, new EnvVars())
                .in(repoDir)
                .using("git")
                .getClient()
                .init_()
                .workspace(repoDir.getAbsolutePath())
                .bare(true)
                .execute();
        File expected = new File(repoDir, "objects");
        File result = LegacyCompatibleGitAPIImpl.getObjectsFile(repoDir);
        assertNotNull(result, "getObjectsFile() returned null for a bare repo");
        assertThat(result.getAbsolutePath(), is(expected.getAbsolutePath()));
        assertTrue(result.isDirectory(), "objects dir should be a directory");
    }

    @Test
    void getObjectsFile_submoduleGitPointerFile_returnsObjectsDir() throws Exception {
        // Submodule workspaces have a .git FILE (not dir) containing "gitdir: <path>"
        // The real git metadata lives at the path referenced by that file.
        File realGitDir = newFolder(tempDir, "real-gitdir");
        File realObjects = new File(realGitDir, "objects");
        assertTrue(realObjects.mkdir(), "Failed to create fake objects dir");

        // Write a .git pointer file in repoDir
        File gitPointer = new File(repoDir, ".git");
        String relativeGitdirPath = "../real-gitdir";
        Files.writeString(
                gitPointer.toPath(),
                "gitdir: " + relativeGitdirPath + "\n",
                StandardCharsets.UTF_8);

        File result = LegacyCompatibleGitAPIImpl.getObjectsFile(repoDir);
        assertNotNull(result, "getObjectsFile() returned null for submodule pointer workspace");
        assertTrue(result.isDirectory(), "Expected objects dir to be a real directory");
        // Canonical path resolves the ".." in the relative gitdir reference
        assertThat(result.getCanonicalPath(), is(realObjects.getCanonicalPath()));
    }

    // -----------------------------------------------------------------------
    // isParameterizedReferenceRepository()
    // -----------------------------------------------------------------------

    @Test
    void isParameterizedReferenceRepository_null_returnsFalse() {
        assertFalse(LegacyCompatibleGitAPIImpl.isParameterizedReferenceRepository((String) null));
    }

    @Test
    void isParameterizedReferenceRepository_emptyString_returnsFalse() {
        assertFalse(LegacyCompatibleGitAPIImpl.isParameterizedReferenceRepository(""));
    }

    @Test
    void isParameterizedReferenceRepository_plainPath_returnsFalse() {
        assertFalse(LegacyCompatibleGitAPIImpl.isParameterizedReferenceRepository("/var/cache/git/repos"));
    }

    @Test
    void isParameterizedReferenceRepository_sha256Suffix_returnsTrue() {
        assertTrue(LegacyCompatibleGitAPIImpl.isParameterizedReferenceRepository(
                "/var/cache/git/${GIT_URL_SHA256}"));
    }

    @Test
    void isParameterizedReferenceRepository_sha256FallbackSuffix_returnsTrue() {
        assertTrue(LegacyCompatibleGitAPIImpl.isParameterizedReferenceRepository(
                "/var/cache/git/${GIT_URL_SHA256_FALLBACK}"));
    }

    @Test
    void isParameterizedReferenceRepository_basenameSuffix_returnsTrue() {
        assertTrue(LegacyCompatibleGitAPIImpl.isParameterizedReferenceRepository(
                "/var/cache/git/${GIT_URL_BASENAME}"));
    }

    @Test
    void isParameterizedReferenceRepository_basenameFallbackSuffix_returnsTrue() {
        assertTrue(LegacyCompatibleGitAPIImpl.isParameterizedReferenceRepository(
                "/var/cache/git/${GIT_URL_BASENAME_FALLBACK}"));
    }

    @Test
    void isParameterizedReferenceRepository_submodulesSuffix_returnsTrue() {
        assertTrue(LegacyCompatibleGitAPIImpl.isParameterizedReferenceRepository(
                "/var/cache/git/${GIT_SUBMODULES}"));
    }

    @Test
    void isParameterizedReferenceRepository_submodulesFallbackSuffix_returnsTrue() {
        assertTrue(LegacyCompatibleGitAPIImpl.isParameterizedReferenceRepository(
                "/var/cache/git/${GIT_SUBMODULES_FALLBACK}"));
    }

    @Test
    void isParameterizedReferenceRepository_tokenInMiddle_returnsFalse() {
        // Token must be at the END of the path; mid-path token is not recognized
        assertFalse(LegacyCompatibleGitAPIImpl.isParameterizedReferenceRepository(
                "/var/cache/git/${GIT_URL_SHA256}/extra"));
    }

    @Test
    void isParameterizedReferenceRepository_fileOverload_delegatesToStringVersion() {
        // The File overload delegates to the String overload via file.getPath().
        // On Windows, getPath() uses backslashes while the checks use forward slashes,
        // so we only assert the behaviors that are platform-independent:
        // null File -> false, and non-parameterized File -> false.
        assertFalse(LegacyCompatibleGitAPIImpl.isParameterizedReferenceRepository((File) null));
        assertFalse(LegacyCompatibleGitAPIImpl.isParameterizedReferenceRepository(new File("/var/cache/git/repos")));
    }

    // -----------------------------------------------------------------------
    // normalizeGitUrl()
    // -----------------------------------------------------------------------

    @Test
    void normalizeGitUrl_trailingSlashes_areStripped() {
        String result = LegacyCompatibleGitAPIImpl.normalizeGitUrl("https://github.com/example/repo///", false);
        assertThat(result, not(endsWith("/")));
    }

    @Test
    void normalizeGitUrl_dotGitSuffix_isStripped() {
        String result = LegacyCompatibleGitAPIImpl.normalizeGitUrl("https://github.com/example/repo.git", false);
        assertThat(result, not(endsWith(".git")));
    }

    @Test
    void normalizeGitUrl_mixedCaseDotGit_lowercasedButNotStripped() {
        // The regex strips only exact lowercase ".git"; ".Git" is lowercased to ".git" but stays
        String result = LegacyCompatibleGitAPIImpl.normalizeGitUrl("https://github.com/example/repo.Git", false);
        assertThat(result, endsWith(".git"));
    }

    @Test
    void normalizeGitUrl_httpsUrl_isLowercased() {
        String result =
                LegacyCompatibleGitAPIImpl.normalizeGitUrl("HTTPS://GitHub.com/Example/Repo.git", false);
        assertThat(result, startsWith("https://"));
        assertThat(result, containsString("github.com"));
    }

    @Test
    void normalizeGitUrl_absoluteLocalPath_getsFileScheme() {
        String result = LegacyCompatibleGitAPIImpl.normalizeGitUrl("/var/cache/git/repo", false);
        assertThat(result, startsWith("file://"));
    }

    @Test
    void normalizeGitUrl_absoluteLocalPath_dotGitStripped() {
        String withGit = LegacyCompatibleGitAPIImpl.normalizeGitUrl("/var/cache/git/repo.git", false);
        String withoutGit = LegacyCompatibleGitAPIImpl.normalizeGitUrl("/var/cache/git/repo", false);
        assertThat(withGit, is(withoutGit));
    }

    @Test
    void normalizeGitUrl_relativeLocalPath_getsFileScheme() {
        // A path starting with "./" is treated as a local relative path
        String result = LegacyCompatibleGitAPIImpl.normalizeGitUrl("./some/local/repo", false);
        assertThat(result, startsWith("file://"));
    }

    @Test
    void normalizeGitUrl_bareHostname_getsSshScheme() {
        // A string without schema, not starting with / or ., is treated as an ssh host
        String result = LegacyCompatibleGitAPIImpl.normalizeGitUrl("git@github.com:example/repo.git", false);
        assertThat(result, startsWith("ssh://"));
    }

    @Test
    void normalizeGitUrl_sameUrlDifferentSuffixes_normalizeToSame() {
        String base = LegacyCompatibleGitAPIImpl.normalizeGitUrl("https://github.com/example/repo", false);
        String withGit = LegacyCompatibleGitAPIImpl.normalizeGitUrl("https://github.com/example/repo.git", false);
        String withSlash = LegacyCompatibleGitAPIImpl.normalizeGitUrl("https://github.com/example/repo/", false);
        String withBoth = LegacyCompatibleGitAPIImpl.normalizeGitUrl("https://github.com/example/repo.git/", false);
        assertThat(withGit, is(base));
        assertThat(withSlash, is(base));
        assertThat(withBoth, is(base));
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + result);
        }
        return result;
    }
}
