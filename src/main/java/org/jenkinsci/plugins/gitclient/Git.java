package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.model.TaskListener;
import hudson.plugins.git.GitAPI;
import hudson.remoting.VirtualChannel;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class Git implements Serializable {
    @Nullable
    private FilePath repository;
    private TaskListener listener;
    private EnvVars env;
    private String exe;

    public Git(TaskListener listener, EnvVars env) {
        this.listener = listener;
        this.env = env;
    }

    public static Git with(TaskListener listener, EnvVars env) {
        return new Git(listener, env);
    }

    public Git in(File repository) {
        return in(new FilePath(repository));
    }

    public Git in(FilePath repository) {
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

    public GitClient getClient() throws IOException, InterruptedException {
        FileCallable<GitClient> callable = new FileCallable<GitClient>() {
            public GitClient invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                if (listener == null) listener = TaskListener.NULL;
                if (env == null) env = new EnvVars();

                if (exe == null || JGitTool.MAGIC_EXENAME.equalsIgnoreCase(exe)) {
                    return new JGitAPIImpl(f, listener);
                }
                // Ensure we return a backward compatible GitAPI, even API only claim to provide a GitClient
                return new GitAPI(exe, f, listener, env);
            }
        };
        return repository!=null ? repository.act(callable) : callable.invoke(null,null);
    }

    // Can be use to force use of the 100% backward-compatible CLI GitClient
    public static boolean USE_CLI = Boolean.valueOf(System.getProperty(Git.class.getName() + ".useCLI", "true"));

    private static final long serialVersionUID = 1L;
}
