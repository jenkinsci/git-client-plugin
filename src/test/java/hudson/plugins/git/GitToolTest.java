package hudson.plugins.git;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.EnvVars;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.DumbSlave;
import hudson.tools.ToolDescriptor;
import hudson.util.StreamTaskListener;
import java.util.List;
import org.apache.commons.lang3.SystemUtils;
import org.jenkinsci.plugins.gitclient.JGitApacheTool;
import org.jenkinsci.plugins.gitclient.JGitTool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class GitToolTest {

    private static JenkinsRule r;

    private GitTool gitTool;

    @BeforeAll
    static void setUp(JenkinsRule rule) {
        r = rule;
    }

    @BeforeEach
    void setUp() {
        GitTool.onLoaded();
        gitTool = GitTool.getDefaultInstallation();
    }

    @Test
    void testGetGitExe() {
        assertEquals(SystemUtils.IS_OS_WINDOWS ? "git.exe" : "git", gitTool.getGitExe());
    }

    @Test
    void testForNode() throws Exception {
        DumbSlave agent = r.createSlave();
        agent.setMode(Node.Mode.EXCLUSIVE);
        TaskListener log = StreamTaskListener.fromStdout();
        GitTool newTool = gitTool.forNode(agent, log);
        assertEquals(gitTool.getGitExe(), newTool.getGitExe());
    }

    @Test
    void testForEnvironment() {
        EnvVars environment = new EnvVars();
        GitTool newTool = gitTool.forEnvironment(environment);
        assertEquals(gitTool.getGitExe(), newTool.getGitExe());
    }

    @Test
    void testGetDescriptor() {
        GitTool.DescriptorImpl descriptor = gitTool.getDescriptor();
        assertEquals("Git", descriptor.getDisplayName());
    }

    @Test
    void testGetInstallationFromDescriptor() {
        GitTool.DescriptorImpl descriptor = gitTool.getDescriptor();
        assertNull(descriptor.getInstallation(""));
        assertNull(descriptor.getInstallation("not-a-valid-git-install"));
    }

    @Test
    void testGetApplicableFromDescriptor() {
        GitTool.DescriptorImpl gitDescriptor = gitTool.getDescriptor();
        GitTool.DescriptorImpl jgitDescriptor = (new JGitTool()).getDescriptor();
        GitTool.DescriptorImpl jgitApacheDescriptor = (new JGitApacheTool()).getDescriptor();
        List<ToolDescriptor<? extends GitTool>> toolDescriptors = gitDescriptor.getApplicableDescriptors();
        assertThat(toolDescriptors, containsInAnyOrder(gitDescriptor, jgitDescriptor, jgitApacheDescriptor));
    }
}
