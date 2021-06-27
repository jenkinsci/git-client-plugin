package org.jenkinsci.plugins.gitclient;


import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * CliGitAPIImplTest tests have been ported to GitClientTest
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CliGitAPIImplTest extends GitAPITestCase {

    @Override
    protected GitClient setupGitAPI(File ws) throws Exception {
        setCliGitDefaults();
        return Git.with(listener, env).in(ws).using("git").getClient();
    }

    @Override
    protected boolean hasWorkingGetRemoteSymbolicReferences() {
        return ((CliGitAPIImpl)(w.git)).isAtLeastVersion(2,8,0,0);
    }

    private static boolean cliGitDefaultsSet = false;

    private void setCliGitDefaults() throws Exception {
        if (!cliGitDefaultsSet) {
            CliGitCommand gitCmd = new CliGitCommand(null);
        }
        cliGitDefaultsSet = true;
    }

    /**
     * Override to run the test and assert its state.
     *
     * @throws Throwable if any exception is thrown
     */
    protected void runTest() throws Throwable {
        Method m = getClass().getMethod(getName());

        if (m.getAnnotation(NotImplementedInCliGit.class) != null) {
            setTimeoutVisibleInCurrentTest(false); /* No timeout if not implemented in CliGitAPIImpl */
            return; // skip this test case
        }
        try {
            m.invoke(this);
        } catch (InvocationTargetException e) {
            e.fillInStackTrace();
            throw e.getTargetException();
        } catch (IllegalAccessException e) {
            e.fillInStackTrace();
            throw e;
        }
    }

    public class VersionTest {

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
                assertTrue("Failed " + msg, git.isAtLeastVersion(
                        version.major,
                        version.minor,
                        version.rev,
                        version.bugfix));
            } else {
                assertFalse("Passed " + msg, git.isAtLeastVersion(
                        version.major,
                        version.minor,
                        version.rev,
                        version.bugfix));
            }
        }
    }

    @Override
    protected String getRemoteBranchPrefix() {
        return "remotes/";
    }
}
