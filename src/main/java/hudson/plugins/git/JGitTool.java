package hudson.plugins.git;

import hudson.Extension;
import hudson.tools.ToolProperty;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.JGitAPIImpl;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.List;

/**
 * JGit as {@link GitTool}
 *
 * @author Kohsuke Kawaguchi
 */
public class JGitTool extends GitTool {
    @DataBoundConstructor
    public JGitTool(List<? extends ToolProperty<?>> properties) {
        super("jgit", MAGIC_EXENAME, properties);
    }

    @Override
    public GitTool.DescriptorImpl getDescriptor() {
        return super.getDescriptor();
    }

    @Extension
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
