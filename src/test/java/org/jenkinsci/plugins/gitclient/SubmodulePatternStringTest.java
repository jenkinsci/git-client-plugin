package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.Issue;

@ParameterizedClass(name = "{0}-{1}")
@MethodSource("repoAndRemote")
class SubmodulePatternStringTest {

    @Parameter(0)
    private String repoUrl;

    @Parameter(1)
    private String remoteName;

    private String submoduleConfigOutput;
    private Matcher matcher;

    private static final Pattern SUBMODULE_CONFIG_PATTERN =
            Pattern.compile(CliGitAPIImpl.SUBMODULE_REMOTE_PATTERN_STRING, Pattern.MULTILINE);

    @BeforeEach
    void setUp() {
        submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        matcher = SUBMODULE_CONFIG_PATTERN.matcher(submoduleConfigOutput);
    }

    /*
     * Permutations of repository URLs and remote names with various
     * protocols and remote names, permuted with various suffixes.
     *
     * Tests file, ssh (both forms), git, and https.
     */
    static List<Arguments> repoAndRemote() {
        List<Arguments> arguments = new ArrayList<>();
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
            "has space", "has.url space", "simple", "simple.name", "simple.url.name", "url", "modules/module.named.url"
        };
        String[] suffixes = {
            "", ".git", ".url", ".url.git", ".git.url",
        };
        for (String repoUrlParam : repoUrls) {
            for (String repoUrlSuffix : suffixes) {
                for (String remoteNameParam : remoteNames) {
                    for (String remoteNameSuffix : suffixes) {
                        Arguments item = Arguments.of(repoUrlParam + repoUrlSuffix, remoteNameParam + remoteNameSuffix);
                        arguments.add(item);
                    }
                }
            }
        }
        return arguments;
    }

    @Issue("JENKINS-46054")
    @Test
    void urlFoundInSubmoduleConfigOutput() {
        assertTrue(matcher.find(), "Match not found for '" + submoduleConfigOutput + "'");
        assertThat(matcher.group(1), is(remoteName));
    }
}
