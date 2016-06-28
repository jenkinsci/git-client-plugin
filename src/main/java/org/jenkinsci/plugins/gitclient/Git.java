package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.Launcher;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.git.GitAPI;
import hudson.remoting.VirtualChannel;
import jenkins.model.Jenkins;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;

/**
 * Git repository access class. Provides local and remote access to a git
 * repository through a {@link org.jenkinsci.plugins.gitclient.GitClient} implementation. Current git
 * implementations include either command line git ("git" -
 * {@link org.jenkinsci.plugins.gitclient.CliGitAPIImpl}) or JGit ("jgit" - {@link org.jenkinsci.plugins.gitclient.JGitAPIImpl}).
 *
 * The command line git implementation requires a separately installed git
 * program. The command line git implementation is the current reference
 * implementation.
 *
 * The JGit implementation is bundled entirely within the git client plugin and
 * does not require any external programs. The JGit implementation is not yet
 * functionally complete, though it handles most use cases.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class Git {
    @Nullable
    private FilePath repository;
    private TaskListener listener;
    private EnvVars env;
    private String exe;
    @Nullable
    private Launcher launcher;

    /**
     * Constructor for a Git object. Either <code>Git.with(listener, env)</code>
     * or <code>new Git(listener, env)</code> can be used to construct a Git
     * object.
     *
     * @param listener a {@link hudson.model.TaskListener} which can be used to
     * monitor git implementation operations
     * @param env a {@link hudson.EnvVars} which provides environment values to
     * the git implementation
     */
    public Git(TaskListener listener, EnvVars env) {
        this.listener = listener;
        this.env = env;
    }

    /**
     * Fluent constructor for a Git object. Either
     * <code>Git.with(listener, env)</code> or new
     * <code>Git(listener, env)</code> can be used to construct a Git object.
     *
     * @param listener a {@link hudson.model.TaskListener} which can be used to
     * monitor git implementation operations
     * @param env a {@link hudson.EnvVars} which provides environment values to
     * the git implementation
     * @return a {@link org.jenkinsci.plugins.gitclient.Git} object for repository access
     */
    public static Git with(TaskListener listener, EnvVars env) {
        return new Git(listener, env);
    }

    /**
     * Defines the local directory containing the git repository which will be
     * used. For repositories with a working directory, repository is the parent
     * of the <code>.git</code> directory. For bare repositories, repository is
     * the parent of the <code>objects</code> directory.
     *
     * @param repository {@link java.io.File} of the git repository
     * @return a {@link org.jenkinsci.plugins.gitclient.Git} object for repository access
     */
    public Git in(File repository) {
        return in(new FilePath(repository));
    }

    /**
     * Defines the {@link hudson.FilePath} (remotable directory) containing the
     * git repository which will be used. For repositories with a working
     * directory, repository is the parent of the <code>.git</code> directory.
     * For bare repositories, repository is the parent of the
     * <code>objects</code> directory.
     *
     * @param repository {@link hudson.FilePath} of the git repository.
     * @return a {@link org.jenkinsci.plugins.gitclient.Git} object for repository access
     */
    public Git in(FilePath repository) {
        this.repository = repository;
        return this;
    }

    /**
     * Set the (node/environment specific) git executable to be used. If not
     * set, JGit implementation will be used. When default is used, it assumes
     * the caller does not rely on unimplemented CLI methods.
     *
     * @param exe either "git" or "jgit"
     * @return {@link org.jenkinsci.plugins.gitclient.Git} object for repository access
     */
    public Git using(String exe) {
        this.exe = exe;
        return this;
    }

    public Git withLauncher(Launcher launcher) {
        this.launcher = launcher;
        return this;
    }

    /**
     * {@link org.jenkinsci.plugins.gitclient.GitClient} implementation. The {@link org.jenkinsci.plugins.gitclient.GitClient} interface
     * provides the key operations which can be performed on a git repository.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.GitClient} for git operations on the repository
     * @throws java.io.IOException if any IO failure
     * @throws java.lang.InterruptedException if interrupted.
     */
    public GitClient getClient() throws IOException, InterruptedException {
        if (listener == null) listener = TaskListener.NULL;
        if (env == null) env = new EnvVars();

        File f = repository != null ? new File(repository.getRemote()) : null;

        GitClient git;
        if (exe == null || JGitTool.MAGIC_EXENAME.equalsIgnoreCase(exe)) {
            git = new  JGitAPIImpl(f, listener);
        } else {
            // Ensure we return a backward compatible GitAPI, even API only claim to provide a GitClient
            git = new GitAPI(exe, f, listener, env, getLauncher());
        }

        Jenkins jenkinsInstance = Jenkins.getInstance();
        if (jenkinsInstance != null && git != null)
            git.setProxy(jenkinsInstance.proxy);
        return git;
    }

    /**
     * Retrieve the {@link Launcher} to be used to run git cli.
     */
    private @CheckForNull Launcher getLauncher() {
        if (this.launcher != null) {
            return launcher;
        }

        // Backward compatibility mode : let's try to retrieve the current Launcher
        Computer computer = null;
        if (repository != null) {
            computer = repository.toComputer();
        } else {
            final Executor e = Executor.currentExecutor();
            final Jenkins jenkins = Jenkins.getInstance();
            if (e != null) {
                computer = e.getOwner();
            } else if (jenkins != null) {
                // on master
                computer = jenkins.toComputer();
            }
        }

        if (computer != null) {
            final Node node = computer.getNode();
            if (node != null) {
                return  node.createLauncher(listener);
            }
        }
        return null;
    }

    /**
     * Constant which controls the default implementation to be used.
     *
     * <code>USE_CLI=Boolean.valueOf(System.getProperty(Git.class.getName() + ".useCLI", "true"))</code>.
     *
     * Uses command line implementation ({@link CliGitAPIImpl}) by default.
     */
    public static final boolean USE_CLI = Boolean.valueOf(System.getProperty(Git.class.getName() + ".useCLI", "true"));

        private static final long serialVersionUID = 1L;
}
