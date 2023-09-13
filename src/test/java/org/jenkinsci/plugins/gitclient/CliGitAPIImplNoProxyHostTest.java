package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.ProxyConfiguration;
import hudson.model.TaskListener;
import java.io.File;
import junit.framework.TestCase;
import org.junit.Test;

/**
 * Test that checks all the no proxy hosts are added or not.
 */
public class CliGitAPIImplNoProxyHostTest extends TestCase {

    private CliGitAPIImpl cliGit;

    @Test
    public void test_no_proxy_host_is_set_correctly() throws NoSuchFieldException, IllegalAccessException {
        cliGit = new CliGitAPIImpl("git", new File("."), TaskListener.NULL, new EnvVars());

        final String proxyHost = "172.16.1.13";
        final int proxyPort = 3128;
        final String proxyUser = null;
        final String proxyPassword = null;
        final String noProxyHosts = "169.254.169.254";

        ProxyConfiguration proxyConfig =
                new ProxyConfiguration(proxyHost, proxyPort, proxyUser, proxyPassword, noProxyHosts);
        cliGit.setProxy(proxyConfig);
        assertEquals("NO_PROXY hosts are not set correctly", noProxyHosts, cliGit.getNoProxyHosts());
    }
}
