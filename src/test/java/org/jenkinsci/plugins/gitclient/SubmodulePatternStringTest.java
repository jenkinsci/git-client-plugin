package org.jenkinsci.plugins.gitclient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

public class SubmodulePatternStringTest {

    private Pattern submoduleConfigPattern;
    private String remoteName = "simple-name";

    @Before
    public void configure() {
        String patternString = CliGitAPIImpl.SUBMODULE_REMOTE_PATTERN_STRING;
        submoduleConfigPattern = Pattern.compile(patternString, Pattern.MULTILINE);
    }

    @Issue("JENKINS-46054")
    @Test
    @Ignore
    public void urlEmbeddedInRepoURL() {
        String repoUrl = "file://gitroot/thirdparty.url.repo.git";
        String submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        Matcher matcher = submoduleConfigPattern.matcher(submoduleConfigOutput);
        assertTrue("Match not found for '" + submoduleConfigOutput + "'", matcher.find());
        assertThat(matcher.group(1), is(remoteName));
    }

    @Issue("JENKINS-46054")
    @Test
    @Ignore
    public void urlEmbeddedInRepoURLsubmoduleEmbeddedDot() {
        String repoUrl = "https://mark.url:some%20pass.urlify@gitroot/repo.git";
        remoteName = "simple.name";
        String submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        Matcher matcher = submoduleConfigPattern.matcher(submoduleConfigOutput);
        assertTrue("Match not found for '" + submoduleConfigOutput + "'", matcher.find());
        assertThat(matcher.group(1), is(remoteName));
    }

    @Issue("JENKINS-46054")
    @Test
    @Ignore
    public void urlEmbeddedInSubmoduleRepoNameEndsWithURL() {
        String repoUrl = "https://gitroot/repo.url";
        remoteName = "simple.name";
        String submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        Matcher matcher = submoduleConfigPattern.matcher(submoduleConfigOutput);
        assertTrue("Match not found for '" + submoduleConfigOutput + "'", matcher.find());
        assertThat(matcher.group(1), is(remoteName));
    }

    @Issue("JENKINS-46054")
    @Test
    @Ignore
    public void urlEmbeddedInSubmoduleRepoNameEndsWithURLSpace() {
        String repoUrl = "https://gitroot/repo.url ";
        remoteName = "simple.name";
        String submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        Matcher matcher = submoduleConfigPattern.matcher(submoduleConfigOutput);
        assertTrue("Match not found for '" + submoduleConfigOutput + "'", matcher.find());
        assertThat(matcher.group(1), is(remoteName));
    }

    @Issue("JENKINS-46054")
    @Test
    @Ignore
    public void urlEmbeddedInSubmoduleNameAndRepoNameEndsWithURL() {
        String repoUrl = "https://gitroot/repo.url.git";
        remoteName = "simple.name.url";
        String submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        Matcher matcher = submoduleConfigPattern.matcher(submoduleConfigOutput);
        assertTrue("Match not found for '" + submoduleConfigOutput + "'", matcher.find());
        assertThat(matcher.group(1), is(remoteName));
    }

    @Issue("JENKINS-46054")
    @Test
    @Ignore
    public void urlExploratoryTestFailureCase() {
        /* See https://github.com/MarkEWaite/JENKINS-46054.url/ */
        String repoUrl = "https://github.com/MarkEWaite/JENKINS-46054.url";
        remoteName = "modules/JENKINS-46504.url";
        String submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        Matcher matcher = submoduleConfigPattern.matcher(submoduleConfigOutput);
        assertTrue("Match not found for '" + submoduleConfigOutput + "'", matcher.find());
        assertThat(matcher.group(1), is(remoteName));
    }

    @Issue("JENKINS-46054")
    @Ignore
    @Test
    public void remoteNameContainsSpaceRepoEndsWithUrl() {
        String repoUrl = "https://github.com/MarkEWaite/JENKINS-46054.url";
        remoteName = "modules/JENKINS-48818 embedded space in remote name";
        String submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        Matcher matcher = submoduleConfigPattern.matcher(submoduleConfigOutput);
        assertTrue("Match not found for '" + submoduleConfigOutput + "'", matcher.find());
        assertThat(matcher.group(1), is(remoteName));
    }

    @Issue("JENKINS-48818")
    @Test
    public void remoteNameContainsSpace() {
        String repoUrl = "https://github.com/MarkEWaite/JENKINS-46054";
        remoteName = "modules/JENKINS-48818 embedded space in remote name";
        String submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        Matcher matcher = submoduleConfigPattern.matcher(submoduleConfigOutput);
        assertTrue("Match not found for '" + submoduleConfigOutput + "'", matcher.find());
        assertThat(matcher.group(1), is(remoteName));
    }

    @Issue("JENKINS-46054")
    @Test
    @Ignore
    public void remoteNameIncludesSubmodule() {
        /* See https://github.com/MarkEWaite/JENKINS-46054.url/ */
        String repoUrl = "https://github.com/MarkEWaite/JENKINS-46054.url";
        remoteName = "submodule.JENKINS-46504.url";
        String submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        Matcher matcher = submoduleConfigPattern.matcher(submoduleConfigOutput);
        assertTrue("Match not found for '" + submoduleConfigOutput + "'", matcher.find());
        assertThat(matcher.group(1), is(remoteName));
    }

    @Issue("JENKINS-46054")
    @Test
    @Ignore
    public void urlEmbeddedInRepoNameEndsWithURLEmptyRemoteName() {
        String repoUrl = "https://github.com/MarkEWaite/JENKINS-46054.url.git";
        remoteName = "";
        String submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        Matcher matcher = submoduleConfigPattern.matcher(submoduleConfigOutput);
        assertFalse("Unexpected match for '" + submoduleConfigOutput + "'", matcher.find());
    }

    @Test
    @Ignore
    public void emptyRemoteName() {
        /* See https://github.com/MarkEWaite/JENKINS-46054.url/ */
        String repoUrl = "https://github.com/MarkEWaite/JENKINS-46054.url";
        remoteName = "";
        String submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        Matcher matcher = submoduleConfigPattern.matcher(submoduleConfigOutput);
        assertFalse("Unexpected match for '" + submoduleConfigOutput + "'", matcher.find());
    }

    @Test
    public void doesNotEndWithDotUrl() {
        String repoUrl = "https://github.com/MarkEWaite/JENKINS-46054";
        String submoduleConfigOutput = "submodule." + remoteName + ".url " + repoUrl;
        Matcher matcher = submoduleConfigPattern.matcher(submoduleConfigOutput);
        assertTrue("Match not found for '" + submoduleConfigOutput + "'", matcher.find());
        assertThat(matcher.group(1), is(remoteName));
    }
}
