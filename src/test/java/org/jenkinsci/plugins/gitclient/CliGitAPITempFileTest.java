package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.EnvVars;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.Issue;

/**
 * Test that createTempFile is adapting its directory name choices to match
 * platform limitations of command line git.
 *
 * @author Mark Waite
 */
@ParameterizedClass(name = "{0}")
@MethodSource("workspaceDirNames")
class CliGitAPITempFileTest {

    @Parameter(0)
    private String workspaceDirName;

    @Parameter(1)
    private boolean mustUseSystemTempDir;

    @Parameter(2)
    private String filenamePrefix;

    @Parameter(3)
    private String filenameSuffix;

    private static final String INVALID_CHARACTERS = "%" + (isWindows() ? " ()" : "`");

    /* Should temp folder be in same parent dir as workspace? */
    @TempDir
    private File workspaceParentFolder;

    private File workspace;

    static List<Arguments> workspaceDirNames() {
        Random random = new Random();
        List<Arguments> workspaceNames = new ArrayList<>();
        for (int charIndex = 0; charIndex < INVALID_CHARACTERS.length(); charIndex++) {
            Arguments oneWorkspace = Arguments.of(
                    "use " + INVALID_CHARACTERS.charAt(charIndex) + " dir",
                    true,
                    random.nextBoolean() ? "pre" : null,
                    random.nextBoolean() ? ".suff" : null);
            workspaceNames.add(oneWorkspace);
        }
        String[] goodNames = {"$5.00", "b&d", "f[x]", "mark@home"};
        for (String goodName : goodNames) {
            Arguments oneWorkspace = Arguments.of(
                    goodName, false, random.nextBoolean() ? "pre" : null, random.nextBoolean() ? ".suff" : null);
            workspaceNames.add(oneWorkspace);
        }
        String[] badNames = {"50%off"};
        for (String badName : badNames) {
            Arguments oneWorkspace = Arguments.of(
                    badName, true, random.nextBoolean() ? "pre" : null, random.nextBoolean() ? ".suff" : null);
            workspaceNames.add(oneWorkspace);
        }
        String[] platformNames = {"(abc)", "abs(x)", "shame's own"};
        for (String platformName : platformNames) {
            Arguments oneWorkspace = Arguments.of(
                    platformName,
                    isWindows(),
                    random.nextBoolean() ? "pre" : null,
                    random.nextBoolean() ? ".suff" : null);
            workspaceNames.add(oneWorkspace);
        }
        return workspaceNames;
    }

    @BeforeEach
    void createWorkspace() throws Exception {
        workspace = newFolder(workspaceParentFolder, workspaceDirName);
        assertTrue(workspace.isDirectory(), "'" + workspace.getAbsolutePath() + "' not a directory");
        assertThat(workspace.getAbsolutePath(), containsString(workspaceDirName));
    }

    /**
     * Check that the file path returned by CliGitAPIImpl.createTempFile
     * contains no characters that are invalid for CLI git authentication.
     *
     */
    // and ...
    @Test
    @Issue({"JENKINS-44301", "JENKINS-43931"})
    void testTempFilePathCharactersValid() throws Exception {
        CliGitAPIImplExtension cliGit = new CliGitAPIImplExtension("git", workspace, null, null);
        for (int charIndex = 0; charIndex < INVALID_CHARACTERS.length(); charIndex++) {
            Path tempFile = cliGit.createTempFile(filenamePrefix, filenameSuffix);
            assertThat(
                    tempFile.toAbsolutePath().toString(),
                    not(containsString("" + INVALID_CHARACTERS.charAt(charIndex))));
            if (!mustUseSystemTempDir) {
                Path tempParent = tempFile.getParent();
                Path tempGrandparent = tempParent.getParent();
                Path workspaceParent = workspace.getParentFile().toPath();
                assertThat(
                        "Parent dir not shared by workspace '" + workspace.getAbsolutePath() + "' and tempdir",
                        workspaceParent,
                        is(tempGrandparent));
            }
        }
    }

    /**
     * inline ${@link hudson.Functions#isWindows()} to prevent a transient
     * remote classloader issue
     */
    private static boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }

    private static class CliGitAPIImplExtension extends CliGitAPIImpl {

        private CliGitAPIImplExtension(String gitExe, File workspace, TaskListener listener, EnvVars environment) {
            super(gitExe, workspace, listener, environment);
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
