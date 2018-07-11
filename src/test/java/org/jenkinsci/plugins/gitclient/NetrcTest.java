package org.jenkinsci.plugins.gitclient;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.apache.commons.io.IOUtils;

import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NetrcTest
{
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String TEST_NETRC_FILE_1 = "netrc_1";
    private static final String TEST_NETRC_FILE_1a = "netrc_1a";
    private static final String TEST_NETRC_FILE_2 = "netrc_2";
    private String testFilePath_1;
    private String testFilePath_1a;
    private String testFilePath_2;

    private enum TestHost {
        H1_01("1-srvr-lp.example.com", "jenkins", "pw4jenkins"),
        H1_02("2-ldap-lp.example.com", "ldap", "pw4ldap"),
        H1_03("3-jenk-pl.example.com", "jenkins", "jenkinspwd"),
        H1_04("4-slv1-lp.example.com", "jenk", "slavepwd"),
        H1_05("5-sonr-l_.example.com", "sonar", null),
        H1_06("6-slv2-p_.example.com", null, "passwd"),
        H1_07("7-slv3-lp.example.com", "jenkins", "pw4jenkins"),
        H1_08("8-empt-__.nowhere.com", null, null),
        H1_09("9-ftps-lp", "james", "last"),
        H1_10("10-ftps-lp", "fred", "vargas"),
        H1_11("11-ftps-__", null, null),
        H1_12("12-last-lp", "lager", "topaz"),

        // H1_05 deleted
        H1a_06("6-slv2-p_.example.com", "builduser", "passwd"),
        H1a_08("8-empt-__.nowhere.com", "master", "key"),
        // H1_09 deleted
        // H1_10 deleted
        // H1_11 deleted

        H2_01("builder1.example.com", "jenkins", "secret"),
        H2_02("builder2.example.com", "builder", "buildpass"),
        H2_03("builder3.example.com", null, null),
        H2_04("builder4.example.com", "jenk", "myvoice");

        private String machine;
        private String login;
        private String password;

        private TestHost(String _machine, String _login, String _password)
        {
            this.machine = _machine;
            this.login = _login;
            this.password = _password;
        }
    }


    private void assertCredentials(TestHost host, Credentials cred)
    {
        if (cred == null) {
            assertTrue("Host." + host.name() + ": Credentials are null, although both login and password are set. (" + host.login + ":" + host.password + ")",
                    host.login == null || host.password == null);
        }
        else {
            assertEquals("Host." + host.name() + ": Login mismatch.", host.login, ((UsernamePasswordCredentials)cred).getUserName());
            assertEquals("Host." + host.name() + ": Password mismatch.", host.password, ((UsernamePasswordCredentials)cred).getPassword());
        }
    }

    private void copyFileContents(String source, String destination) throws IOException
    {
        try (InputStream sourceStream = Files.newInputStream(Paths.get(source));
                OutputStream out = Files.newOutputStream(Paths.get(destination))) {
            IOUtils.copy(sourceStream, out);
        }
    }

    private void copyResourceContents(String resource, String destination) throws IOException
    {
        try (InputStream sourceStream = this.getClass().getClassLoader().getResourceAsStream(resource);
                OutputStream out = Files.newOutputStream(Paths.get(destination))) {
            IOUtils.copy(sourceStream, out);
        }
    }

    @Before
    public void setup() throws IOException
    {
        testFilePath_1 = folder.newFile(TEST_NETRC_FILE_1).getAbsolutePath();
        copyResourceContents(TEST_NETRC_FILE_1 + ".in", testFilePath_1);

        testFilePath_1a = folder.newFile(TEST_NETRC_FILE_1a).getAbsolutePath();
        copyResourceContents(TEST_NETRC_FILE_1a + ".in", testFilePath_1a);

        testFilePath_2 = folder.newFile(TEST_NETRC_FILE_2).getAbsolutePath();
        copyResourceContents(TEST_NETRC_FILE_2 + ".in", testFilePath_2);
    }


    @Test
    public void testGetInstanceString()
    {
        Netrc netrc = Netrc.getInstance(testFilePath_1);
        assertNotNull(netrc);
    }

    @Test
    public void testGetInstanceFile()
    {
        Netrc netrc = Netrc.getInstance(new File(testFilePath_1));
        assertNotNull(netrc);
    }


    @Test
    public void testGetCredentialsPath()
    {
        Netrc netrc = Netrc.getInstance(testFilePath_1);
        assertNotNull(netrc);

        assertCredentials(TestHost.H1_01, netrc.getCredentials(TestHost.H1_01.machine));
        assertCredentials(TestHost.H1_02, netrc.getCredentials(TestHost.H1_02.machine));
        assertCredentials(TestHost.H1_03, netrc.getCredentials(TestHost.H1_03.machine));
        assertCredentials(TestHost.H1_04, netrc.getCredentials(TestHost.H1_04.machine));
        assertCredentials(TestHost.H1_05, netrc.getCredentials(TestHost.H1_05.machine));
        assertCredentials(TestHost.H1_06, netrc.getCredentials(TestHost.H1_06.machine));
        assertCredentials(TestHost.H1_07, netrc.getCredentials(TestHost.H1_07.machine));
        assertCredentials(TestHost.H1_08, netrc.getCredentials(TestHost.H1_08.machine));
        assertCredentials(TestHost.H1_09, netrc.getCredentials(TestHost.H1_09.machine));
        assertCredentials(TestHost.H1_10, netrc.getCredentials(TestHost.H1_10.machine));
        assertCredentials(TestHost.H1_11, netrc.getCredentials(TestHost.H1_11.machine));
        assertCredentials(TestHost.H1_12, netrc.getCredentials(TestHost.H1_12.machine));

        assertNull("Credentials for H2_01 should be null.", netrc.getCredentials(TestHost.H2_01.machine));
        assertNull("Credentials for H2_02 should be null.", netrc.getCredentials(TestHost.H2_02.machine));
        assertNull("Credentials for H2_03 should be null.", netrc.getCredentials(TestHost.H2_03.machine));
        assertNull("Credentials for H2_04 should be null.", netrc.getCredentials(TestHost.H2_04.machine));
    }


    @Test
    public void testGetCredentialsFile()
    {
        Netrc netrc = Netrc.getInstance(new File(testFilePath_1));
        assertNotNull(netrc);

        assertCredentials(TestHost.H1_01, netrc.getCredentials(TestHost.H1_01.machine));
        assertCredentials(TestHost.H1_02, netrc.getCredentials(TestHost.H1_02.machine));
        assertCredentials(TestHost.H1_03, netrc.getCredentials(TestHost.H1_03.machine));
        assertCredentials(TestHost.H1_04, netrc.getCredentials(TestHost.H1_04.machine));
        assertCredentials(TestHost.H1_05, netrc.getCredentials(TestHost.H1_05.machine));
        assertCredentials(TestHost.H1_06, netrc.getCredentials(TestHost.H1_06.machine));
        assertCredentials(TestHost.H1_07, netrc.getCredentials(TestHost.H1_07.machine));
        assertCredentials(TestHost.H1_08, netrc.getCredentials(TestHost.H1_08.machine));
        assertCredentials(TestHost.H1_09, netrc.getCredentials(TestHost.H1_09.machine));
        assertCredentials(TestHost.H1_10, netrc.getCredentials(TestHost.H1_10.machine));
        assertCredentials(TestHost.H1_11, netrc.getCredentials(TestHost.H1_11.machine));
        assertCredentials(TestHost.H1_12, netrc.getCredentials(TestHost.H1_12.machine));

        assertNull("Credentials for H2_01 should be null.", netrc.getCredentials(TestHost.H2_01.machine));
        assertNull("Credentials for H2_02 should be null.", netrc.getCredentials(TestHost.H2_02.machine));
        assertNull("Credentials for H2_03 should be null.", netrc.getCredentials(TestHost.H2_03.machine));
        assertNull("Credentials for H2_04 should be null.", netrc.getCredentials(TestHost.H2_04.machine));
    }


    @Test
    public void testGetCredentialsModifyFile() throws IOException
    {
        String testFilePath = testFilePath_1 + "_m";

        copyFileContents(testFilePath_1, testFilePath);

        Netrc netrc = Netrc.getInstance(testFilePath);
        assertNotNull(netrc);

        assertCredentials(TestHost.H1_01, netrc.getCredentials(TestHost.H1_01.machine));
        assertCredentials(TestHost.H1_02, netrc.getCredentials(TestHost.H1_02.machine));
        assertCredentials(TestHost.H1_03, netrc.getCredentials(TestHost.H1_03.machine));
        assertCredentials(TestHost.H1_04, netrc.getCredentials(TestHost.H1_04.machine));
        assertCredentials(TestHost.H1_05, netrc.getCredentials(TestHost.H1_05.machine));
        assertCredentials(TestHost.H1_06, netrc.getCredentials(TestHost.H1_06.machine));
        assertCredentials(TestHost.H1_07, netrc.getCredentials(TestHost.H1_07.machine));
        assertCredentials(TestHost.H1_08, netrc.getCredentials(TestHost.H1_08.machine));
        assertCredentials(TestHost.H1_09, netrc.getCredentials(TestHost.H1_09.machine));
        assertCredentials(TestHost.H1_10, netrc.getCredentials(TestHost.H1_10.machine));
        assertCredentials(TestHost.H1_11, netrc.getCredentials(TestHost.H1_11.machine));
        assertCredentials(TestHost.H1_12, netrc.getCredentials(TestHost.H1_12.machine));


        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) { /* ignored */ }
        copyFileContents(testFilePath_1a, testFilePath);


        assertCredentials(TestHost.H1_01, netrc.getCredentials(TestHost.H1_01.machine));
        assertCredentials(TestHost.H1_02, netrc.getCredentials(TestHost.H1_02.machine));
        assertCredentials(TestHost.H1_03, netrc.getCredentials(TestHost.H1_03.machine));
        assertCredentials(TestHost.H1_04, netrc.getCredentials(TestHost.H1_04.machine));

        assertNull("Credentials for H1_05 should be null.", netrc.getCredentials(TestHost.H1_05.machine));
        assertCredentials(TestHost.H1a_06, netrc.getCredentials(TestHost.H1_06.machine));

        assertCredentials(TestHost.H1_07, netrc.getCredentials(TestHost.H1_07.machine));

        assertCredentials(TestHost.H1a_08, netrc.getCredentials(TestHost.H1_08.machine));
        assertNull("Credentials for H1_09 should be null.", netrc.getCredentials(TestHost.H1_09.machine));
        assertNull("Credentials for H1_10 should be null.", netrc.getCredentials(TestHost.H1_10.machine));
        assertNull("Credentials for H1_11 should be null.", netrc.getCredentials(TestHost.H1_11.machine));

        assertCredentials(TestHost.H1_12, netrc.getCredentials(TestHost.H1_12.machine));

    }


    @Test
    public void testGetCredentialsOtherFile()
    {
        Netrc netrc = Netrc.getInstance(testFilePath_1);
        assertNotNull(netrc);

        assertCredentials(TestHost.H1_01, netrc.getCredentials(TestHost.H1_01.machine));
        assertCredentials(TestHost.H1_02, netrc.getCredentials(TestHost.H1_02.machine));
        assertCredentials(TestHost.H1_03, netrc.getCredentials(TestHost.H1_03.machine));
        assertCredentials(TestHost.H1_04, netrc.getCredentials(TestHost.H1_04.machine));
        assertCredentials(TestHost.H1_05, netrc.getCredentials(TestHost.H1_05.machine));
        assertCredentials(TestHost.H1_06, netrc.getCredentials(TestHost.H1_06.machine));
        assertCredentials(TestHost.H1_07, netrc.getCredentials(TestHost.H1_07.machine));
        assertCredentials(TestHost.H1_08, netrc.getCredentials(TestHost.H1_08.machine));
        assertCredentials(TestHost.H1_09, netrc.getCredentials(TestHost.H1_09.machine));
        assertCredentials(TestHost.H1_10, netrc.getCredentials(TestHost.H1_10.machine));
        assertCredentials(TestHost.H1_11, netrc.getCredentials(TestHost.H1_11.machine));
        assertCredentials(TestHost.H1_12, netrc.getCredentials(TestHost.H1_12.machine));
        assertNull("Credentials for H2_01 should be null.", netrc.getCredentials(TestHost.H2_01.machine));
        assertNull("Credentials for H2_02 should be null.", netrc.getCredentials(TestHost.H2_02.machine));
        assertNull("Credentials for H2_03 should be null.", netrc.getCredentials(TestHost.H2_03.machine));
        assertNull("Credentials for H2_04 should be null.", netrc.getCredentials(TestHost.H2_04.machine));

        netrc = Netrc.getInstance(testFilePath_2);
        assertNotNull(netrc);

        assertCredentials(TestHost.H2_01, netrc.getCredentials(TestHost.H2_01.machine));
        assertCredentials(TestHost.H2_02, netrc.getCredentials(TestHost.H2_02.machine));
        assertCredentials(TestHost.H2_03, netrc.getCredentials(TestHost.H2_03.machine));
        assertCredentials(TestHost.H2_04, netrc.getCredentials(TestHost.H2_04.machine));
        assertNull("Credentials for H1_01 should be null.", netrc.getCredentials(TestHost.H1_01.machine));
        assertNull("Credentials for H1_02 should be null.", netrc.getCredentials(TestHost.H1_02.machine));
        assertNull("Credentials for H1_03 should be null.", netrc.getCredentials(TestHost.H1_03.machine));
        assertNull("Credentials for H1_04 should be null.", netrc.getCredentials(TestHost.H1_04.machine));
    }

}
