package org.jenkinsci.plugins.gitclient;

import org.hamcrest.Matcher;
import org.hamcrest.core.SubstringMatcher;

/**
 * Tests if the argument shares a prefix.
 */
public class StringSharesPrefix extends SubstringMatcher {
    public StringSharesPrefix(String substring) { super(substring); }

    @Override
    protected boolean evalSubstringOf(String s) {
        return s.startsWith(substring) ||
               substring.startsWith(s);
    }

    @Override
    protected String relationship() {
            return "sharing prefix with";
    }

    /**
     * <p>
     * Creates a matcher that matches if the examined {@link String} shares a
     * common prefix with the specified {@link String}.
     * </p>
     * For example:
     * <pre>assertThat("myString", sharesPrefix("myStringOfNote"))</pre>
     *
     * @param prefix
     *      the substring that the returned matcher will expect to share a
     *      prefix of any examined string
     */
    public static Matcher<String> sharesPrefix(String prefix) { return new StringSharesPrefix(prefix); }
}
