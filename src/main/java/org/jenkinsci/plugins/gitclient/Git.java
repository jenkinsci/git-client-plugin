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
    private final TaskListener listener;
    private final EnvVars env;
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

    public Git using(String exe) {
        this.exe = exe;
        return this;
    }

    public GitClient getClient() {

        // For user/developer to be able to switch to JGit for testing purpose
        if (USE_JGIT) {
            return new JGitAPIImpl(exe, repository, listener, env);
        }
        // Ensure we return a backward compatible GitAPI
        return new GitAPI(exe, repository, listener, env);
    }

    static boolean USE_JGIT = Boolean.getBoolean(Git.class.getName() + ".useJGit");

}
