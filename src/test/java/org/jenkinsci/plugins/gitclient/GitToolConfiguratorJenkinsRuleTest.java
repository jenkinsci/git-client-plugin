/*
 * The MIT License
 *
 * Copyright 2019 Mark Waite.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.GitTool;
import hudson.tools.InstallSourceProperty;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolProperty;
import hudson.tools.ZipExtractionInstaller;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.model.CNode;
import io.jenkins.plugins.casc.model.Mapping;
import io.jenkins.plugins.casc.model.Sequence;
import java.util.ArrayList;
import java.util.List;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;

public class GitToolConfiguratorJenkinsRuleTest {

    private final GitToolConfigurator gitToolConfigurator;

    public GitToolConfiguratorJenkinsRuleTest() {
        gitToolConfigurator = new GitToolConfigurator();
    }

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void testDescribeGitToolEmptyProperties() throws Exception {
        String gitName = "git-2.19.1-name";
        String gitHome = "/opt/git-2.19.1/bin/git";

        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);

        // Intentionally empty properties passed to GitTool constructor
        List<ToolProperty<ToolInstallation>> gitToolProperties = new ArrayList<>();

        GitTool gitTool = new GitTool(gitName, gitHome, gitToolProperties);

        CNode cNode = gitToolConfigurator.describe(gitTool, context);

        assertThat(cNode, is(notNullValue()));
        assertThat(cNode.getType(), is(CNode.Type.MAPPING));
        Mapping cNodeMapping = cNode.asMapping();
        assertThat(cNodeMapping.getScalarValue("name"), is(gitName));
        assertThat(cNodeMapping.getScalarValue("home"), is(gitHome));
    }

    @Test
    public void testDescribeGitTool() throws Exception {
        String gitName = "git-2.19.1-name";
        String gitHome = "/opt/git-2.19.1/bin/git";

        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);

        List<ToolProperty<ToolInstallation>> gitToolProperties = new ArrayList<>();
        List<ToolInstaller> toolInstallers = new ArrayList<>();
        ToolInstaller toolInstaller = new ZipExtractionInstaller("tool-label", "tool-url", "tool-subdir");
        toolInstallers.add(toolInstaller);
        InstallSourceProperty installSourceProperty = new InstallSourceProperty(toolInstallers);
        gitToolProperties.add(installSourceProperty);

        GitTool gitTool = new GitTool(gitName, gitHome, gitToolProperties);

        CNode cNode = gitToolConfigurator.describe(gitTool, context);

        assertThat(cNode, is(notNullValue()));
        assertThat(cNode.getType(), is(CNode.Type.MAPPING));
        Mapping cNodeMapping = cNode.asMapping();
        assertThat(cNodeMapping.getScalarValue("name"), is(gitName));
        assertThat(cNodeMapping.getScalarValue("home"), is(gitHome));

        //         properties:
        //          - installSource:
        //              installers:
        //                - zip:
        //                    label: "tool-label"
        //                    subdir: "tool-subdir"
        //                    url: "tool-url"
        Sequence propertiesSequence = cNodeMapping.get("properties").asSequence();                             // properties:
        Mapping installSourceMapping = propertiesSequence.get(0).asMapping().get("installSource").asMapping(); //  - installSource:
        Sequence installersSequence = installSourceMapping.get("installers").asSequence();                     //      installers:
        Mapping zipMapping = installersSequence.get(0).asMapping().get("zip").asMapping();                     //        - zip:
        assertThat(zipMapping.getScalarValue("label"), is("tool-label"));
        assertThat(zipMapping.getScalarValue("subdir"), is("tool-subdir"));
        assertThat(zipMapping.getScalarValue("url"), is("tool-url"));
    }
}
