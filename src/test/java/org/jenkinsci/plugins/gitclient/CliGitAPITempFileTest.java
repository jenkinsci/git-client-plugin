package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;

/**
 * Test that createTempFile is adapting its directory name choices to match
 * platform limitations of command line git.
 *
 * @author Mark Waite
 */
@RunWith(Parameterized.class)
public class CliGitAPITempFileTest {

    private final String workspaceDirName;
    private final boolean mustUseSystemTempDir;
    /* Should temp folder be in same parent dir as workspace? */

    private static final String INVALID_CHARACTERS = "%" + (isWindows() ? " ()" : "`");

    @Rule
    public TemporaryFolder workspaceParentFolder = new TemporaryFolder();

    public CliGitAPITempFileTest(String workspaceDirName, boolean mustUseSystemTempDir) {
        this.workspaceDirName = workspaceDirName;
        this.mustUseSystemTempDir = mustUseSystemTempDir;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection workspaceDirNames() {
        List<Object[]> workspaceNames = new ArrayList<>();
        for (int charIndex = 0; charIndex < INVALID_CHARACTERS.length(); charIndex++) {
            Object[] oneWorkspace = {"use " + INVALID_CHARACTERS.charAt(charIndex) + " dir", true};
            workspaceNames.add(oneWorkspace);
        }
        String[] goodNames = {
            "$5.00",
            "b&d",
            "f[x]",
            "mark@home"
        };
        for (String goodName : goodNames) {
            Object[] oneWorkspace = {goodName, false};
            workspaceNames.add(oneWorkspace);
        }
        String[] badNames = {
            "50%off"
        };
        for (String badName : badNames) {
            Object[] oneWorkspace = {badName, true};
            workspaceNames.add(oneWorkspace);
        }
        String[] platformNames = {
            "(abc)",
            "abs(x)",
            "shame's own"
        };
        for (String platformName : platformNames) {
            Object[] oneWorkspace = {platformName, isWindows()};
            workspaceNames.add(oneWorkspace);
        }
        return workspaceNames;
    }

    private File workspace;

    @Before
    public void createWorkspace() throws Exception {
        workspace = workspaceParentFolder.newFolder(workspaceDirName);
        assertTrue("'" + workspace.getAbsolutePath() + "' not a directory", workspace.isDirectory());
        assertThat(workspace.getAbsolutePath(), containsString(workspaceDirName));
    }

    /**
     * Check that the file path returned by CliGitAPIImpl.createTempFile
     * contains no characters that are invalid for CLI git authentication.
     *
     */
    @Test
    @Issue("JENKINS-44301") // and 43931 and ...
    public void testTempFilePathCharactersValid() throws IOException {
        CliGitAPIImplExtension cliGit = new CliGitAPIImplExtension("git", workspace, null, null);
        for (int charIndex = 0; charIndex < INVALID_CHARACTERS.length(); charIndex++) {
            File tempFile = cliGit.createTempFile("pre", ".suff");
            assertThat(tempFile.getAbsolutePath(), not(containsString("" + INVALID_CHARACTERS.charAt(charIndex))));
            if (!mustUseSystemTempDir) {
                File tempParent = tempFile.getParentFile();
                File tempGrandparent = tempParent.getParentFile();
                File workspaceParent = workspace.getParentFile();
                assertThat("Parent dir not shared by workspace '" + workspace.getAbsolutePath() + "' and tempdir", workspaceParent, is(tempGrandparent));
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

    private class CliGitAPIImplExtension extends CliGitAPIImpl {

        public CliGitAPIImplExtension(String gitExe, File workspace, TaskListener listener, EnvVars environment) {
            super(gitExe, workspace, listener, environment);
        }
    }
}
