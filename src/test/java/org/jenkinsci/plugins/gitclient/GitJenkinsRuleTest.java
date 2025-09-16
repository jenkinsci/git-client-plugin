package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.EnvVars;
import hudson.model.TaskListener;
import java.io.File;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class GitJenkinsRuleTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void testMockClient() throws Exception {
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
