package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import hudson.plugins.git.GitException;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

public abstract class GitAPITestUpdateCliGit extends GitAPITestUpdate {

    /* Shows the submodule update is broken now that tests/getSubmodule includes a renamed submodule */
    @Test
    public void testSubmoduleUpdate() throws Exception {
        w.init();
        w.git.clone_().url(localMirror()).repositoryName("sub2_origin").execute();
        w.git.checkout()
                .branch("tests/getSubmodules")
                .ref("sub2_origin/tests/getSubmodules")
                .deleteBranchIfExist(true)
                .execute();
        w.git.submoduleInit();
        w.git.submoduleUpdate().execute();

        assertTrue("modules/firewall does not exist", w.exists("modules/firewall"));
        assertTrue("modules/ntp does not exist", w.exists("modules/ntp"));
        // JGit submodule implementation doesn't handle renamed submodules
        if (w.igit() instanceof CliGitAPIImpl) {
            assertTrue("modules/sshkeys does not exist", w.exists("modules/sshkeys"));
        }
        assertFixSubmoduleUrlsThrows();

        String shallow = Path.of(".git", "modules", "module", "1", "shallow").toString();
        assertFalse("shallow file existence: " + shallow, w.exists(shallow));
    }

    @Test
    public void testSubmoduleUpdateWithError() throws Exception {
        w.git.clone_().url(localMirror()).execute();
        w.git.checkout().ref("origin/tests/getSubmodules").execute();
        w.rm("modules/ntp");
        w.touch("modules/ntp", "file that interferes with ntp submodule folder");

        try {
            w.git.submoduleUpdate().execute();
            fail("Did not throw expected submodule update exception");
        } catch (GitException e) {
            /* Depending on git program implementation/version, the string can be either short:
             *    Command "git submodule update modules/ntp" returned status code 1"
             * or detailed:
             *    Command "git submodule update modules/ntp" executed in workdir "C:\Users\..." returned status code 1
             * so we catch below the two common parts separately.
             * NOTE: git codebase itself goes to great extents to forbid their
             * own unit-testing code from relying on emitted text messages.
             */
            assertThat(e.getMessage(), containsString("Command \"git submodule update modules/ntp\" "));
            assertThat(e.getMessage(), containsString(" returned status code 1"));
        }
    }

    @Test
    public void testSubmoduleUpdateWithThreads() throws Exception {
        w.init();
        w.git.clone_().url(localMirror()).repositoryName("sub2_origin").execute();
        w.git.checkout()
                .branch("tests/getSubmodules")
                .ref("sub2_origin/tests/getSubmodules")
                .deleteBranchIfExist(true)
                .execute();
        w.git.submoduleInit();
        w.git.submoduleUpdate().threads(3).execute();

        assertTrue("modules/firewall does not exist", w.exists("modules/firewall"));
        assertTrue("modules/ntp does not exist", w.exists("modules/ntp"));
        // JGit submodule implementation doesn't handle renamed submodules
        if (w.igit() instanceof CliGitAPIImpl) {
            assertTrue("modules/sshkeys does not exist", w.exists("modules/sshkeys"));
        }
        assertFixSubmoduleUrlsThrows();
    }

    @Test
    public void testTrackingSubmoduleBranches() throws Exception {
        w.init(); // empty repository

        // create a new GIT repo.
        //    default branch  -- <file1>C
        //    branch1 -- <file1>C <file2>C
        //    branch2 -- <file1>C <file3>C
        WorkingArea r = new WorkingArea(createTempDirectoryWithoutSpaces());
        r.init();
        r.touch("file1", "content1");
        r.git.add("file1");
        r.git.commit("submod-commit1");

        r.git.branch("branch1");
        r.git.checkout().ref("branch1").execute();
        r.touch("file2", "content2");
        r.git.add("file2");
        r.git.commit("submod-commit2");
        r.git.checkout().ref(defaultBranchName).execute();

        r.git.branch("branch2");
        r.git.checkout().ref("branch2").execute();
        r.touch("file3", "content3");
        r.git.add("file3");
        r.git.commit("submod-commit3");
        r.git.checkout().ref(defaultBranchName).execute();

        // Setup variables for use in tests
        String submodDir = "submod1" + UUID.randomUUID();
        String subFile1 = submodDir + File.separator + "file1";
        String subFile2 = submodDir + File.separator + "file2";
        String subFile3 = submodDir + File.separator + "file3";

        // Add new GIT repo to w, at the default branch
        w.cgit().allowFileProtocol();
        w.git.addSubmodule(r.repoPath(), submodDir);
        w.git.submoduleInit();
        assertTrue("file1 does not exist and should be we imported the submodule.", w.exists(subFile1));
        assertFalse("file2 exists and should not because not on 'branch1'", w.exists(subFile2));
        assertFalse("file3 exists and should not because not on 'branch2'", w.exists(subFile3));

        // Windows tests fail if repoPath is more than 200 characters
        // CLI git support for longpaths on Windows is complicated
        if (w.repoPath().length() > 200) {
            return;
        }

        // Switch to branch1
        submoduleUpdateTimeout = 1 + random.nextInt(60 * 24);
        w.git.submoduleUpdate()
                .remoteTracking(true)
                .useBranch(submodDir, "branch1")
                .timeout(submoduleUpdateTimeout)
                .execute();
        assertTrue("file2 does not exist and should because on branch1", w.exists(subFile2));
        assertFalse("file3 exists and should not because not on 'branch2'", w.exists(subFile3));

        // Switch to branch2
        w.git.submoduleUpdate()
                .remoteTracking(true)
                .useBranch(submodDir, "branch2")
                .timeout(submoduleUpdateTimeout)
                .execute();
        assertFalse("file2 exists and should not because not on 'branch1'", w.exists(subFile2));
        assertTrue("file3 does not exist and should because on branch2", w.exists(subFile3));

        // Switch to default branch
        w.git.submoduleUpdate()
                .remoteTracking(true)
                .useBranch(submodDir, defaultBranchName)
                .timeout(submoduleUpdateTimeout)
                .execute();
        assertFalse("file2 exists and should not because not on 'branch1'", w.exists(subFile2));
        assertFalse("file3 exists and should not because not on 'branch2'", w.exists(subFile3));
    }

    @Test
    public void testTrackingSubmodule() throws Exception {
        w.init(); // empty repository

        // create a new GIT repo.
        //   default branch -- <file1>C  <file2>C
        WorkingArea r = new WorkingArea(createTempDirectoryWithoutSpaces());
        r.init();
        r.touch("file1", "content1");
        r.git.add("file1");
        r.git.commit("submod-commit1");

        // Add new GIT repo to w
        w.cgit().allowFileProtocol();
        String subModDir = "submod1-" + UUID.randomUUID();
        w.git.addSubmodule(r.repoPath(), subModDir);
        w.git.submoduleInit();

        // Add a new file to the separate GIT repo.
        r.touch("file2", "content2");
        r.git.add("file2");
        r.git.commit("submod-branch1-commit1");

        // Make sure that the new file doesn't exist in the repo with remoteTracking
        String subFile = subModDir + File.separator + "file2";
        w.git.submoduleUpdate().recursive(true).remoteTracking(false).execute();
        assertFalse(
                "file2 exists and should not because we didn't update to the tip of the default branch.",
                w.exists(subFile));

        // Windows tests fail if repoPath is more than 200 characters
        // CLI git support for longpaths on Windows is complicated
        if (w.repoPath().length() > 200) {
            return;
        }

        // Run submodule update with remote tracking
        w.git.submoduleUpdate().recursive(true).remoteTracking(true).execute();
        assertTrue(
                "file2 does not exist and should because we updated to the tip of the default branch.",
                w.exists(subFile));
        assertFixSubmoduleUrlsThrows();
    }

    @Issue("JENKINS-37185")
    @Test
    public void testCheckoutHonorTimeout() throws Exception {
        w = clone(localMirror());

        checkoutTimeout = 1 + random.nextInt(60 * 24);
        w.git.checkout()
                .branch(DEFAULT_MIRROR_BRANCH_NAME)
                .ref("origin/" + DEFAULT_MIRROR_BRANCH_NAME)
                .timeout(checkoutTimeout)
                .deleteBranchIfExist(true)
                .execute();
    }

    @Test
    public void testSparseCheckout() throws Exception {
        // Create a repo for cloning purpose
        w.init();
        w.commitEmpty("init");
        assertTrue("mkdir dir1 failed", w.file("dir1").mkdir());
        w.touch("dir1/file1");
        assertTrue("mkdir dir2 failed", w.file("dir2").mkdir());
        w.touch("dir2/file2");
        assertTrue("mkdir dir3 failed", w.file("dir3").mkdir());
        w.touch("dir3/file3");
        w.git.add("dir1/file1");
        w.git.add("dir2/file2");
        w.git.add("dir3/file3");
        w.git.commit("commit");

        // Clone it
        WorkingArea workingArea = new WorkingArea();
        workingArea.git.clone_().url(w.repoPath()).execute();

        checkoutTimeout = 1 + random.nextInt(60 * 24);
        workingArea
                .git
                .checkout()
                .ref(defaultRemoteBranchName)
                .branch(defaultBranchName)
                .deleteBranchIfExist(true)
                .sparseCheckoutPaths(Collections.singletonList("dir1"))
                .timeout(checkoutTimeout)
                .execute();
        assertTrue(workingArea.exists("dir1"));
        assertFalse(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea
                .git
                .checkout()
                .ref(defaultRemoteBranchName)
                .branch(defaultBranchName)
                .deleteBranchIfExist(true)
                .sparseCheckoutPaths(Collections.singletonList("dir2"))
                .timeout(checkoutTimeout)
                .execute();
        assertFalse(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea
                .git
                .checkout()
                .ref(defaultRemoteBranchName)
                .branch(defaultBranchName)
                .deleteBranchIfExist(true)
                .sparseCheckoutPaths(Arrays.asList("dir1", "dir2"))
                .timeout(checkoutTimeout)
                .execute();
        assertTrue(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea
                .git
                .checkout()
                .ref(defaultRemoteBranchName)
                .branch(defaultBranchName)
                .deleteBranchIfExist(true)
                .sparseCheckoutPaths(Collections.emptyList())
                .timeout(checkoutTimeout)
                .execute();
        assertTrue(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertTrue(workingArea.exists("dir3"));

        workingArea
                .git
                .checkout()
                .ref(defaultRemoteBranchName)
                .branch(defaultBranchName)
                .deleteBranchIfExist(true)
                .sparseCheckoutPaths(null)
                .timeout(checkoutTimeout)
                .execute();
        assertTrue(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertTrue(workingArea.exists("dir3"));
    }
}
