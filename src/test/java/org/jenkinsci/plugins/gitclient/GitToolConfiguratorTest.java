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
import hudson.tools.ToolProperty;
import io.jenkins.plugins.casc.Attribute;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.model.CNode;
import io.jenkins.plugins.casc.model.Mapping;
import java.util.List;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GitToolConfiguratorTest {

    private final GitToolConfigurator gitToolConfigurator;
    private static final ConfigurationContext NULL_CONFIGURATION_CONTEXT = null;

    public GitToolConfiguratorTest() {
        gitToolConfigurator = new GitToolConfigurator();
    }

    @Test
    public void testGetName() {
        assertThat(gitToolConfigurator.getName(), is("git"));
    }

    @Test
    public void testGetDisplayName() {
        assertThat(gitToolConfigurator.getDisplayName(), is("Git"));
    }

    @Test
    public void testGetTarget() {
        assertEquals("Wrong target class", gitToolConfigurator.getTarget(), GitTool.class);
    }

    @Test
    public void testCanConfigure() {
        assertTrue("Can't configure GitTool", gitToolConfigurator.canConfigure(GitTool.class));
        assertFalse("Can configure GitToolConfigurator", gitToolConfigurator.canConfigure(GitToolConfigurator.class));
    }

    @Test
    public void testGetImplementedAPI() {
        assertEquals("Wrong implemented API", gitToolConfigurator.getImplementedAPI(), GitTool.class);
    }

    @Test
    public void testGetConfigurators() {
        assertThat(gitToolConfigurator.getConfigurators(NULL_CONFIGURATION_CONTEXT), contains(gitToolConfigurator));
    }

    @Test
    public void testDescribe() throws Exception {
        GitTool nullGitTool = null;
        assertThat(gitToolConfigurator.describe(nullGitTool, NULL_CONFIGURATION_CONTEXT), is(new Mapping()));
    }

    @Test
    public void testDescribeJGitTool() throws Exception {
        GitTool gitTool = new JGitTool();
        CNode cNode = gitToolConfigurator.describe(gitTool, NULL_CONFIGURATION_CONTEXT);
        assertThat(cNode, is(notNullValue()));
        assertThat(cNode.getType(), is(CNode.Type.MAPPING));
        Mapping cNodeMapping = cNode.asMapping();
        assertThat(cNodeMapping.getScalarValue("name"), is(JGitTool.MAGIC_EXENAME));
    }

    @Test
    public void testDescribeJGitApacheTool() throws Exception {
        GitTool gitTool = new JGitApacheTool();
        CNode cNode = gitToolConfigurator.describe(gitTool, NULL_CONFIGURATION_CONTEXT);
        assertThat(cNode, is(notNullValue()));
        assertThat(cNode.getType(), is(CNode.Type.MAPPING));
        Mapping cNodeMapping = cNode.asMapping();
        assertThat(cNodeMapping.getScalarValue("name"), is(JGitApacheTool.MAGIC_EXENAME));
    }

    @Test
    public void testDescribeGitToolWithoutProperties() throws Exception {
        String gitName = "git-name";
        String gitHome = "/opt/git-2.23.0/bin/git";
        GitTool gitTool = new GitTool(gitName, gitHome, null);
        CNode cNode = gitToolConfigurator.describe(gitTool, NULL_CONFIGURATION_CONTEXT);
        assertThat(cNode, is(notNullValue()));
        assertThat(cNode.getType(), is(CNode.Type.MAPPING));
        Mapping cNodeMapping = cNode.asMapping();
        assertThat(cNodeMapping.getScalarValue("name"), is(gitName));
        assertThat(cNodeMapping.getScalarValue("home"), is(gitHome));
    }

    @Test
    public void testInstance() throws Exception {
        Mapping mapping = null;
        ConfigurationContext context = new ConfigurationContext(null);
        GitTool gitTool = gitToolConfigurator.instance(mapping, context);
        assertThat(gitTool, is(instanceOf(GitTool.class)));
        assertThat(gitTool.getName(), is("Default"));
        assertThat(gitTool.getHome(), is(""));
    }

    @Test
    public void testInstanceJGitTool() throws Exception {
        Mapping mapping = new Mapping();
        mapping.put("name", JGitTool.MAGIC_EXENAME);
        ConfigurationContext context = new ConfigurationContext(null);
        GitTool gitTool = gitToolConfigurator.instance(mapping, context);
        assertThat(gitTool, is(instanceOf(JGitTool.class)));
        assertThat(gitTool, is(not(instanceOf(JGitApacheTool.class))));
    }

    @Test
    public void testInstanceJGitToolWithHome() throws Exception {
        Mapping mapping = new Mapping();
        mapping.put("name", JGitTool.MAGIC_EXENAME);
        mapping.put("home", "unused-value-for-home"); // Will log a message
        ConfigurationContext context = new ConfigurationContext(null);
        GitTool gitTool = gitToolConfigurator.instance(mapping, context);
        assertThat(gitTool, is(instanceOf(JGitTool.class)));
        assertThat(gitTool, is(not(instanceOf(JGitApacheTool.class))));
    }

    @Test
    public void testInstanceJGitApacheTool() throws Exception {
        Mapping mapping = new Mapping();
        mapping.put("name", JGitApacheTool.MAGIC_EXENAME);
        ConfigurationContext context = new ConfigurationContext(null);
        GitTool gitTool = gitToolConfigurator.instance(mapping, context);
        assertThat(gitTool, is(instanceOf(JGitApacheTool.class)));
        assertThat(gitTool, is(not(instanceOf(JGitTool.class))));
    }

    @Test
    public void testInstanceJGitApacheToolWithHome() throws Exception {
        Mapping mapping = new Mapping();
        mapping.put("name", JGitApacheTool.MAGIC_EXENAME);
        mapping.put("home", "unused-value-for-home"); // Will log a message
        ConfigurationContext context = new ConfigurationContext(null);
        GitTool gitTool = gitToolConfigurator.instance(mapping, context);
        assertThat(gitTool, is(instanceOf(JGitApacheTool.class)));
        assertThat(gitTool, is(not(instanceOf(JGitTool.class))));
    }

    @Test(expected = ConfiguratorException.class)
    public void testInstanceGitToolWithoutHome() throws Exception {
        Mapping mapping = new Mapping();
        mapping.put("name", "testGitName"); // No home mapping defined
        ConfigurationContext context = new ConfigurationContext(null);
        gitToolConfigurator.instance(mapping, context);
    }

    @Test
    public void testInstanceGitTool() throws Exception {
        Mapping mapping = new Mapping();
        String gitHome = "testGitHome";
        String gitName = "testGitName";
        mapping.put("home", gitHome);
        mapping.put("name", gitName);
        ConfigurationContext context = new ConfigurationContext(null);
        GitTool gitTool = gitToolConfigurator.instance(mapping, context);
        assertThat(gitTool, is(instanceOf(GitTool.class)));
        assertThat(gitTool, is(not(instanceOf(JGitTool.class))));
        assertThat(gitTool, is(not(instanceOf(JGitApacheTool.class))));
        assertThat(gitTool.getHome(), is(gitHome));
        assertThat(gitTool.getName(), is(gitName));
    }

    @Test
    public void testGetAttributes() {
        List<Attribute<GitTool, ?>> gitToolAttributes = gitToolConfigurator.getAttributes();
        Attribute<GitTool, String> name = new Attribute<>("name", String.class);
        Attribute<GitTool, String> home = new Attribute<>("home", String.class);
        Attribute<GitTool, ToolProperty> p = new Attribute<>("properties", ToolProperty.class);
        assertThat(gitToolAttributes, containsInAnyOrder(name, home, p));
    }
}
