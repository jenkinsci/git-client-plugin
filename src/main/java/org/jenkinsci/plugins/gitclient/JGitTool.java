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
 * JGit as {@link hudson.plugins.git.GitTool}
 *
 * @author Kohsuke Kawaguchi
 */
public class JGitTool extends GitTool {
    private static final long serialVersionUID = 1L;

    /**
     * Constructor for JGitTool.
     *
     * @param properties a {@link java.util.List} object.
     */
    @DataBoundConstructor
    public JGitTool(List<? extends ToolProperty<?>> properties) {
        super("jgit", MAGIC_EXENAME, properties);
    }

    /**
     * Constructor for JGitTool.
     */
    public JGitTool() {
        this(Collections.<ToolProperty<?>>emptyList());
    }

    public GitTool forNode(Node node, TaskListener log) throws IOException, InterruptedException {
        return this;
    }

    public GitTool forEnvironment(EnvVars environment) {
        return this;
    }


    @Extension @Symbol("jgit")
    public static class DescriptorImpl extends GitTool.DescriptorImpl {
        @Override
        public String getDisplayName() {
            return "JGit";
        }
    }

    /**
     * {@link Git} recognizes this as a magic executable name to use {@link JGitAPIImpl}.
     */
    public static final String MAGIC_EXENAME = "jgit";
}
