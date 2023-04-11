package org.jenkinsci.plugins.gitclient;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.plugins.git.GitTool;
import hudson.tools.ToolProperty;
import java.util.Collections;
import java.util.List;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

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
        this(Collections.emptyList());
    }

    /** {@inheritDoc} */
    @Override
    public GitTool.DescriptorImpl getDescriptor() {
        return super.getDescriptor();
    }

    @Extension
    @Symbol(MAGIC_EXENAME)
    public static class DescriptorImpl extends GitTool.DescriptorImpl {
        @NonNull
        @Override
        public String getDisplayName() {
            return "JGit with Apache HTTP client";
        }
    }

    /**
     * {@link Git} recognizes this as a magic executable name to use {@link JGitAPIImpl}.
     */
    public static final String MAGIC_EXENAME = "jgitapache";
}
