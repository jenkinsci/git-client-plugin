package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.git.GitTool;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolProperty;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * JGit, configured with the Apache HTTP Client, as {@link hudson.plugins.git.GitTool}
 */
public class JGitApacheTool extends GitTool {
    private static final long serialVersionUID = 1L;

    @DataBoundConstructor
    public JGitApacheTool(final List<? extends ToolProperty<?>> properties) {
        super(MAGIC_EXENAME, MAGIC_EXENAME, properties);
    }

    public JGitApacheTool() {
        this(Collections.<ToolProperty<?>>emptyList());
    }

    @Extension @Symbol(MAGIC_EXENAME)
    public static class DescriptorImpl extends GitTool.DescriptorImpl {
        @Override
        public String getDisplayName() {
            return "JGit with Apache HTTP client";
        }
    }

    public GitTool forNode(Node node, TaskListener log) throws IOException, InterruptedException {
        return this;
    }

    public GitTool forEnvironment(EnvVars environment) {
        return this;
    }

    /**
     * {@link Git} recognizes this as a magic executable name to use {@link JGitAPIImpl}.
     */
    public static final String MAGIC_EXENAME = "jgitapache";

}
