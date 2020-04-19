package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.model.TaskListener;
import org.hamcrest.core.IsInstanceOf;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.hamcrest.MatcherAssert.assertThat;

public class GitJenkinsRuleTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void testMockClient() throws IOException, InterruptedException {
        System.setProperty(Git.class.getName() + ".mockClient", MyMockGitClient.class.getName());
        try {
            Git git = new Git(null, null).in(new File(".")).using("Hello World");
            final GitClient client = git.getClient();
            assertThat(client, IsInstanceOf.instanceOf(MyMockGitClient.class));
            MyMockGitClient c = (MyMockGitClient) client;
            assertEquals("Hello World", c.exe);
        } finally {
            System.clearProperty(Git.class.getName() + ".mockClient");
        }

    }

    public static class MyMockGitClient extends JGitAPIImpl {

        final String exe;
        final EnvVars env;

        public MyMockGitClient(String exe, EnvVars env, File workspace, TaskListener listener) {
            super(workspace, listener);
            this.exe = exe;
            this.env = env;
        }
    }
}
