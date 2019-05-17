package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.Extension;
import hudson.init.Initializer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.git.GitTool;
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static hudson.init.InitMilestone.EXTENSIONS_AUGMENTED;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CLIGitTool extends GitTool {

    /**
     * Constructor for CLIGitTool.
     *
     * @param name       Tool name (for example, "git")
     * @param home       Tool location ("/usr/local/bin/git")
     * @param properties {@link List} of properties for this tool
     */
    @DataBoundConstructor
    public CLIGitTool(String name, String home, List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    public GitTool forNode(Node node, TaskListener log) throws IOException, InterruptedException {
        return new CLIGitTool(getName(), translateFor(node, log), Collections.<ToolProperty<?>>emptyList());
    }

    public GitTool forEnvironment(EnvVars environment) {
        return new CLIGitTool(getName(), environment.expand(getHome()), Collections.<ToolProperty<?>>emptyList());
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
        GitTool tool = new CLIGitTool(DEFAULT, defaultGitExe, Collections.<ToolProperty<?>>emptyList());
        descriptor.setInstallations(new GitTool[] { tool });
        descriptor.save();
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

    public static GitTool getDefaultInstallation() {
        Jenkins jenkinsInstance = Jenkins.get();
        DescriptorImpl gitTools = jenkinsInstance.getDescriptorByType(DescriptorImpl.class);
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


    @Extension
    @Symbol("git")
    public static class DescriptorImpl extends GitTool.DescriptorImpl {

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

        public FormValidation doCheckHome(@QueryParameter File value) {
            Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
            String path = value.getPath();

            return FormValidation.validateExecutable(path);
        }
    }


    private static final Logger LOGGER = Logger.getLogger(GitTool.class.getName());

    /** inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue */
    private static boolean isWindows() {
        return File.pathSeparatorChar==';';
    }

}
