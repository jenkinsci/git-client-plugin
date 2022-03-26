package org.jenkinsci.plugins.gitclient;

import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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


}
