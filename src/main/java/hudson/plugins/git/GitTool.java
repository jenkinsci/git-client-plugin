package hudson.plugins.git;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Functions;
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
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static hudson.init.InitMilestone.PLUGINS_STARTED;
import java.util.logging.Level;

/**
 * Information about Git installation.
 *
 * @author Jyrki Puttonen
 */
public class GitTool extends ToolInstallation implements NodeSpecific<GitTool>, EnvironmentSpecific<GitTool> {

    @DataBoundConstructor
    public GitTool(String name, String home, List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    public static transient final String DEFAULT = "Default";

    private static final long serialVersionUID = 1;

    public String getGitExe() {
        return getHome();
    }

    private static GitTool[] getInstallations(DescriptorImpl descriptor) {
        GitTool[] installations = null;
        try {
            installations = descriptor.getInstallations();
        } catch (NullPointerException e) {
            installations = new GitTool[0];
        }
        return installations;
    }

    public static GitTool getDefaultInstallation() {
        DescriptorImpl gitTools = Jenkins.getInstance().getDescriptorByType(GitTool.DescriptorImpl.class);
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
        return new GitTool(getName(), translateFor(node, log), Collections.<ToolProperty<?>>emptyList());
    }

    public GitTool forEnvironment(EnvVars environment) {
        return new GitTool(getName(), environment.expand(getHome()), Collections.<ToolProperty<?>>emptyList());
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) Jenkins.getInstance().getDescriptorOrDie(getClass());
    }

    @Initializer(after=PLUGINS_STARTED)
    public static void onLoaded() {
        //Creates default tool installation if needed. Uses "git" or migrates data from previous versions

        DescriptorImpl descriptor = (DescriptorImpl) Jenkins.getInstance().getDescriptor(GitTool.class);
        GitTool[] installations = getInstallations(descriptor);

        if (installations != null && installations.length > 0) {
            //No need to initialize if there's already something
            return;
        }

        String defaultGitExe = Functions.isWindows() ? "git.exe" : "git";
        GitTool tool = new GitTool(DEFAULT, defaultGitExe, Collections.<ToolProperty<?>>emptyList());
        descriptor.setInstallations(new GitTool[] { tool });
        descriptor.save();
    }


    @Extension
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

        public FormValidation doCheckHome(@QueryParameter File value)
            throws IOException, ServletException {

            Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
            String path = value.getPath();

            return FormValidation.validateExecutable(path);

        }

        public GitTool getInstallation(String name) {
            for(GitTool i : getInstallations()) {
                if(i.getName().equals(name)) {
                    return i;
                }
            }
            if (name.length() > 0) {
                LOGGER.log(Level.WARNING, "invalid gitTool selection {0}", name);
            }
            return null;
        }

        public List<ToolDescriptor<? extends GitTool>> getApplicableDesccriptors() {
            List<ToolDescriptor<? extends GitTool>> r = new ArrayList<ToolDescriptor<? extends GitTool>>();
            for (ToolDescriptor td : Jenkins.getInstance().<ToolInstallation,ToolDescriptor<?>>getDescriptorList(ToolInstallation.class)) {
                if (GitTool.class.isAssignableFrom(td.clazz))
                    r.add(td);
            }
            return r;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(GitTool.class.getName());
}

