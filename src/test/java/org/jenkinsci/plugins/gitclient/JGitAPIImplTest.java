package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.IGitAPI;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class JGitAPIImplTest extends GitAPITestCase {
    @Override
    protected IGitAPI setupGitAPI() {
        return new JGitAPIImpl("git", repo, listener, env);
    }
}
