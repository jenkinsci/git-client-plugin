package org.jenkinsci.plugins.gitclient;

import java.io.File;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
class JGitAPIImplTest extends GitAPITestUpdate {

    @Override
    protected GitClient setupGitAPI(File ws) throws Exception {
        return Git.with(listener, env).in(ws).using("jgit").getClient();
    }

    @Override
    protected String getRemoteBranchPrefix() {
        return "";
    }
}
