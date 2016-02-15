package org.jenkinsci.plugins.gitclient.utils;

import org.junit.Test;

import static org.junit.Assert.*;

public class GitOutputUtilsTest {

    @Test
    public void testFirstNonBlankLine() throws Exception {
        String expected = "58c8401641511340b7d919a4e6b805d9f3416d3f";

        String result = GitOutputUtils.firstNonBlankLine("\n" + expected);

        assertNotNull(result);
        assertEquals(expected, result);
    }

    @Test
    public void testFirstNonBlankLine_EmptyLine() throws Exception {
        String result = GitOutputUtils.firstNonBlankLine("\n");

        assertNull(result);
    }

    @Test
    public void testFirstNonBlankLine_NoResult() throws Exception {
        String result = GitOutputUtils.firstNonBlankLine("");

        assertNull(result);
    }
}