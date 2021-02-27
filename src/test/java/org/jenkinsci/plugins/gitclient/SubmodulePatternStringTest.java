package org.jenkinsci.plugins.gitclient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;

@RunWith(Parameterized.class)
public class SubmodulePatternStringTest {

    private final String remoteName;
    private final String submoduleConfigOutput;
    private final Matcher matcher;

    private static final Pattern SUBMODULE_CONFIG_PATTERN = Pattern.compile(CliGitAPIImpl.SUBMODULE_REMOTE_PATTERN_STRING, Pattern.MULTILINE);

    public SubmodulePatternStringTest(String repoUrl, String remoteName)
    {
        this.remoteName = remoteName;
        this.submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        this.matcher = SUBMODULE_CONFIG_PATTERN.matcher(submoduleConfigOutput);
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
            // JENKINS-56175 notes that submodule URL's with spaces don't
            // work in the git plugin. This test shows they don't work at
            // one level. Other levels also have failures.
            // "file://gitroot/has space",
        };
        String[] remoteNames = {
            "has space",
            "has.url space",
            "simple",
            "simple.name",
            "simple.url.name",
            "url",
            "modules/module.named.url"
        };
        String [] suffixes = {
            "",
            ".git",
            ".url",
            ".url.git",
            ".git.url",
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
    public void urlFoundInSubmoduleConfigOutput() {
        assertTrue("Match not found for '" + submoduleConfigOutput + "'", matcher.find());
        assertThat(matcher.group(1), is(remoteName));
    }
}
