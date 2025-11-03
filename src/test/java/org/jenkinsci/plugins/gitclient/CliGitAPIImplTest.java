package org.jenkinsci.plugins.gitclient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

/**
 * CliGitAPIImplTest tests have been ported to GitClientTest
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CliGitAPIImplTest extends GitAPITestUpdateCliGit {

    @Override
    protected GitClient setupGitAPI(File ws) throws Exception {
        return Git.with(listener, env).in(ws).using("git").getClient();
    }

    public record VersionTest(boolean expectedIsAtLeastVersion, int major, int minor, int rev, int bugfix) {}

    public void assertVersionOutput(String versionOutput, VersionTest[] versions) {
        CliGitAPIImpl git = new CliGitAPIImpl("git", new File("."), listener, env);
        git.computeGitVersion(versionOutput);
        for (VersionTest version : versions) {
            String msg = versionOutput + " for " + version.major + version.minor + version.rev + version.bugfix;
            if (version.expectedIsAtLeastVersion) {
                assertTrue(
                        git.isAtLeastVersion(version.major, version.minor, version.rev, version.bugfix),
                        "Failed " + msg);
                assertTrue(
                        git.isCliGitVerAtLeast(version.major, version.minor, version.rev, version.bugfix),
                        "Failed " + msg);
            } else {
                assertFalse(
                        git.isAtLeastVersion(version.major, version.minor, version.rev, version.bugfix),
                        "Passed " + msg);
                assertFalse(
                        git.isCliGitVerAtLeast(version.major, version.minor, version.rev, version.bugfix),
                        "Passed " + msg);
            }
        }
    }

    @Override
    protected String getRemoteBranchPrefix() {
        return "remotes/";
    }
}
