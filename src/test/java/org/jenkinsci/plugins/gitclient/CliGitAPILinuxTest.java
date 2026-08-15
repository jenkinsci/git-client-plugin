package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliGitAPILinuxTest {

    @TempDir
    private static File tempDir;

    @Test
    void test_invalid_workspace_dir_name_rce() throws Exception {
        String rceInjection = "$(date>>/tmp/rce-proof)";
        File workspaceDir = new File(tempDir, rceInjection);
        workspaceDir.mkdirs();
        CliGitAPIImpl cliGit = new CliGitAPIImpl("git", workspaceDir, null, null);
        Path file = cliGit.createTempFile("something", ".suffix");
        assertThat(file.toAbsolutePath().toString(), not(containsString(rceInjection)));
    }

    @Test
    void test_valid_workspace_dir_name_single_quote() throws Exception {
        String singleQuote = "Bob's-job";
        File workspaceDir = new File(tempDir, singleQuote);
        workspaceDir.mkdirs();
        CliGitAPIImpl cliGit = new CliGitAPIImpl("git", workspaceDir, null, null);
        Path file = cliGit.createTempFile("something", ".suffix");
        assertThat(file.toAbsolutePath().toString(), containsString(singleQuote));
    }

    @Test
    void test_valid_workspace_dir_name_parentheses() throws Exception {
        String parentheses = "My OWASP (special) case";
        File workspaceDir = new File(tempDir, parentheses);
        workspaceDir.mkdirs();
        CliGitAPIImpl cliGit = new CliGitAPIImpl("git", workspaceDir, null, null);
        Path file = cliGit.createTempFile("something", ".suffix");
        if (isWindows()) {
            assertThat(file.toAbsolutePath().toString(), not(containsString(parentheses)));
        } else {
            assertThat(file.toAbsolutePath().toString(), containsString(parentheses));
        }
    }

    private static boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
