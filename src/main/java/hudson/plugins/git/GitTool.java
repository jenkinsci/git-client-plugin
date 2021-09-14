package hudson.plugins.git;

import hudson.EnvVars;
import hudson.Extension;
import hudson.init.Initializer;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolProperty;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static hudson.init.InitMilestone.EXTENSIONS_AUGMENTED;

/**
 * Information about Git installation. A GitTool is used to select
 * between different installations of git, as in "git" or "jgit".
 *
 * @author Jyrki Puttonen
 */
public class GitTool extends ToolInstallation implements NodeSpecific<GitTool>, EnvironmentSpecific<GitTool> {

    /**
     * Constructor for GitTool.
     *
     * @param name Tool name (for example, "git" or "jgit")
     * @param home Tool location (usually "git")
     * @param properties {@link java.util.List} of properties for this tool
     */
    @DataBoundConstructor
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

    private static GitTool[] getInstallations(DescriptorImpl descriptor) {
        GitTool[] installations;
        try {
            installations = descriptor.getInstallations();
        } catch (NullPointerException e) {
            installations = new GitTool[0];
        }
        return installations;
    }

    /**
     * Returns the default installation.
     *
     * @return default installation
     */
    public static GitTool getDefaultInstallation() {
        Jenkins jenkinsInstance = Jenkins.get();
        DescriptorImpl gitTools = jenkinsInstance.getDescriptorByType(GitTool.DescriptorImpl.class);
        GitTool tool = gitTools.getInstallation(GitTool.DEFAULT);
        if (tool != null) {
            return tool;
        } else {
            GitTool[] installations = gitTools.getInstallations();
            if (installations.length > 0) {
                return installations[0];
            } else {
                onLoaded();
                return gitTools.getInstallations()[0];
            }
        }
    }

    public GitTool forNode(Node node, TaskListener log) throws IOException, InterruptedException {
        return new GitTool(getName(), translateFor(node, log), Collections.emptyList());
    }

    public GitTool forEnvironment(EnvVars environment) {
        return new GitTool(getName(), environment.expand(getHome()), Collections.emptyList());
    }

    @Override
    public DescriptorImpl getDescriptor() {
        Jenkins jenkinsInstance = Jenkins.getInstanceOrNull();
        if (jenkinsInstance == null) {
            /* Throw AssertionError exception to match behavior of Jenkins.getDescriptorOrDie */
            throw new AssertionError("No Jenkins instance");
        }
        return (DescriptorImpl) jenkinsInstance.getDescriptorOrDie(getClass());
    }

    @Initializer(after=EXTENSIONS_AUGMENTED)
    public static void onLoaded() {
        //Creates default tool installation if needed. Uses "git" or migrates data from previous versions

        Jenkins jenkinsInstance = Jenkins.get();
        DescriptorImpl descriptor = (DescriptorImpl) jenkinsInstance.getDescriptor(GitTool.class);
        GitTool[] installations = getInstallations(descriptor);

        if (installations != null && installations.length > 0) {
            //No need to initialize if there's already something
            return;
        }

        String defaultGitExe = isWindows() ? "git.exe" : "git";
        GitTool tool = new GitTool(DEFAULT, defaultGitExe, Collections.emptyList());
        descriptor.setInstallations(new GitTool[] { tool });
        descriptor.save();
    }


    @Extension @Symbol("git")
    public static class DescriptorImpl extends ToolDescriptor<GitTool> {

        public DescriptorImpl() {
            super();
            load();
        }

        @Override
        public String getDisplayName() {
            return "Git";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            setInstallations(req.bindJSONToList(clazz, json.get("tool")).toArray(new GitTool[0]));
            save();
            return true;
        }

        @RequirePOST
        public FormValidation doCheckHome(@QueryParameter File value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            String path = value.getPath();

            return FormValidation.validateExecutable(path);
        }

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

    private static final Logger LOGGER = Logger.getLogger(GitTool.class.getName());

    /** inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue */
    private static boolean isWindows() {
        return File.pathSeparatorChar==';';
    }
}

