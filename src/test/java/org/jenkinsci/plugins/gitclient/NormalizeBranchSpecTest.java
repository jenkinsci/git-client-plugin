package org.jenkinsci.plugins.gitclient;

import static org.junit.Assert.assertEquals;
import hudson.EnvVars;
import hudson.plugins.git.GitException;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;

/**
 * This test class tests the behaviour of {@link LegacyCompatibleGitAPIImpl#normalizeBranchSpec(String)}.<br/>
 * See javadoc and comments in implementation for details about the behaviour. 
 */
public class NormalizeBranchSpecTest
{

    private static final String[] ORIGIN = { "origin" };
    private static final String[] REMOTES = { "origin", "rem2", "rem3.xy", "rem4/xy" };
    
    private CliGitAPIImpl mock;

    @Before
    public void before() throws GitException, InterruptedException {
        mock = PowerMockito.spy(new CliGitAPIImpl("git", null, null, new EnvVars()));
        Mockito.when(mock.getRemoteNames()).thenReturn(ORIGIN);
    }
    
    @Test
    public void normalizeBranchSpecReserved() throws GitException, InterruptedException {
        check("master", 
                    "[refs/heads/master, refs/headsmaster, master]");
        check("origin/master", 
                    "[refs/heads/master, refs/heads/origin/master, refs/headsorigin/master, origin/master]");
        check("remotes/origin/master", 
                    "[refs/heads/master, refs/heads/remotes/origin/master, refs/headsremotes/origin/master, remotes/origin/master]");
        check("refs/remotes/origin/master", 
                    "[refs/remotes/origin/master, refs/heads/refs/remotes/origin/master, refs/headsrefs/remotes/origin/master, refs/remotes/origin/master]");
        check("refs/heads/master", 
                    "[refs/heads/master, refs/heads/refs/heads/master, refs/headsrefs/heads/master, refs/heads/master]");
        check("refs/heads/refs/heads/master", 
                    "[refs/heads/refs/heads/master, refs/heads/refs/heads/refs/heads/master, refs/headsrefs/heads/refs/heads/master, refs/heads/refs/heads/master]");
        check("refs/heads/refs/heads/refs/heads/master", 
                    "[refs/heads/refs/heads/refs/heads/master, refs/heads/refs/heads/refs/heads/refs/heads/master, refs/headsrefs/heads/refs/heads/refs/heads/master, refs/heads/refs/heads/refs/heads/master]");
        check("refs/tags/master", 
                    "[refs/tags/master^{}, refs/tags/master, refs/heads/refs/tags/master, refs/headsrefs/tags/master, refs/tags/master]");
    }

    @Test
    public void testNormalizeBranchSpecWildcards() throws GitException, InterruptedException {
        check("*/master", 
                    "[refs/heads/*/master, refs/heads*/master, */master]");
        check("not-a-real-origin-but-allowed/*cov*", 
                    "[refs/heads/not-a-real-origin-but-allowed/*cov*, refs/headsnot-a-real-origin-but-allowed/*cov*, not-a-real-origin-but-allowed/*cov*]");
        check("yyzzy*/*er*", 
                    "[refs/heads/yyzzy*/*er*, refs/headsyyzzy*/*er*, yyzzy*/*er*]");
        check("X/re[mc]*o*e*", 
                    "[refs/heads/X/re[mc]*o*e*, refs/headsX/re[mc]*o*e*, X/re[mc]*o*e*]");
        check("N/*od*", 
                    "[refs/heads/N/*od*, refs/headsN/*od*, N/*od*]");
        check("origin/m*aste?", 
                    "[refs/heads/m*aste?, refs/heads/origin/m*aste?, refs/headsorigin/m*aste?, origin/m*aste?]");
    }
    
    @Test
    public void testNormalizeBranchSpecRemoteNames() throws GitException, InterruptedException {
        //All Remotes
        Mockito.when(mock.getRemoteNames()).thenReturn(REMOTES);
        check("origin/master", 
                    "[refs/heads/master, refs/heads/origin/master, refs/headsorigin/master, origin/master]");
        check("remotes/origin/master", 
                    "[refs/heads/master, refs/heads/remotes/origin/master, refs/headsremotes/origin/master, remotes/origin/master]");
        check("remotes/rem2/master", 
                    "[refs/heads/master, refs/heads/remotes/rem2/master, refs/headsremotes/rem2/master, remotes/rem2/master]");
        check("remotes/rem3.xy/master", 
                    "[refs/heads/master, refs/heads/remotes/rem3.xy/master, refs/headsremotes/rem3.xy/master, remotes/rem3.xy/master]");
        check("remotes/rem4/xy/master", 
                    "[refs/heads/master, refs/heads/remotes/rem4/xy/master, refs/headsremotes/rem4/xy/master, remotes/rem4/xy/master]");
        check("remotes/remNotThere/master", 
                    "[refs/heads/remotes/remNotThere/master, refs/headsremotes/remNotThere/master, remotes/remNotThere/master]");
        //Only Origin
        Mockito.when(mock.getRemoteNames()).thenReturn(ORIGIN);
        check("origin/master", 
                    "[refs/heads/master, refs/heads/origin/master, refs/headsorigin/master, origin/master]");
        check("remotes/origin/master", 
                    "[refs/heads/master, refs/heads/remotes/origin/master, refs/headsremotes/origin/master, remotes/origin/master]");
        check("remotes/rem2/master", 
                    "[refs/heads/remotes/rem2/master, refs/headsremotes/rem2/master, remotes/rem2/master]");
        check("remotes/rem3.xy/master", 
                    "[refs/heads/remotes/rem3.xy/master, refs/headsremotes/rem3.xy/master, remotes/rem3.xy/master]");
        check("remotes/rem4/xy/master", 
                    "[refs/heads/remotes/rem4/xy/master, refs/headsremotes/rem4/xy/master, remotes/rem4/xy/master]");
        check("remotes/remNotThere/master", 
                    "[refs/heads/remotes/remNotThere/master, refs/headsremotes/remNotThere/master, remotes/remNotThere/master]");
        //No remotes
        Mockito.when(mock.getRemoteNames()).thenReturn(new String[0]);
        check("origin/master", 
                    "[refs/heads/origin/master, refs/headsorigin/master, origin/master]");
        check("remotes/origin/master", 
                    "[refs/heads/remotes/origin/master, refs/headsremotes/origin/master, remotes/origin/master]");
    }    
    
    private void check(String branchSpec, String expectedResult) throws GitException, InterruptedException
    {
        String[] result = mock.normalizeBranchSpec(branchSpec);
        assertEquals("Unexpected result for '"+branchSpec+"'", expectedResult, Arrays.toString(result));
    }

}
