package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Main;
import hudson.model.TaskListener;
import hudson.plugins.git.GitAPI;
import hudson.remoting.VirtualChannel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.gitclient.jgit.PreemptiveAuthHttpClientConnectionFactory;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

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
public class Git implements Serializable {
    private FilePath repository;
    private TaskListener listener;
    private EnvVars env;
    private String exe;

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

    /**
     * {@link org.jenkinsci.plugins.gitclient.GitClient} implementation. The {@link org.jenkinsci.plugins.gitclient.GitClient} interface
     * provides the key operations which can be performed on a git repository.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.GitClient} for git operations on the repository
     * @throws java.io.IOException if any IO failure
     * @throws java.lang.InterruptedException if interrupted.
     */
    public GitClient getClient() throws IOException, InterruptedException {
        jenkins.MasterToSlaveFileCallable<GitClient> callable = new GitAPIMasterToSlaveFileCallable();
        GitClient git = (repository!=null ? repository.act(callable) : callable.invoke(null,null));
        Jenkins jenkinsInstance = Jenkins.getInstanceOrNull();
        if (jenkinsInstance != null && git != null)
            git.setProxy(jenkinsInstance.proxy);
        return git;
    }

    private GitClient initMockClient(String className, String exe, EnvVars env, File f, TaskListener listener) throws RuntimeException {
        try {
            final Class<?> it = Class.forName(className);
            final Constructor<?> constructor = it.getConstructor(String.class, EnvVars.class, File.class, TaskListener.class);
            return (GitClient)constructor.newInstance(exe, env, f, listener);
        } catch (ClassNotFoundException | IllegalAccessException | IllegalArgumentException | InstantiationException | NoSuchMethodException | SecurityException | InvocationTargetException e) {
            throw new RuntimeException("Unable to initialize mock GitClient " + className, e);
        }
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

    private class GitAPIMasterToSlaveFileCallable extends jenkins.MasterToSlaveFileCallable<GitClient> {
        public GitClient invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            if (listener == null) listener = TaskListener.NULL;
            if (env == null) env = new EnvVars();

            if (Main.isUnitTest && System.getProperty(Git.class.getName() + ".mockClient") != null) {
                return initMockClient(System.getProperty(Git.class.getName() + ".mockClient"),
                        exe, env, f, listener);
            }

            if (exe == null || JGitTool.MAGIC_EXENAME.equalsIgnoreCase(exe)) {
                return new JGitAPIImpl(f, listener);
            }

            if (JGitApacheTool.MAGIC_EXENAME.equalsIgnoreCase(exe)) {
                final PreemptiveAuthHttpClientConnectionFactory factory = new PreemptiveAuthHttpClientConnectionFactory();
                return new JGitAPIImpl(f, listener, factory);
            }
            // Ensure we return a backward compatible GitAPI, even API only claim to provide a GitClient
            return new GitAPI(exe, f, listener, env);
        }
    }
}
