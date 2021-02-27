package hudson.plugins.git;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.tools.AbstractCommandInstaller;
import hudson.tools.BatchCommandInstaller;
import hudson.tools.CommandInstaller;
import hudson.tools.InstallSourceProperty;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

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
        final String label = "master";
        final String command = "echo Hello";
        final String toolHome = "TOOL_HOME";
        AbstractCommandInstaller installer = isWindows()
                ? new BatchCommandInstaller(label, command, toolHome)
                : new CommandInstaller(label, command, toolHome);
        GitTool t = new GitTool("myGit", null, Collections.singletonList(
                new InstallSourceProperty(Collections.singletonList(installer))));
        t.getDescriptor().setInstallations(t);

        GitTool defaultTool = GitTool.getDefaultInstallation();
        GitTool resolved = (GitTool) defaultTool.translate(j.jenkins, new EnvVars(), TaskListener.NULL);
        assertThat(resolved.getGitExe(), containsString(toolHome));
    }

    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
