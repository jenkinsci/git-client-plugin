package org.jenkinsci.plugins.gitclient;

import org.hamcrest.Matcher;
import org.hamcrest.core.SubstringMatcher;

/**
 * Tests if the argument shares a prefix.
 */
class StringSharesPrefix extends SubstringMatcher {
    private static final String RELATIONSHIP = "sharing prefix with";

    protected StringSharesPrefix(String relationship, boolean ignoringCase, String substring) {
        super(RELATIONSHIP, false, substring);
    }

    @Override
    protected boolean evalSubstringOf(String s) {
        return s.startsWith(substring) || substring.startsWith(s);
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
    static Matcher<String> sharesPrefix(String prefix) {
        return new StringSharesPrefix(RELATIONSHIP, false, prefix);
    }
}
