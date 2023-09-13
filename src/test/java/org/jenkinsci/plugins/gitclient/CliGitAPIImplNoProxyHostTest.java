package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.ProxyConfiguration;
import hudson.model.TaskListener;
import java.io.File;
import java.lang.reflect.Field;
import junit.framework.TestCase;
import org.junit.Test;
import org.objenesis.ObjenesisStd;

/**
 * Test that checks all the no proxy hosts are added or not.
 */
public class CliGitAPIImplNoProxyHostTest extends TestCase {

    private CliGitAPIImpl cliGit;

    @Test
    public void test_no_proxy_host_is_set_correctly() throws NoSuchFieldException, IllegalAccessException {
        cliGit = new CliGitAPIImpl("git", new File("."), TaskListener.NULL, new EnvVars());

        final String proxyHost = "172.16.1.13";
        final String proxyPort = "3128";
        final String proxyUser = null;
        final String noProxyHosts = "169.254.169.254";

        ProxyConfiguration proxyConfig = new ObjenesisStd().newInstance(ProxyConfiguration.class);

        setField(ProxyConfiguration.class, "name", proxyConfig, proxyHost);
        setField(ProxyConfiguration.class, "port", proxyConfig, Integer.parseInt(proxyPort));
        setField(ProxyConfiguration.class, "userName", proxyConfig, null);
        setField(ProxyConfiguration.class, "noProxyHost", proxyConfig, noProxyHosts);
        setField(ProxyConfiguration.class, "password", proxyConfig, null);
        setField(ProxyConfiguration.class, "secretPassword", proxyConfig, null);

        cliGit.setProxy(proxyConfig);
        assertEquals("NO_PROXY hosts are not set correctly", noProxyHosts, cliGit.getNoProxyHosts());
    }

    private void setField(Class<?> clazz, String fieldName, Object object, Object value)
            throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
        Field declaredField = clazz.getDeclaredField(fieldName);
        declaredField.setAccessible(true);
        declaredField.set(object, value);
    }
}
