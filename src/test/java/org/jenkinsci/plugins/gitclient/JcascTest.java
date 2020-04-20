
package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.GitTool;
import hudson.tools.BatchCommandInstaller;
import hudson.tools.InstallSourceProperty;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolProperty;
import hudson.tools.ToolPropertyDescriptor;
import hudson.tools.ZipExtractionInstaller;
import hudson.util.DescribableList;
import io.jenkins.plugins.casc.misc.RoundTripAbstractTest;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

public class JcascTest extends RoundTripAbstractTest {
    @Override
    protected void assertConfiguredAsExpected(RestartableJenkinsRule restartableJenkinsRule, String s) {
        final ToolDescriptor descriptor = (ToolDescriptor) restartableJenkinsRule.j.jenkins.getDescriptor(GitTool.class);
        final ToolInstallation[] installations = descriptor.getInstallations();
        assertThat(installations, arrayWithSize(4));
        assertThat(installations, arrayContainingInAnyOrder(
                allOf(
                        instanceOf(JGitTool.class),
                        hasProperty("name", equalTo(JGitTool.MAGIC_EXENAME))
                ),
                allOf(
                        instanceOf(JGitApacheTool.class),
                        hasProperty("name", equalTo(JGitApacheTool.MAGIC_EXENAME))
                ),
                allOf(
                        instanceOf(GitTool.class),
                        not(instanceOf(JGitTool.class)),
                        not(instanceOf(JGitApacheTool.class)),
                        hasProperty("name", equalTo("Default")),
                        hasProperty("home", equalTo("git"))
                ),
                allOf(
                        instanceOf(GitTool.class),
                        not(instanceOf(JGitTool.class)),
                        not(instanceOf(JGitApacheTool.class)),
                        hasProperty("name", equalTo("optional")),
                        hasProperty("home", equalTo("/opt/git/git"))
                ))
        );
        final DescribableList<ToolProperty<?>, ToolPropertyDescriptor> properties = Arrays.stream(installations).filter(t -> t.getName().equals("optional")).findFirst().get().getProperties();
        assertThat(properties, iterableWithSize(1));
        final ToolProperty<?> property = properties.get(0);
        assertThat(property, instanceOf(InstallSourceProperty.class));
        assertThat(((InstallSourceProperty)property).installers,
                containsInAnyOrder(
                        allOf(
                                instanceOf(BatchCommandInstaller.class),
                                hasProperty("command", equalTo("echo \"got git\""))
                        ),
                        allOf(
                                instanceOf(ZipExtractionInstaller.class),
                                hasProperty("url", equalTo("file://some/path.zip"))
                        )
                )
        );
    }

    @Override
    protected String stringInLogExpected() {
        return "installations = [JGitTool[jgit], GitTool[Default], JGitApacheTool[jgitapache], GitTool[optional]]";
    }
}
