package hudson.plugins.git;

import hudson.model.EnvironmentSpecific;
import hudson.model.Items;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolProperty;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.gitclient.CLIGitTool;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Information about Git installation. A GitTool is used to select
 * between different installations of git, as in "git" or "jgit".
 *
 * @author Jyrki Puttonen
 */
public abstract class GitTool extends ToolInstallation implements NodeSpecific<GitTool>, EnvironmentSpecific<GitTool> {

    /**
     * Constructor for GitTool.
     *
     * @param name Tool name (for example, "git" or "jgit")
     * @param home Tool location (usually "git")
     * @param properties {@link java.util.List} of properties for this tool
     */
    public GitTool(String name, String home, List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    /** Constant <code>DEFAULT="Default"</code> */
    public static transient final String DEFAULT = "Default";

    private static final long serialVersionUID = 1;

    /**
     * getGitExe.
     *
     * @return {@link java.lang.String} that will be used to execute git (e.g. "git" or "/usr/bin/git")
     */
    public String getGitExe() {
        return getHome();
    }

    /**
     * Returns the default installation.
     *
     * @return default installation
     * @deprecated see {@link CLIGitTool#getDefaultInstallation()}
     */
    public static GitTool getDefaultInstallation() {
        return CLIGitTool.getDefaultInstallation();
    }


    public GitTool.DescriptorImpl getDescriptor() {
        return (GitTool.DescriptorImpl) super.getDescriptor();
    }

    public static abstract class DescriptorImpl extends ToolDescriptor<GitTool> {

        public GitTool getInstallation(String name) {
            for(GitTool i : getInstallations()) {
                if(i.getName().equals(name)) {
                    return i;
                }
            }
            return null;
        }


        /**
         * Misspelled method name. Please use #getApplicableDescriptors.
         * @return list of applicable GitTool descriptors
         * @deprecated
         */
        @Deprecated
        public List<ToolDescriptor<? extends GitTool>> getApplicableDesccriptors() {
            return getApplicableDescriptors();
        }

        /**
         * Return list of applicable GitTool descriptors.
         * @return list of applicable GitTool descriptors
         */
        @SuppressWarnings("unchecked")
        public List<ToolDescriptor<? extends GitTool>> getApplicableDescriptors() {
            List<ToolDescriptor<? extends GitTool>> r = new ArrayList<>();
            Jenkins jenkinsInstance = Jenkins.get();
            for (ToolDescriptor<?> td : jenkinsInstance.<ToolInstallation,ToolDescriptor<?>>getDescriptorList(ToolInstallation.class)) {
                if (GitTool.class.isAssignableFrom(td.clazz)) { // This checks cast is allowed
                    r.add((ToolDescriptor<? extends GitTool>)td); // This is the unchecked cast
                }
            }
            return r;
        }
    }

    static {
        Items.XSTREAM2.aliasType(GitTool.class.getName(), CLIGitTool.class);
    }

    @Deprecated
    public static void onLoaded() {
        CLIGitTool.onLoaded();
    }
}

