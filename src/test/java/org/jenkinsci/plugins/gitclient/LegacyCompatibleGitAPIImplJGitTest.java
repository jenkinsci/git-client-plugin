package org.jenkinsci.plugins.gitclient;

public class LegacyCompatibleGitAPIImplJGitTest extends LegacyCompatibleGitAPIImplTest {

    public LegacyCompatibleGitAPIImplJGitTest() {
        gitImpl = "jgit";
    }
}
