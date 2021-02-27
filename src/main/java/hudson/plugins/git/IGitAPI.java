package hudson.plugins.git;

import hudson.model.TaskListener;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RemoteConfig;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * IGitAPI interface.
 *
 * @deprecated methods here are deprecated until proven useful by a plugin
 */
@Deprecated
public interface IGitAPI extends GitClient {

    /**
     * Returns true if this repository has submodules.
     *
     * @param treeIsh an ignored argument, kept for compatibility
     * @return true if this repository has submodules (git modules file)
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     * @see GitClient#hasGitModules
     */
    boolean hasGitModules( String treeIsh ) throws GitException, InterruptedException;

    /**
     * Returns URL of remote name in repository GIT_DIR.
     *
     * @param name name for the remote repository, for examnple, "origin"
     * @param GIT_DIR directory containing git repository
     * @return URL of remote "name" in repository GIT_DIR.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    String getRemoteUrl(String name, String GIT_DIR) throws GitException, InterruptedException;

    /**
     * Set remote repository name and URL.
     *
     * @param name name for the remote repository, for examnple, "origin"
     * @param url URL for the remote repository, for example git://github.com/jenkinsci/git-client-plugin.git
     * @param GIT_DIR directory containing git repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void setRemoteUrl(String name, String url, String GIT_DIR) throws GitException, InterruptedException;

    /**
     * Returns name of default remote.
     *
     * @param _default_ value to return if no remote is defined in this repository
     * @return name of default remote
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    String getDefaultRemote( String _default_ ) throws GitException, InterruptedException;

    /**
     * Returns true if this repositry is bare.
     *
     * @return true if this repository is bare
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    boolean isBareRepository() throws GitException, InterruptedException;

    /**
     * Detect whether a repository at the given path is bare or not.
     *
     * @param GIT_DIR The path to the repository (must be to .git dir).
     * @throws hudson.plugins.git.GitException on failure
     * @throws java.lang.InterruptedException if interrupted
     * @return true if this repository is bare
     */
    boolean isBareRepository(String GIT_DIR) throws GitException, InterruptedException;

    /**
     * Synchronizes submodules' remote URL configuration setting to
     * the value specified in .gitmodules. Refer to git submodule sync
     * documentation for more details.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void submoduleSync() throws GitException, InterruptedException;

    /**
     * Returns URL of the named submodule.
     *
     * @param name submodule name whose URL will be returned
     * @return URL of the named submodule
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    String getSubmoduleUrl(String name) throws GitException, InterruptedException;

    /**
     * Sets URL of the named submodule.
     *
     * @param name submodule name whose URL will be set
     * @param url URL for the named submodule
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void setSubmoduleUrl(String name, String url) throws GitException, InterruptedException;

    /**
     * fixSubmoduleUrls.
     *
     * @param remote a {@link java.lang.String} object.
     * @param listener a {@link hudson.model.TaskListener} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void fixSubmoduleUrls( String remote, TaskListener listener ) throws GitException, InterruptedException;

    void setupSubmoduleUrls( String remote, TaskListener listener ) throws GitException, InterruptedException;

    /**
     * Retrieve commits based on refspec from repository.
     *
     * @param repository URL of the repository to be retrieved
     * @param refspec definition of mapping from remote refs to local refs
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void fetch(String repository, String refspec) throws GitException, InterruptedException;

    /**
     * Retrieve commits from RemoteConfig.
     *
     * @param remoteRepository remote configuration from which refs will be retrieved
     * @throws java.lang.InterruptedException if interrupted.
     */
    void fetch(RemoteConfig remoteRepository) throws InterruptedException;

    /**
     * Retrieve commits from default remote.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void fetch() throws GitException, InterruptedException;

    /**
     * Reset the contents of the working directory of this
     * repository. Refer to git reset documentation.
     *
     * @param hard reset as though "--hard" were passed to "git reset"
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void reset(boolean hard) throws GitException, InterruptedException;

    /**
     * Reset the contents of the working directory of this
     * repository. Refer to git reset documentation.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void reset() throws GitException, InterruptedException;

    /**
     * Push revspec to repository.
     * @param repository git repository to receive commits
     * @param revspec commits to be pushed
     * @throws GitException if underlying git operating fails
     * @throws InterruptedException if interrupted
     */
    void push(RemoteConfig repository, String revspec) throws GitException, InterruptedException;

    /**
     * Merge commits from revspec into the current branch.
     *
     * @param revSpec the revision specification to be merged (for example, origin/master)
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void merge(String revSpec) throws GitException, InterruptedException;

    /**
     * Clone repository from source to this repository.
     *
     * @param source remote repository to be cloned
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void clone(RemoteConfig source) throws GitException, InterruptedException;

    /**
     * Clone repository from {@link org.eclipse.jgit.transport.RemoteConfig} rc to this repository.
     *
     * @param rc the remote config for the remote repository
     * @param useShallowClone if true, use a shallow clone
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void clone(RemoteConfig rc, boolean useShallowClone) throws GitException, InterruptedException;

    /**
     * Find all the branches that include the given commit.
     * @deprecated Use {@link GitClient#getBranchesContaining(String, boolean)}
     *
     * @param revspec substring to be searched for branch name
     * @throws hudson.plugins.git.GitException on failure
     * @throws java.lang.InterruptedException if interrupted
     * @return list of branches containing revspec
     * @deprecated Use {@link org.jenkinsci.plugins.gitclient.GitClient#getBranchesContaining(String, boolean)}
     *             instead. This method does work only with local branches on
     *             one implementation and with all the branches - in the other
     */
    @Deprecated
    List<Branch> getBranchesContaining(String revspec) throws GitException, InterruptedException;

    /**
     * This method has been implemented as non-recursive historically, but
     * often that is not what the caller wants.
     *
     * @param treeIsh string representation of a treeIsh item
     * @throws hudson.plugins.git.GitException on failure
     * @throws java.lang.InterruptedException if interrupted
     * @return list of IndexEntry items starting at treeIsh
     *
     * @deprecated
     *  Use {@link #lsTree(String, boolean)} to be explicit about the recursion behaviour.
     */
    @Deprecated
    List<IndexEntry> lsTree(String treeIsh) throws GitException, InterruptedException;

    /**
     * lsTree.
     *
     * @param treeIsh a {@link java.lang.String} object.
     * @param recursive a boolean.
     * @return a {@link java.util.List} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    List<IndexEntry> lsTree(String treeIsh, boolean recursive) throws GitException, InterruptedException;

    /**
     * revListBranch.
     *
     * @param branchId a {@link java.lang.String} object.
     * @return a {@link java.util.List} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    List<ObjectId> revListBranch(String branchId) throws GitException, InterruptedException;

    /**
     * getTagsOnCommit.
     *
     * @param revName a {@link java.lang.String} object.
     * @return a {@link java.util.List} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.io.IOException if any IO failure
     * @throws java.lang.InterruptedException if interrupted.
     */
    List<Tag> getTagsOnCommit(String revName) throws GitException, IOException, InterruptedException;

    /** {@inheritDoc} */
    void changelog(String revFrom, String revTo, OutputStream fos) throws GitException, InterruptedException;

    /** {@inheritDoc} */
    void checkoutBranch(String branch, String commitish) throws GitException, InterruptedException;

    /**
     * mergeBase.
     *
     * @param sha1 a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @param sha2 a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @return a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @throws java.lang.InterruptedException if interrupted.
     */
    ObjectId mergeBase(ObjectId sha1, ObjectId sha2) throws InterruptedException;

    /**
     * showRevision.
     *
     * @param r a {@link hudson.plugins.git.Revision} object.
     * @return a {@link java.util.List} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    List<String> showRevision(Revision r) throws GitException, InterruptedException;

    /**
     * This method makes no sense, in that it lists all log entries across all refs and yet it
     * takes a meaningless 'branch' parameter. Please do not use this.
     *
     * @deprecated
     * @param branch a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Restricted(NoExternalUse.class)
    @Deprecated
    String getAllLogEntries(String branch) throws InterruptedException;
}
