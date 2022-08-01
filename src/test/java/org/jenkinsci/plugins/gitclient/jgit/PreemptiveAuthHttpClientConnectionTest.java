package org.jenkinsci.plugins.gitclient.jgit;

import static org.junit.Assert.*;

import org.apache.http.auth.NTCredentials;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

/**
 * A class to test {@link PreemptiveAuthHttpClientConnectionTest}.
 */
public class PreemptiveAuthHttpClientConnectionTest {

    @Test public void goUp_noPath() throws Exception {
        final URIish input = new URIish("https://example.com");

        final URIish actual = PreemptiveAuthHttpClientConnection.goUp(input);

        assertNull(actual);
    }

    @Test public void goUp_slash() throws Exception {
        final URIish input = new URIish("https://example.com/");

        final URIish actual = PreemptiveAuthHttpClientConnection.goUp(input);

        assertNull(actual);
    }

    @Test public void goUp_slashSlash() throws Exception {
        final URIish input = new URIish("https://example.com//");

        final URIish actual = PreemptiveAuthHttpClientConnection.goUp(input);

        assertNotNull(actual);
        assertEquals("https://example.com", actual.toString());
    }

    @Test public void goUp_one() throws Exception {
        final URIish input = new URIish("https://example.com/one");

        final URIish actual = PreemptiveAuthHttpClientConnection.goUp(input);

        assertNotNull(actual);
        assertEquals("https://example.com", actual.toString());
    }

    @Test public void goUp_oneSlash() throws Exception {
        final URIish input = new URIish("https://example.com/one/");

        final URIish actual = PreemptiveAuthHttpClientConnection.goUp(input);

        assertNotNull(actual);
        assertEquals("https://example.com", actual.toString());
    }

    @Test public void goUp_oneSlashTwo() throws Exception {
        final URIish input = new URIish("https://example.com/one/two");

        final URIish actual = PreemptiveAuthHttpClientConnection.goUp(input);

        assertNotNull(actual);
        assertEquals("https://example.com/one", actual.toString());
    }

    @Test public void goUp_oneSlashSlashTwoSlash() throws Exception {
        final URIish input = new URIish("https://example.com/one//two/");

        final URIish actual = PreemptiveAuthHttpClientConnection.goUp(input);

        assertNotNull(actual);
        assertEquals("https://example.com/one/", actual.toString());
    }

    @Test public void goUp_oneSlashTwoSlash() throws Exception {
        final URIish input = new URIish("https://example.com/one/two/");

        final URIish actual = PreemptiveAuthHttpClientConnection.goUp(input);

        assertNotNull(actual);
        assertEquals("https://example.com/one", actual.toString());
    }

    private static void createNTCredentials(final String inputUserName, final String inputPassword, final String expectedDomain, final String expectedUserName, final String expectedPassword) {

        final NTCredentials actual = PreemptiveAuthHttpClientConnection.createNTCredentials(inputUserName, inputPassword);

        assertEquals(expectedDomain, actual.getDomain());
        assertEquals(expectedUserName, actual.getUserName());
        assertEquals(expectedPassword, actual.getPassword());
    }

    @Test public void createNTCredentials_plainUser() {
        createNTCredentials("cnorris", "roundhouse", null, "cnorris", "roundhouse");
        createNTCredentials("cnorris", "round\\:/house", null, "cnorris", "round\\:/house");
    }

    @Test public void createNTCredentials_domainBackslashUser() {
        createNTCredentials("WALKER\\cnorris", "roundhouse", "WALKER", "cnorris", "roundhouse");
        createNTCredentials("WALKER\\cnorris", "round\\:/house", "WALKER", "cnorris", "round\\:/house");
    }

    @Test public void createNTCredentials_domainSlashUser() {
        createNTCredentials("WALKER/cnorris", "roundhouse", "WALKER", "cnorris", "roundhouse");
        createNTCredentials("WALKER/cnorris", "round\\:/house", "WALKER", "cnorris", "round\\:/house");
    }

    @Test public void createNTCredentials_userAtDomain() {
        createNTCredentials("cnorris@walker.example.com", "roundhouse", "WALKER.EXAMPLE.COM", "cnorris", "roundhouse");
        createNTCredentials("cnorris@walker.example.com", "round\\:/house", "WALKER.EXAMPLE.COM", "cnorris", "round\\:/house");
    }

}
