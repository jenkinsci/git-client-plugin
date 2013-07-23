package org.jenkinsci.plugins.gitclient;

import java.io.File;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CliGitAPIImplTest extends GitAPITestCase {
    @Override
    protected GitClient setupGitAPI(File ws) throws Exception {
        return Git.with(listener, env).in(ws).using("git").getClient();
    }
}
