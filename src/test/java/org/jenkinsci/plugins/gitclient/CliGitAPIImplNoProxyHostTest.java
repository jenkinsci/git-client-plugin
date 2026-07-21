package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

import hudson.EnvVars;
import hudson.ProxyConfiguration;
import hudson.model.TaskListener;
import java.io.File;
import org.junit.Before;
import org.junit.Test;

/**
 * Test that checks all the no proxy hosts are added or not.
 */
public class CliGitAPIImplNoProxyHostTest {

    private CliGitAPIImpl cliGit;

    @Before
    public void createCliGit() {
        cliGit = new CliGitAPIImpl("git", new File("."), TaskListener.NULL, new EnvVars());
    }

    @Test
    public void test_no_proxy_host_is_set_correctly() throws NoSuchFieldException, IllegalAccessException {

        final String proxyHost = "172.16.1.13";
        final int proxyPort = 3128;
        final String proxyUser = null;
        final String proxyPassword = null;
        final String noProxyHosts = "169.254.169.254";

        ProxyConfiguration proxyConfig =
                new ProxyConfiguration(proxyHost, proxyPort, proxyUser, proxyPassword, noProxyHosts);
        cliGit.setProxy(proxyConfig);
        assertThat(cliGit.getNoProxyHosts(), is(noProxyHosts));
    }

    @Test
    public void test_default_value() {
        assertThat(cliGit.getNoProxyHosts(), is(""));
    }
}
