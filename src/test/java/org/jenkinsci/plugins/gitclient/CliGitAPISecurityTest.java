package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.EnvVars;
import hudson.model.TaskListener;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.Issue;

/**
 * Security test that proves the environment variable approach prevents
 * OS command injection in SSH wrapper script generation.
 *
 * This test validates SECURITY-3614 by actually generating wrapper scripts
 * with malicious workspace paths and verifying that command injection does
 * NOT occur when using environment variables.
 *
 * @author Mark Waite
 */
class CliGitAPISecurityTest {

    @TempDir
    private File tempDir;

    private File workspace;
    private List<File> evidenceFiles;

    private static boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }

    @BeforeEach
    void setUp() throws Exception {
        evidenceFiles = new ArrayList<>();
    }

    @AfterEach
    void cleanUp() {
        // Clean up any evidence files that may have been created
        for (File evidence : evidenceFiles) {
            if (evidence.exists()) {
                evidence.delete();
            }
        }
    }

    static List<Arguments> maliciousWorkspaceNames() {
        List<Arguments> names = new ArrayList<>();

        if (!isWindows()) {
            // Unix command substitution attacks
            names.add(Arguments.of("$(touch /tmp/pwned-unix-1)", "/tmp/pwned-unix-1"));
            names.add(Arguments.of("`touch /tmp/pwned-unix-2`", "/tmp/pwned-unix-2"));

            // Unix command chaining attacks
            names.add(Arguments.of("foo;touch /tmp/pwned-unix-3", "/tmp/pwned-unix-3"));
            names.add(Arguments.of("foo&&touch /tmp/pwned-unix-4", "/tmp/pwned-unix-4"));

            // Quote escape attacks
            names.add(Arguments.of("foo\";touch /tmp/pwned-unix-5;echo \"bar", "/tmp/pwned-unix-5"));
        } else {
            // Windows command chaining attacks
            // Note: Windows paths cannot contain > < | characters, so we use commands
            // without file redirection. These test & and && operators which ARE valid
            // in Windows paths but dangerous if interpreted in batch scripts.
            names.add(Arguments.of("foo&echo.PWNED", "C:\\temp\\pwned-win-1.txt"));
            names.add(Arguments.of("foo&&echo.PWNED", "C:\\temp\\pwned-win-2.txt"));

            // Test percent expansion (% is valid in Windows paths)
            names.add(Arguments.of("test%USERNAME%dir", "C:\\temp\\pwned-win-3.txt"));
        }

        return names;
    }

    @ParameterizedTest
    @MethodSource("maliciousWorkspaceNames")
    @Issue("SECURITY-3614")
    void testEnvironmentVariablesPreventsInjection(String maliciousWorkspaceName, String evidencePath)
            throws Exception {
        // Create workspace with malicious name
        workspace = new File(tempDir, maliciousWorkspaceName);

        // On Windows, some characters like > < | are illegal in paths and will cause
        // InvalidPathException before we can even test. Use JUnit assumptions to properly
        // skip these tests rather than silently returning.
        boolean workspaceCreated = false;
        try {
            workspaceCreated = workspace.mkdirs();
            assumeTrue(workspaceCreated, "Workspace creation failed - path may contain platform-illegal characters");
        } catch (Exception e) {
            // Use assumeTrue to properly skip test with reported reason
            assumeTrue(
                    false,
                    "Cannot create workspace with name '" + maliciousWorkspaceName + "' on this platform: "
                            + e.getMessage());
        }

        File evidenceFile = new File(evidencePath);
        evidenceFiles.add(evidenceFile);

        // Ensure evidence file doesn't exist from a previous test
        if (evidenceFile.exists()) {
            evidenceFile.delete();
        }

        // Create a mock SSH key file
        Path keyFile;
        try {
            keyFile = createMockSSHKey(workspace);
        } catch (java.nio.file.InvalidPathException e) {
            // Use assumeTrue to properly skip test with reported reason
            assumeTrue(
                    false,
                    "Cannot create key file path with workspace name '" + maliciousWorkspaceName
                            + "' on this platform: " + e.getMessage());
            return; // Keep return for compiler, but assumeTrue will skip first
        }

        // Create a mock known_hosts file
        Path knownHosts = Files.createTempFile("known_hosts", "");

        try {
            // Create the Git API instance using the proper factory method
            GitClient gitClient = Git.with(TaskListener.NULL, new EnvVars())
                    .in(workspace)
                    .using("git")
                    .getClient();

            // Cast to CliGitAPIImpl to access package-protected methods
            CliGitAPIImpl git = (CliGitAPIImpl) gitClient;

            // Generate the SSH wrapper script using the actual production code
            Path sshWrapper;
            if (isWindows()) {
                sshWrapper = git.createWindowsGitSSH(keyFile, "testuser", knownHosts);
            } else {
                sshWrapper = git.createUnixGitSSH(keyFile, "testuser", knownHosts);
            }

            // Read the generated wrapper script
            String wrapperContent = Files.readString(sshWrapper, StandardCharsets.UTF_8);

            // Verify the wrapper uses environment variables, not string interpolation
            if (isWindows()) {
                assertThat(
                        "Wrapper should reference !JENKINS_GIT_SSH_KEYFILE!",
                        wrapperContent.contains("!JENKINS_GIT_SSH_KEYFILE!"),
                        is(true));
                assertFalse(
                        wrapperContent.contains(maliciousWorkspaceName),
                        "Wrapper should NOT contain malicious workspace name directly");
            } else {
                assertThat(
                        "Wrapper should reference $JENKINS_GIT_SSH_KEYFILE",
                        wrapperContent.contains("$JENKINS_GIT_SSH_KEYFILE"),
                        is(true));
                assertFalse(
                        wrapperContent.contains(maliciousWorkspaceName),
                        "Wrapper should NOT contain malicious workspace name directly");
            }

            // Execute the wrapper script with environment variables set
            // (This simulates what git does when GIT_SSH is set)
            executeWrapper(sshWrapper, keyFile);

            // Verify NO command injection occurred
            assertFalse(
                    evidenceFile.exists(), "Evidence file should NOT exist - command injection should be prevented");

            // Verify the key file path is still accessible (functionality preserved)
            assertTrue(keyFile.toFile().exists(), "Key file should still exist and be accessible");

        } finally {
            Files.deleteIfExists(knownHosts);
        }
    }

    /**
     * Test that Windows wrapper uses delayed expansion for safe variable handling
     */
    @Test
    @Issue("SECURITY-3614")
    void testWindowsDelayedExpansionEnabled() throws Exception {
        if (!isWindows()) {
            return; // Skip on Unix
        }

        workspace = new File(tempDir, "test!var!expansion");
        workspace.mkdirs();

        Path keyFile = createMockSSHKey(workspace);
        Path knownHosts = Files.createTempFile("known_hosts", "");

        try {
            GitClient gitClient = Git.with(TaskListener.NULL, new EnvVars())
                    .in(workspace)
                    .using("git")
                    .getClient();
            CliGitAPIImpl git = (CliGitAPIImpl) gitClient;
            Path sshWrapper = git.createWindowsGitSSH(keyFile, "testuser", knownHosts);

            String wrapperContent = Files.readString(sshWrapper, StandardCharsets.UTF_8);

            // Verify delayed expansion is enabled and uses !var! syntax
            assertThat(
                    "Wrapper should enable delayed expansion",
                    wrapperContent.contains("setlocal enabledelayedexpansion"),
                    is(true));
            assertThat(
                    "Wrapper should use !var! syntax for safe expansion",
                    wrapperContent.contains("!JENKINS_GIT_SSH_KEYFILE!"),
                    is(true));

        } finally {
            Files.deleteIfExists(knownHosts);
        }
    }

    private Path createMockSSHKey(File workspace) throws IOException {
        File tmpDir = new File(workspace.getAbsolutePath() + "@tmp");
        tmpDir.mkdirs();

        Path keyFile = new File(tmpDir, "test-key.pem").toPath();
        try (BufferedWriter w = Files.newBufferedWriter(keyFile, StandardCharsets.UTF_8)) {
            w.write("-----BEGIN RSA PRIVATE KEY-----\n");
            w.write("MIIEpAIBAAKCAQEA...(mock key)...\n");
            w.write("-----END RSA PRIVATE KEY-----\n");
        }
        return keyFile;
    }

    private void executeWrapper(Path wrapper, Path keyFile) throws Exception {
        // Set up environment variables (simulating what the production code does)
        ProcessBuilder pb = new ProcessBuilder();

        if (isWindows()) {
            pb.command("cmd.exe", "/c", wrapper.toAbsolutePath().toString());
        } else {
            pb.command("/bin/sh", wrapper.toAbsolutePath().toString());
        }

        // Set the environment variable that the wrapper will reference
        pb.environment().put("JENKINS_GIT_SSH_KEYFILE", keyFile.toAbsolutePath().toString());
        pb.environment().put("JENKINS_GIT_SSH_USERNAME", "testuser");

        // Redirect output to avoid cluttering test output
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

        try {
            Process process = pb.start();

            // Wait for completion with timeout
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new Exception("Wrapper execution timed out");
            }

            // We expect the wrapper to fail (no real SSH server), but that's OK
            // We're just checking that no command injection occurred

        } catch (IOException e) {
            // Expected - the wrapper will fail because there's no real ssh binary
            // or the ssh binary will fail because there's no real server
            // That's fine - we're just checking for injection
        }
    }
}
