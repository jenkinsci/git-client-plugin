package hudson.plugins.git;

import hudson.EnvVars;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.DumbSlave;
import hudson.util.StreamTaskListener;
import java.io.IOException;
import org.apache.commons.lang.SystemUtils;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class GitToolTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    private GitTool gitTool;

    @Before
    public void setUp() throws IOException {
        GitTool.onLoaded();
        gitTool = GitTool.getDefaultInstallation();
    }

    @Test
    public void testGetGitExe() {
        assertEquals(SystemUtils.IS_OS_WINDOWS ? "git.exe" : "git", gitTool.getGitExe());
    }

    @Test
    public void testForNode() throws Exception {
        DumbSlave slave = j.createSlave();
        slave.setMode(Node.Mode.EXCLUSIVE);
        TaskListener log = StreamTaskListener.fromStdout();
        GitTool newTool = gitTool.forNode(slave, log);
        assertEquals(gitTool.getGitExe(), newTool.getGitExe());
    }

    @Test
    public void testForEnvironment() {
        EnvVars environment = new EnvVars();
        GitTool newTool = gitTool.forEnvironment(environment);
        assertEquals(gitTool.getGitExe(), newTool.getGitExe());
    }

    @Test
    public void testGetDescriptor() {
        GitTool.DescriptorImpl descriptor = gitTool.getDescriptor();
        assertEquals("Git", descriptor.getDisplayName());
    }

}
