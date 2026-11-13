package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.Proc;
import hudson.ProxyConfiguration;
import hudson.model.TaskListener;
import java.io.File;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for exporting no_proxy and NO_PROXY environment variables
 * in command-line Git operations when using a proxy.
 *
 * @see <a href="https://issues.jenkins.io/browse/JENKINS-61193">JENKINS-61193</a>
 */
class CliGitAPIImplNoProxyHostTest {

    private CliGitAPIImpl cliGit;

    @BeforeEach
    void setUp() {
        cliGit = new CliGitAPIImpl("git", new File("."), TaskListener.NULL, new EnvVars());
    }

    @Test
    void testGetNoProxyHostsDefaultWhenNoProxyConfigured() {
        assertThat(cliGit.getNoProxyHosts(), is(""));
    }

    @Test
    void testGetNoProxyHostsWhenNoProxyHostIsNull() {
        ProxyConfiguration proxyConfig = new ProxyConfiguration("proxy.example.com", 8080, null, null, null);
        cliGit.setProxy(proxyConfig);
        assertThat(cliGit.getNoProxyHosts(), is(""));
    }

    @Test
    void testGetNoProxyHostsWhenNoProxyHostIsBlank() {
        ProxyConfiguration proxyConfig = new ProxyConfiguration("proxy.example.com", 8080, null, null, "   ");
        cliGit.setProxy(proxyConfig);
        assertThat(cliGit.getNoProxyHosts(), is(""));
    }

    @Test
    void testGetNoProxyHostsSingleHost() {
        ProxyConfiguration proxyConfig =
                new ProxyConfiguration("proxy.example.com", 8080, null, null, "169.254.169.254");
        cliGit.setProxy(proxyConfig);
        assertThat(cliGit.getNoProxyHosts(), is("169.254.169.254"));
    }

    @Test
    void testGetNoProxyHostsCommaSeparated() {
        ProxyConfiguration proxyConfig =
                new ProxyConfiguration("proxy.example.com", 8080, null, null, "localhost,127.0.0.1,169.254.169.254");
        cliGit.setProxy(proxyConfig);
        assertThat(cliGit.getNoProxyHosts(), is("localhost,127.0.0.1,169.254.169.254"));
    }

    @Test
    void testGetNoProxyHostsSpaceSeparated() {
        ProxyConfiguration proxyConfig =
                new ProxyConfiguration("proxy.example.com", 8080, null, null, "localhost 127.0.0.1 169.254.169.254");
        cliGit.setProxy(proxyConfig);
        assertThat(cliGit.getNoProxyHosts(), is("localhost,127.0.0.1,169.254.169.254"));
    }

    @Test
    void testGetNoProxyHostsNewlineSeparated() {
        ProxyConfiguration proxyConfig =
                new ProxyConfiguration("proxy.example.com", 8080, null, null, "localhost\n127.0.0.1\n169.254.169.254");
        cliGit.setProxy(proxyConfig);
        assertThat(cliGit.getNoProxyHosts(), is("localhost,127.0.0.1,169.254.169.254"));
    }

    @Test
    void testGetNoProxyHostsPipeSeparated() {
        ProxyConfiguration proxyConfig =
                new ProxyConfiguration("proxy.example.com", 8080, null, null, "localhost|127.0.0.1|169.254.169.254");
        cliGit.setProxy(proxyConfig);
        assertThat(cliGit.getNoProxyHosts(), is("localhost,127.0.0.1,169.254.169.254"));
    }

    @Test
    void testGetNoProxyHostsMixedDelimitersWithWhitespace() {
        ProxyConfiguration proxyConfig = new ProxyConfiguration(
                "proxy.example.com", 8080, null, null, "  localhost, \n\t 127.0.0.1 | 169.254.169.254 , *.internal  ");
        cliGit.setProxy(proxyConfig);
        assertThat(cliGit.getNoProxyHosts(), is("localhost,127.0.0.1,169.254.169.254,*.internal"));
    }

    @Test
    void testExportNoProxyEnvironmentVariablesWhenProxyConfigured() throws Exception {
        ProxyConfiguration proxyConfig =
                new ProxyConfiguration("proxy.example.com", 8080, null, null, "localhost\n127.0.0.1\n169.254.169.254");
        cliGit.setProxy(proxyConfig);

        Launcher launcher = mock(Launcher.class);
        Launcher.ProcStarter procStarter = mock(Launcher.ProcStarter.class);
        Proc proc = mock(Proc.class);

        when(launcher.launch()).thenReturn(procStarter);
        when(procStarter.cmds(any(String[].class))).thenReturn(procStarter);
        when(procStarter.envs(any(Map.class))).thenReturn(procStarter);
        when(procStarter.stdout(any(OutputStream.class))).thenReturn(procStarter);
        when(procStarter.stderr(any(OutputStream.class))).thenReturn(procStarter);
        when(procStarter.start()).thenReturn(proc);
        when(proc.joinWithTimeout(anyLong(), any(TimeUnit.class), any(TaskListener.class)))
                .thenReturn(0);

        cliGit.launcher = launcher;

        cliGit.getRemoteReferences("http://git.example.com/repo.git", null, false, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(procStarter, atLeastOnce()).envs(envCaptor.capture());

        Map<String, String> capturedEnv =
                envCaptor.getAllValues().get(envCaptor.getAllValues().size() - 1);
        assertThat(capturedEnv.get("http_proxy"), is("http://proxy.example.com:8080"));
        assertThat(capturedEnv.get("https_proxy"), is("http://proxy.example.com:8080"));
        assertThat(capturedEnv.get("no_proxy"), is("localhost,127.0.0.1,169.254.169.254"));
        assertThat(capturedEnv.get("NO_PROXY"), is("localhost,127.0.0.1,169.254.169.254"));
    }

    @Test
    void testNoProxyEnvironmentVariablesOmittedWhenUrlMatchesNoProxyList() throws Exception {
        ProxyConfiguration proxyConfig =
                new ProxyConfiguration("proxy.example.com", 8080, null, null, "localhost\n127.0.0.1\n169.254.169.254");
        cliGit.setProxy(proxyConfig);

        Launcher launcher = mock(Launcher.class);
        Launcher.ProcStarter procStarter = mock(Launcher.ProcStarter.class);
        Proc proc = mock(Proc.class);

        when(launcher.launch()).thenReturn(procStarter);
        when(procStarter.cmds(any(String[].class))).thenReturn(procStarter);
        when(procStarter.envs(any(Map.class))).thenReturn(procStarter);
        when(procStarter.stdout(any(OutputStream.class))).thenReturn(procStarter);
        when(procStarter.stderr(any(OutputStream.class))).thenReturn(procStarter);
        when(procStarter.start()).thenReturn(proc);
        when(proc.joinWithTimeout(anyLong(), any(TimeUnit.class), any(TaskListener.class)))
                .thenReturn(0);

        cliGit.launcher = launcher;

        cliGit.getRemoteReferences("http://169.254.169.254/repo.git", null, false, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(procStarter, atLeastOnce()).envs(envCaptor.capture());

        Map<String, String> capturedEnv =
                envCaptor.getAllValues().get(envCaptor.getAllValues().size() - 1);
        assertThat(capturedEnv.get("http_proxy"), is(nullValue()));
        assertThat(capturedEnv.get("https_proxy"), is(nullValue()));
        assertThat(capturedEnv.get("no_proxy"), is(nullValue()));
        assertThat(capturedEnv.get("NO_PROXY"), is(nullValue()));
    }

    @Test
    void testNoProxyEnvironmentVariablesOmittedWhenNoProxyHostNotConfigured() throws Exception {
        ProxyConfiguration proxyConfig = new ProxyConfiguration("proxy.example.com", 8080, null, null, null);
        cliGit.setProxy(proxyConfig);

        Launcher launcher = mock(Launcher.class);
        Launcher.ProcStarter procStarter = mock(Launcher.ProcStarter.class);
        Proc proc = mock(Proc.class);

        when(launcher.launch()).thenReturn(procStarter);
        when(procStarter.cmds(any(String[].class))).thenReturn(procStarter);
        when(procStarter.envs(any(Map.class))).thenReturn(procStarter);
        when(procStarter.stdout(any(OutputStream.class))).thenReturn(procStarter);
        when(procStarter.stderr(any(OutputStream.class))).thenReturn(procStarter);
        when(procStarter.start()).thenReturn(proc);
        when(proc.joinWithTimeout(anyLong(), any(TimeUnit.class), any(TaskListener.class)))
                .thenReturn(0);

        cliGit.launcher = launcher;

        cliGit.getRemoteReferences("http://git.example.com/repo.git", null, false, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(procStarter, atLeastOnce()).envs(envCaptor.capture());

        Map<String, String> capturedEnv =
                envCaptor.getAllValues().get(envCaptor.getAllValues().size() - 1);
        assertThat(capturedEnv.get("http_proxy"), is("http://proxy.example.com:8080"));
        assertThat(capturedEnv.get("https_proxy"), is("http://proxy.example.com:8080"));
        assertThat(capturedEnv.get("no_proxy"), is(nullValue()));
        assertThat(capturedEnv.get("NO_PROXY"), is(nullValue()));
    }
}
