package org.jenkinsci.plugins.gitclient;

import org.jenkinsci.plugins.gitclient.verifier.AcceptFirstConnectionStrategy;
import org.jenkinsci.plugins.gitclient.verifier.KnownHostsFileVerificationStrategy;
import org.jenkinsci.plugins.gitclient.verifier.ManuallyProvidedKeyVerificationStrategy;
import org.jenkinsci.plugins.gitclient.verifier.NoHostKeyVerificationStrategy;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class GitHostKeyVerificationConfigurationTest {

    @Rule
    public RestartableJenkinsRule r = new RestartableJenkinsRule();

    private final GitHostKeyVerificationConfiguration gitHostKeyVerificationConfiguration;

    public GitHostKeyVerificationConfigurationTest() {
        gitHostKeyVerificationConfiguration = new GitHostKeyVerificationConfiguration();
    }

    @Test
    public void testGetSshHostKeyVerificationStrategyInitiallyAcceptFirst() {
        assertThat(gitHostKeyVerificationConfiguration.getSshHostKeyVerificationStrategy(), instanceOf(AcceptFirstConnectionStrategy.class));
    }

    @Test
    public void testGitHostKeyVerificationConfigurationSavedBetweenSessions() throws Exception {
        String hostKey = "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";
        ManuallyProvidedKeyVerificationStrategy manuallyProvidedKeyVerificationStrategy = new ManuallyProvidedKeyVerificationStrategy(hostKey);
        r.then(step -> {
            assertThat(GitHostKeyVerificationConfiguration.get().getSshHostKeyVerificationStrategy(), instanceOf(AcceptFirstConnectionStrategy.class));
            GitHostKeyVerificationConfiguration.get().setSshHostKeyVerificationStrategy(manuallyProvidedKeyVerificationStrategy);
        });

        r.then(step -> {
            assertThat(GitHostKeyVerificationConfiguration.get().getSshHostKeyVerificationStrategy(), is(manuallyProvidedKeyVerificationStrategy));
            GitHostKeyVerificationConfiguration.get().setSshHostKeyVerificationStrategy(new NoHostKeyVerificationStrategy());
        });

        r.then(step -> {
            assertThat(GitHostKeyVerificationConfiguration.get().getSshHostKeyVerificationStrategy(), instanceOf(NoHostKeyVerificationStrategy.class));
            GitHostKeyVerificationConfiguration.get().setSshHostKeyVerificationStrategy(new KnownHostsFileVerificationStrategy());
        });

        r.then(step -> assertThat(GitHostKeyVerificationConfiguration.get().getSshHostKeyVerificationStrategy(), instanceOf(KnownHostsFileVerificationStrategy.class)));
    }
}
