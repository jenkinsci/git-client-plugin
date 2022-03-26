package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.GitException;
import org.junit.Test;

import java.nio.file.Paths;

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



}
