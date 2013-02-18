package hudson.plugins.git;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.gitclient.CliGitAPIImpl;

import java.io.File;

/**
 * Backward compatible class to match the one some plugins used to get from git-plugin
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 * @deprecated use either CliGitAPIImpl or JGitAPIImpl
 */
public class GitAPI extends CliGitAPIImpl {

    private final File repository;

    @Deprecated
    public GitAPI(String gitExe, FilePath repository, TaskListener listener, EnvVars environment) {
        this(gitExe, new File(repository.getRemote()), listener, environment);
    }

    @Deprecated
    public GitAPI(String gitExe, FilePath repository, TaskListener listener, EnvVars environment, String reference) {
        this(gitExe, repository, listener, environment);
    }

    public GitAPI(String gitExe, File repository, TaskListener listener, EnvVars environment) {
        super(gitExe, repository, listener, environment);
        this.repository = repository;
    }

}
