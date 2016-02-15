package org.jenkinsci.plugins.gitclient.utils;

import edu.umd.cs.findbugs.annotations.CheckForNull;

import static org.apache.commons.lang.StringUtils.isNotBlank;

public final class GitOutputUtils {

    private GitOutputUtils() {
        throw new UnsupportedOperationException("Not supported");
    }

    /**
     * Return first non blank line or null if no result.
     *
     * @param result git output.
     * @return first non blank line or null if no result.
     */
    @CheckForNull
    public static String firstNonBlankLine(String result) {
        String[] lines = result.split(System.getProperty("line.separator"));

        for (String line : lines) {
            if (isNotBlank(line)) {
                return line;
            }
        }
        return null;
    }
}
