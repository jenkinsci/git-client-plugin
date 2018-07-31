package hudson.plugins.git;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.tools.CommandInstaller;
import hudson.tools.InstallSourceProperty;
import hudson.util.StreamTaskListener;
import java.io.IOException;
import java.util.Collections;
import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class GitToolResolverTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private GitTool gitTool;

    @Before
    public void setUp() throws IOException {
        GitTool.onLoaded();
        gitTool = GitTool.getDefaultInstallation();
    }

    @Test
    public void shouldResolveToolsOnMaster() throws Exception {
        TaskListener log = StreamTaskListener.fromStdout();
        GitTool t = new GitTool("myGit", null, Collections.singletonList(
                new InstallSourceProperty(Collections.singletonList(
                        new CommandInstaller("master", "echo Hello", "TOOL_HOME")
                ))));
        t.getDescriptor().setInstallations(t);

        GitTool defaultTool = GitTool.getDefaultInstallation();
        GitTool resolved = (GitTool) defaultTool.translate(j.jenkins, new EnvVars(), TaskListener.NULL);
        assertThat(resolved.getGitExe(), org.hamcrest.CoreMatchers.containsString("TOOL_HOME"));
    }
}
