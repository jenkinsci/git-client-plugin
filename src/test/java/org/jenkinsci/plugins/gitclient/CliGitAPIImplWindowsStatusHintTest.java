package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

/**
 * Tests the hexadecimal exit status hint added to git command failure messages
 * on Windows (JENKINS-61076).
 */
class CliGitAPIImplWindowsStatusHintTest {

    @Test
    void hexOfNegativeStatusOnWindows() {
        // -1073741502 is 0xC0000142 (STATUS_DLL_INIT_FAILED), the value reported in JENKINS-61076
        String hint = CliGitAPIImpl.windowsStatusHint(-1073741502, true);
        assertThat(hint, containsString("0xC0000142"));
        assertThat(hint, containsString("learn.microsoft.com"));
    }

    @Test
    void hexOfStatusMinusOneOnWindows() {
        assertThat(CliGitAPIImpl.windowsStatusHint(-1, true), containsString("0xFFFFFFFF"));
    }

    @Test
    void hexIsZeroPaddedToEightDigits() {
        assertThat(CliGitAPIImpl.windowsStatusHint(256, true), containsString("0x00000100"));
    }

    @Test
    void noHintForNormalExitStatusOnWindows() {
        assertThat(CliGitAPIImpl.windowsStatusHint(1, true), is(""));
        assertThat(CliGitAPIImpl.windowsStatusHint(128, true), is(""));
        assertThat(CliGitAPIImpl.windowsStatusHint(255, true), is(""));
    }

    @Test
    void noHintWhenNotWindows() {
        assertThat(CliGitAPIImpl.windowsStatusHint(-1073741502, false), is(""));
        assertThat(CliGitAPIImpl.windowsStatusHint(1, false), is(""));
    }
}
