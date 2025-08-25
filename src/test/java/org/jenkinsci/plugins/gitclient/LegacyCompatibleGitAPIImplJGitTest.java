package org.jenkinsci.plugins.gitclient;

class LegacyCompatibleGitAPIImplJGitTest extends LegacyCompatibleGitAPIImplTest {

    @Override
    protected String getGitImplementation() {
        return "jgit";
    }
}
