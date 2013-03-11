package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.IGitAPI;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CliGitAPIImplTest extends GitAPITestCase {
    @Override
    protected GitClient setupGitAPI() {
        return Git.with(listener, env).in(repo).using("git").getClient();
    }
}
