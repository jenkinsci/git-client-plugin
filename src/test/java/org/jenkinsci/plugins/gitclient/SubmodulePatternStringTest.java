package org.jenkinsci.plugins.gitclient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;

@RunWith(Parameterized.class)
public class SubmodulePatternStringTest {

    private final String remoteName;
    private final String submoduleConfigOutputNewlineSeparated;
    private final String submoduleConfigOutputNullSeparated;

    public SubmodulePatternStringTest(String repoUrl, String remoteName)
    {
        this.remoteName = remoteName;
        // "git config --get" default style
        this.submoduleConfigOutputNewlineSeparated = "submodule." + remoteName + ".url " + repoUrl;
        // "git config --get --null" style
        this.submoduleConfigOutputNullSeparated = "submodule." + remoteName + ".url\n" + repoUrl + '\0';
    }

    /*
     * Permutations of repository URLs and remote names with various
     * protocols and remote names, permuted with various suffixes.
     *
     * Tests file, ssh (both forms), git, and https.
     */
    @Parameterized.Parameters(name = "{0}-{1}")
    public static Collection repoAndRemote() {
        List<Object[]> arguments = new ArrayList<>();
        String[] repoUrls = {
            "file://gitroot/thirdparty.url.repo",
            "git://gitroot/repo",
            "git@github.com:jenkinsci/JENKINS-46054",
            "https://github.com/MarkEWaite/JENKINS-46054",
            "https://mark.url:some%20pass.urlify@gitroot/repo",
            "ssh://git.example.com/MarkEWaite/JENKINS-46054",
        };
        String[] remoteNames = {
            "has space",
            "has.url space",
            "simple",
            "simple.name",
            "simple.url.name",
        };
        String [] suffixes = {
            "",
            ".git",
            ".url",
            ".url.git",
        };
        for (String repoUrlParam : repoUrls) {
            for (String repoUrlSuffix : suffixes) {
                for (String remoteNameParam : remoteNames) {
                    for (String remoteNameSuffix : suffixes) {
                        Object[] item = {repoUrlParam + repoUrlSuffix, remoteNameParam + remoteNameSuffix};
                        arguments.add(item);
                    }
                }
            }
        }
        return arguments;
    }

    @Issue("JENKINS-46054")
    @Test
    public void urlFoundInSubmoduleConfigOutputPre153() {
        Map<String, String> results = CliGitAPIImpl.parseSubmodulesListingWithNewlineSeparator(submoduleConfigOutputNewlineSeparated, null);
        assertFalse("Match not found for '" + submoduleConfigOutputNewlineSeparated + "'", results.isEmpty());
        assertThat(results.keySet().iterator().next(), is(remoteName));
    }

    @Test
    public void urlFoundInSubmoduleConfigOutputPost153() {
        Map<String, String> results = CliGitAPIImpl.parseSubmodulesListingWithNullSeparator(submoduleConfigOutputNullSeparated, null);
        assertFalse("Match not found for '" + submoduleConfigOutputNullSeparated + "'", results.isEmpty());
        assertThat(results.keySet().iterator().next(), is(remoteName));
    }
}
