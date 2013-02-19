package hudson.plugins.git;

import java.io.File;

import org.jenkinsci.plugins.gitclient.CliGitAPIImpl;

import hudson.EnvVars;

import hudson.model.TaskListener;

/**
 * Backward compatible class to match the one some plugins used to get from git-plugin
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 * @deprecated use either CliGitAPIImpl or JGitAPIImpl
 */
public class GitAPI extends CliGitAPIImpl {
    public GitAPI(String gitExe, File workspace, TaskListener listener, EnvVars environment) {
        super(gitExe, workspace, listener, environment);
    }
}
