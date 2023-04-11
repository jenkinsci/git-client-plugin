package org.jenkinsci.plugins.gitclient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

/**
 * CliGitAPIImplTest tests have been ported to GitClientTest
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CliGitAPIImplTest extends GitAPITestUpdateCliGit {

    @Override
    protected GitClient setupGitAPI(File ws) throws Exception {
        GitClient client = Git.with(listener, env).in(ws).using("git").getClient();
        return client;
    }

    @Override
    protected boolean hasWorkingGetRemoteSymbolicReferences() {
        return ((CliGitAPIImpl) (w.git)).isAtLeastVersion(2, 8, 0, 0);
    }

    public static class VersionTest {

        private boolean expectedIsAtLeastVersion;
        private int major;
        private int minor;
        private int rev;
        private int bugfix;

        public VersionTest(boolean assertTrueOrFalse, int major, int minor, int rev, int bugfix) {
            this.expectedIsAtLeastVersion = assertTrueOrFalse;
            this.major = major;
            this.minor = minor;
            this.rev = rev;
            this.bugfix = bugfix;
        }
    }

    public void assertVersionOutput(String versionOutput, VersionTest[] versions) {
        setTimeoutVisibleInCurrentTest(false); /* No timeout for git --version command */
        CliGitAPIImpl git = new CliGitAPIImpl("git", new File("."), listener, env);
        git.computeGitVersion(versionOutput);
        for (VersionTest version : versions) {
            String msg = versionOutput + " for " + version.major + version.minor + version.rev + version.bugfix;
            if (version.expectedIsAtLeastVersion) {
                assertTrue(
                        "Failed " + msg,
                        git.isAtLeastVersion(version.major, version.minor, version.rev, version.bugfix));
                assertTrue(
                        "Failed " + msg,
                        git.isCliGitVerAtLeast(version.major, version.minor, version.rev, version.bugfix));
            } else {
                assertFalse(
                        "Passed " + msg,
                        git.isAtLeastVersion(version.major, version.minor, version.rev, version.bugfix));
                assertFalse(
                        "Passed " + msg,
                        git.isCliGitVerAtLeast(version.major, version.minor, version.rev, version.bugfix));
            }
        }
    }

    @Override
    protected String getRemoteBranchPrefix() {
        return "remotes/";
    }
}
