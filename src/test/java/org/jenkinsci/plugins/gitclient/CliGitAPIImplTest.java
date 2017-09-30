package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.Branch;
import org.apache.commons.lang.SystemUtils;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

/**
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
            gitCmd.setDefaults();
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

    class VersionTest {

        public boolean expectedIsAtLeastVersion;
        public int major;
        public int minor;
        public int rev;
        public int bugfix;

        public VersionTest(boolean assertTrueOrFalse, int major, int minor, int rev, int bugfix) {
            this.expectedIsAtLeastVersion = assertTrueOrFalse;
            this.major = major;
            this.minor = minor;
            this.rev = rev;
            this.bugfix = bugfix;
        }
    }

    private void doTest(String versionOutput, VersionTest[] versions) {
        setTimeoutVisibleInCurrentTest(false); /* No timeout for git --version command */
        CliGitAPIImpl git = new CliGitAPIImpl("git", new File("."), listener, env);
        git.computeGitVersion(versionOutput);
        for (int i = 0; i < versions.length; ++i) {
            String msg = versionOutput + " for " + versions[i].major + versions[i].minor + versions[i].rev + versions[i].bugfix;
            if (versions[i].expectedIsAtLeastVersion) {
                assertTrue("Failed " + msg, git.isAtLeastVersion(
                        versions[i].major,
                        versions[i].minor,
                        versions[i].rev,
                        versions[i].bugfix));
            } else {
                assertFalse("Passed " + msg, git.isAtLeastVersion(
                        versions[i].major,
                        versions[i].minor,
                        versions[i].rev,
                        versions[i].bugfix));
            }
        }
    }

    public void test_git_version_debian_wheezy() {
        VersionTest[] versions = {
            new VersionTest(true,  1, 7, 10, 4),
            new VersionTest(true,  1, 7, 10, 3),
            new VersionTest(false, 1, 7, 10, 5)
        };
        doTest("git version 1.7.10.4", versions);
    }

    public void test_git_version_debian_testing() {
        VersionTest[] versions = {
            new VersionTest(true,  2, 0, 1, 0),
            new VersionTest(true,  2, 0, 0, 0),
            new VersionTest(false, 2, 0, 2, 0),
            new VersionTest(false, 2, 1, 0, 0)
        };
        doTest("git version 2.0.1", versions);
    }

    public void test_git_version_debian_testing_old() {
        VersionTest[] versions = {
            new VersionTest(true,  2, 0,  0,  0),
            new VersionTest(true,  1, 9, 99, 99),
            new VersionTest(false, 2, 0,  1,  0)
        };
        doTest("git version 2.0.0.rc0", versions);
        doTest("git version 2.0.0.rc2", versions);
        doTest("git version 2.0.0", versions);
        doTest("git version 2.0", versions); // mythical version
        doTest("git version 2", versions);   // mythical version
    }

    public void test_git_version_debian_testing_older() {
        VersionTest[] versions = {
            new VersionTest(true,  1, 9,  0,  0),
            new VersionTest(true,  1, 8, 99, 99),
            new VersionTest(false, 1, 9,  1,  0)
        };
        doTest("git version 1.9.0", versions);
    }

    public void test_git_version_windows_1800() {
        VersionTest[] versions = {
            new VersionTest(true,  1, 8,  0, 0),
            new VersionTest(true,  1, 7, 99, 0),
            new VersionTest(false, 1, 8,  1, 0)
        };
        doTest("git version 1.8.0.msysgit.0", versions);
    }

    public void test_git_version_windows_1840() {
        VersionTest[] versions = {
            new VersionTest(true,  1, 8, 4,  0),
            new VersionTest(true,  1, 8, 3, 99),
            new VersionTest(false, 1, 8, 4,  1)
        };
        doTest("git version 1.8.4.msysgit.0", versions);
    }

    public void test_git_version_windows_1852() {
        VersionTest[] versions = {
            new VersionTest(true,  1, 8, 5, 2),
            new VersionTest(true,  1, 8, 5, 1),
            new VersionTest(false, 1, 8, 5, 3)
        };
        doTest("git version 1.8.5.2.msysgit.0", versions);
    }

    public void test_git_version_windows_1900() {
        VersionTest[] versions = {
            new VersionTest(true,  1, 9,  0, 0),
            new VersionTest(true,  1, 8, 99, 0),
            new VersionTest(false, 1, 9,  0, 1)
        };
        doTest("git version 1.9.0.msysgit.0", versions);
    }

    public void test_git_version_windows_1920() {
        VersionTest[] versions = {
            new VersionTest(true,  1, 9, 2,  0),
            new VersionTest(true,  1, 9, 1, 99),
            new VersionTest(false, 1, 9, 2,  1)
        };
        doTest("git version 1.9.2.msysgit.0", versions);
    }

    public void test_git_version_windows_1940() {
        VersionTest[] versions = {
            new VersionTest(true,  1, 9, 4,  0),
            new VersionTest(true,  1, 9, 3, 99),
            new VersionTest(false, 1, 9, 4,  1)
        };
        doTest("git version 1.9.4.msysgit.0", versions);
    }

    public void test_git_version_windows_2501() {
        VersionTest[] versions = {
            new VersionTest(true,  2, 5, 0, 1),
            new VersionTest(true,  2, 5, 0, 0),
            new VersionTest(false, 2, 5, 0, 2)
        };
        doTest("git version 2.5.0.windows.1", versions);
    }

    public void test_git_version_windows_2_10_1_1() {
        VersionTest[] versions = {
            new VersionTest(true,  2, 10, 1, 1),
            new VersionTest(true,  2, 10, 1, 0),
            new VersionTest(true,  2, 10, 0, 1),
            new VersionTest(false, 2, 10, 1, 2),
            new VersionTest(false, 2, 10, 2, 0)
        };
        doTest("git version 2.10.1.windows.1", versions);
    }

    public void test_git_version_redhat_5() {
        VersionTest[] versions = {
            new VersionTest(true,  1, 8, 2, 1),
            new VersionTest(true,  1, 8, 2, 0),
            new VersionTest(false, 1, 8, 2, 2)
        };
        doTest("git version 1.8.2.1", versions);
    }

    public void test_git_version_redhat_65() {
        VersionTest[] versions = {
            new VersionTest(true,  1, 7, 1,  0),
            new VersionTest(true,  1, 7, 0, 99),
            new VersionTest(false, 1, 7, 1,  1),
            new VersionTest(false, 1, 7, 2,  0)
        };
        doTest("git version 1.7.1", versions);
    }

    public void test_git_version_opensuse_13() {
        VersionTest[] versions = {
            new VersionTest(true,  1, 8, 4, 5),
            new VersionTest(true,  1, 8, 4, 4),
            new VersionTest(false, 1, 8, 4, 6)
        };
        doTest("git version 1.8.4.5", versions);
    }

    public void test_git_version_ubuntu_13() {
        VersionTest[] versions = {
            new VersionTest(true,  1, 8, 3, 2),
            new VersionTest(true,  1, 8, 3, 1),
            new VersionTest(false, 1, 8, 3, 3)
        };
        doTest("git version 1.8.3.2", versions);
    }

    public void test_git_version_ubuntu_14_04_ppa() {
        VersionTest[] versions = {
            new VersionTest(true,  2, 2, 2, 0),
            new VersionTest(true,  2, 2, 1, 0),
            new VersionTest(false, 2, 2, 3, 0)
        };
        doTest("git version 2.2.2", versions);
    }

    public void test_git_version_ubuntu_14_04_ppa_2_3_0() {
        VersionTest[] versions = {
            new VersionTest(true,  2, 3, 0, 0),
            new VersionTest(true,  2, 2, 9, 0),
            new VersionTest(false, 2, 3, 1, 0)
        };
        doTest("git version 2.3.0", versions);
    }

    public void test_git_version_ubuntu_14_04_ppa_2_3_5() {
        VersionTest[] versions = {
            new VersionTest(true,  2, 3, 5, 0),
            new VersionTest(true,  2, 2, 9, 9),
            new VersionTest(false, 2, 3, 5, 1),
            new VersionTest(false, 2, 4, 0, 0),
            new VersionTest(false, 3, 0, 0, 0)
       };
        doTest("git version 2.3.5", versions);
    }

    /* Not implemented in JGit because it is not needed there */
    public void test_git_ssh_executable_found_on_windows() throws Exception {
        setTimeoutVisibleInCurrentTest(false);
        if (!SystemUtils.IS_OS_WINDOWS) {
            return;
        }

        assertTrue("ssh.exe not found", w.cgit().getSSHExecutable().exists());
    }

    public void test_git_branch_with_line_breaks_and_long_strings() throws Exception {
        String gitBranchOutput =
                "* (HEAD detached at b297853)  b297853e667d5989801937beea30fcec7d1d2595 Commit message with line breaks\r very-long-string-with-more-than-44-characters\n" +
                "  remotes/origin/master       e0d3f46c4fdb8acd068b6b127356931411d16e23 Commit message with line breaks\r very-long-string-with-more-than-44-characters and some more text\n" +
                "  remotes/origin/develop      fc8996efc1066d9dae529e5187800f84995ca56f Single-line commit message\n";

        setTimeoutVisibleInCurrentTest(false);
        CliGitAPIImpl git = new CliGitAPIImpl("git", new File("."), listener, env);
        Set<Branch> branches = git.parseBranches(gitBranchOutput);
        assertTrue("\"git branch -a -v --no-abbrev\" output correctly parsed", branches.size() == 2);
    }

    @Override
    protected String getRemoteBranchPrefix() {
        return "remotes/";
    }
}
