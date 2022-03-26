package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.GitException;
import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;

public abstract class GitAPITestUpdateCliGit extends GitAPITestUpdate{

    /* Shows the submodule update is broken now that tests/getSubmodule includes a renamed submodule */
    @Test
    public void testSubmoduleUpdate() throws Exception {
        w.init();
        w.git.clone_().url(localMirror()).repositoryName("sub2_origin").execute();
        w.git.checkout().branch("tests/getSubmodules").ref("sub2_origin/tests/getSubmodules").deleteBranchIfExist(true).execute();
        w.git.submoduleInit();
        w.git.submoduleUpdate().execute();

        assertTrue("modules/firewall does not exist", w.exists("modules/firewall"));
        assertTrue("modules/ntp does not exist", w.exists("modules/ntp"));
        // JGit submodule implementation doesn't handle renamed submodules
        if (w.igit() instanceof CliGitAPIImpl) {
            assertTrue("modules/sshkeys does not exist", w.exists("modules/sshkeys"));
        }
        assertFixSubmoduleUrlsThrows();

        String shallow = Paths.get(".git", "modules", "module", "1", "shallow").toString();
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
            assertThat(e.getMessage(), containsString("Command \"git submodule update modules/ntp\" returned status code 1"));
        }
    }

    @Test
    public void testSubmoduleUpdateWithThreads() throws Exception {
        w.init();
        w.git.clone_().url(localMirror()).repositoryName("sub2_origin").execute();
        w.git.checkout().branch("tests/getSubmodules").ref("sub2_origin/tests/getSubmodules").deleteBranchIfExist(true).execute();
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
        if (! ((CliGitAPIImpl)w.git).isAtLeastVersion(1,8,2,0)) {
            setTimeoutVisibleInCurrentTest(false);
            System.err.println("git must be at least 1.8.2 to do tracking submodules.");
            return;
        }
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
        w.git.submoduleUpdate().remoteTracking(true).useBranch(submodDir, "branch1").timeout(submoduleUpdateTimeout).execute();
        assertTrue("file2 does not exist and should because on branch1", w.exists(subFile2));
        assertFalse("file3 exists and should not because not on 'branch2'", w.exists(subFile3));

        // Switch to branch2
        w.git.submoduleUpdate().remoteTracking(true).useBranch(submodDir, "branch2").timeout(submoduleUpdateTimeout).execute();
        assertFalse("file2 exists and should not because not on 'branch1'", w.exists(subFile2));
        assertTrue("file3 does not exist and should because on branch2", w.exists(subFile3));

        // Switch to default branch
        w.git.submoduleUpdate().remoteTracking(true).useBranch(submodDir, defaultBranchName).timeout(submoduleUpdateTimeout).execute();
        assertFalse("file2 exists and should not because not on 'branch1'", w.exists(subFile2));
        assertFalse("file3 exists and should not because not on 'branch2'", w.exists(subFile3));
    }

    @Test
    public void testTrackingSubmodule() throws Exception {
        if (! ((CliGitAPIImpl)w.git).isAtLeastVersion(1,8,2,0)) {
            System.err.println("git must be at least 1.8.2 to do tracking submodules.");
            return;
        }
        w.init(); // empty repository

        // create a new GIT repo.
        //   default branch -- <file1>C  <file2>C
        WorkingArea r = new WorkingArea(createTempDirectoryWithoutSpaces());
        r.init();
        r.touch("file1", "content1");
        r.git.add("file1");
        r.git.commit("submod-commit1");

        // Add new GIT repo to w
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
        assertFalse("file2 exists and should not because we didn't update to the tip of the default branch.", w.exists(subFile));

        // Windows tests fail if repoPath is more than 200 characters
        // CLI git support for longpaths on Windows is complicated
        if (w.repoPath().length() > 200) {
            return;
        }

        // Run submodule update with remote tracking
        w.git.submoduleUpdate().recursive(true).remoteTracking(true).execute();
        assertTrue("file2 does not exist and should because we updated to the tip of the default branch.", w.exists(subFile));
        assertFixSubmoduleUrlsThrows();
    }

}
