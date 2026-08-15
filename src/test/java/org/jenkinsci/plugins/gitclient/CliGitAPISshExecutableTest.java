package org.jenkinsci.plugins.gitclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.Issue;

/**
 * Tests the order in which {@link CliGitAPIImpl#getSSHExecutable()} searches for ssh.
 * The ssh of the git installation must be preferred over an ssh found on the system PATH.
 *
 * @author <a href="mailto:akash.manna.mymail@gmail.com">Akash Manna</a>
 */
class CliGitAPISshExecutableTest {

    @TempDir
    private File tempDir;

    private CliGitAPIImpl gitClientWithEnv(String gitExe, Map<String, String> environmentVariables) {
        return new CliGitAPIImpl(gitExe, tempDir, null, null) {
            @Override
            String getEnvVar(String envVar) {
                return environmentVariables.get(envVar);
            }
        };
    }

    /* Paths are joined with a backslash, so on Unix the file name contains backslashes */
    private File createExecutable(File executable) throws IOException {
        File parent = executable.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        assertTrue(executable.createNewFile(), "Could not create " + executable);
        return executable;
    }

    private File pathDirectoryWithSsh() throws IOException {
        File pathDir = new File(tempDir, "path-dir");
        pathDir.mkdirs();
        createExecutable(new File(pathDir, "ssh.exe"));
        return pathDir;
    }

    @Test
    @Issue("JENKINS-1715")
    void sshOfGitInstallationPreferredOverSshOnPath() throws Exception {
        File programFiles = new File(tempDir, "Program Files");
        File gitSsh = createExecutable(new File(programFiles + "\\Git\\bin\\ssh.exe"));

        Map<String, String> env = new HashMap<>();
        env.put("ProgramFiles", programFiles.getAbsolutePath());
        env.put("PATH", pathDirectoryWithSsh().getAbsolutePath());

        assertEquals(gitSsh, gitClientWithEnv("git", env).getSSHExecutable());
    }

    @Test
    @Issue("JENKINS-1715")
    void sshOfGitInstallationInUsrBinPreferredOverSshOnPath() throws Exception {
        File programFiles = new File(tempDir, "Program Files");
        File gitSsh = createExecutable(new File(programFiles + "\\Git\\usr\\bin\\ssh.exe"));

        Map<String, String> env = new HashMap<>();
        env.put("ProgramFiles", programFiles.getAbsolutePath());
        env.put("PATH", pathDirectoryWithSsh().getAbsolutePath());

        assertEquals(gitSsh, gitClientWithEnv("git", env).getSSHExecutable());
    }

    @Test
    @Issue("JENKINS-1715")
    void sshBesideGitExecutablePreferredOverSshOnPath() throws Exception {
        File gitExe = new File(tempDir, "MinGit\\cmd\\git.exe");
        File gitSsh = createExecutable(new File(gitExe.getParent() + "\\ssh.exe"));

        Map<String, String> env = new HashMap<>();
        env.put("PATH", pathDirectoryWithSsh().getAbsolutePath());

        assertEquals(gitSsh, gitClientWithEnv(gitExe.getAbsolutePath(), env).getSSHExecutable());
    }

    @Test
    @Issue("JENKINS-72450")
    void sshOnPathUsedWhenGitInstallationHasNoSsh() throws Exception {
        File pathDir = pathDirectoryWithSsh();

        Map<String, String> env = new HashMap<>();
        env.put("PATH", pathDir.getAbsolutePath());

        assertEquals(
                new File(pathDir, "ssh.exe"),
                gitClientWithEnv(new File(tempDir, "no-ssh-here\\git.exe").getAbsolutePath(), env)
                        .getSSHExecutable());
    }

    @Test
    @Issue("JENKINS-72450")
    void gitSshEnvironmentVariablePreferredOverSshOnPath() throws Exception {
        File gitSshVariable = createExecutable(new File(tempDir, "custom-ssh.exe"));

        Map<String, String> env = new HashMap<>();
        env.put("GIT_SSH", gitSshVariable.getAbsolutePath());
        env.put("PATH", pathDirectoryWithSsh().getAbsolutePath());

        assertEquals(gitSshVariable, gitClientWithEnv("git", env).getSSHExecutable());
    }

    @Test
    void sshNotFoundReportsUnsupportedGitInstallation() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", new File(tempDir, "empty-dir").getAbsolutePath());

        CliGitAPIImpl git = gitClientWithEnv(new File(tempDir, "no-ssh-here\\git.exe").getAbsolutePath(), env);
        RuntimeException e = assertThrows(RuntimeException.class, git::getSSHExecutable);
        assertTrue(e.getMessage().startsWith("ssh executable not found"), e.getMessage());
    }
}
