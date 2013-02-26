package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.Functions;
import hudson.model.TaskListener;
import hudson.plugins.git.GitAPI;

import java.io.File;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class Git {

    private File repository;
    private TaskListener listener;
    private EnvVars env;
    private String exe = Functions.isWindows() ? "git.exe" : "git";

    public Git(TaskListener listener, EnvVars env) {
        this.listener = listener;
        this.env = env;
    }

    public static Git with(TaskListener listener, EnvVars env) {
        return new Git(listener, env);
    }

    public Git in(File repository) {
        this.repository = repository;
        return this;
    }

    /**
     * Set the (node/environment specific) git executable to be used
     * If not set, JGit implementation will be used, assuming you don't rely on unimplemented CLI methods
     */
    public Git using(String exe) {
        this.exe = exe;
        return this;
    }

    public GitClient getClient() {
        if (listener == null) listener = TaskListener.NULL;
        if (env == null) env = new EnvVars();

        if (exe == null || USE_JGIT) {
            listener.getLogger().println("Using JGit client implementation");
            return new JGitAPIImpl(repository, listener);
        }
        // Ensure we return a backward compatible GitAPI
        return new GitAPI(exe, repository, listener, env);
    }

    // For user/developer to be able to switch to JGit for testing purpose
    static boolean USE_JGIT = Boolean.getBoolean(Git.class.getName() + ".useJGit");

}
