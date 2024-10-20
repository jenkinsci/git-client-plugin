package org.jenkinsci.plugins.gitclient;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.plugins.git.GitTool;
import hudson.tools.ToolProperty;
import java.io.Serial;
import java.util.Collections;
import java.util.List;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * JGit as {@link hudson.plugins.git.GitTool}
 *
 * @author Kohsuke Kawaguchi
 */
public class JGitTool extends GitTool {
    @Serial
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
        this(Collections.emptyList());
    }

    /** {@inheritDoc} */
    @Override
    public GitTool.DescriptorImpl getDescriptor() {
        return super.getDescriptor();
    }

    @Extension
    @Symbol("jgit")
    public static class DescriptorImpl extends GitTool.DescriptorImpl {
        @NonNull
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
