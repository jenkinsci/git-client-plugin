package org.jenkinsci.plugins.gitclient;

import java.io.IOException;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CliGitAPIImplTest extends GitAPITestCase {
    @Override
    protected GitClient setupGitAPI() throws Exception {
        return Git.with(listener, env).in(repo).using("git").getClient();
    }
}
