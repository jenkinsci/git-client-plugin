package org.jenkinsci.plugins.gitclient;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.model.TaskListener;
import hudson.plugins.git.*;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface to Git functionality.
 *
 * <p>
 * Since 1.1, this interface is remotable, meaning it can be referenced from a remote closure call.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public interface GitClient {

    /** Constant <code>verbose=Boolean.getBoolean(IGitAPI.class.getName() + ".verbose")</code> */
    boolean verbose = Boolean.getBoolean(IGitAPI.class.getName() + ".verbose");

    // If true, do not print the list of remote branches.
    /** Constant <code>quietRemoteBranches=Boolean.getBoolean(GitClient.class.getName() + ".quietRemoteBranches")</code> */
    boolean quietRemoteBranches = Boolean.getBoolean(GitClient.class.getName() + ".quietRemoteBranches");

    /**
     * The supported credential types.
     * @since 1.2.0
     */
    CredentialsMatcher CREDENTIALS_MATCHER = CredentialsMatchers.anyOf(
            CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
            CredentialsMatchers.instanceOf(SSHUserPrivateKey.class)
    );

    /**
     * Remove all credentials from the client.
     *
     * @since 1.2.0
     */
    void clearCredentials();

    /**
     * Adds credentials to be used against a specific url.
     *
     * @param url the url for the credentials to be used against.
     * @param credentials the credentials to use.
     * @since 1.2.0
     */
    void addCredentials(String url, StandardCredentials credentials);

    /**
     * Adds credentials to be used when there are not url specific credentials defined.
     *
     * @param credentials the credentials to use.
     * @see #addCredentials(String, com.cloudbees.plugins.credentials.common.StandardCredentials)
     * @since 1.2.0
     */
    void addDefaultCredentials(StandardCredentials credentials);

    /**
     * Sets the identity of the author for future commits and merge operations.
     *
     * @param name a {@link java.lang.String} object.
     * @param email a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    void setAuthor(String name, String email) throws GitException;
    /**
     * setAuthor.
     *
     * @param p a {@link org.eclipse.jgit.lib.PersonIdent} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    void setAuthor(PersonIdent p) throws GitException;

    /**
     * Sets the identity of the committer for future commits and merge operations.
     *
     * @param name a {@link java.lang.String} object.
     * @param email a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    void setCommitter(String name, String email) throws GitException;
    /**
     * setCommitter.
     *
     * @param p a {@link org.eclipse.jgit.lib.PersonIdent} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    void setCommitter(PersonIdent p) throws GitException;

    /**
     * Expose the JGit repository this GitClient is using.
     * Don't forget to call {@link org.eclipse.jgit.lib.Repository#close()}, to avoid JENKINS-12188.
     *
     * @deprecated as of 1.1
     *      This method was deprecated to make {@link org.jenkinsci.plugins.gitclient.GitClient} remotable. When called on
     *      a proxy object, this method throws {@link java.io.NotSerializableException}.
     *      Use {@link #withRepository(RepositoryCallback)} to pass in the closure instead.
     *      This prevents the repository leak (JENKINS-12188), too.
     * @return a {@link org.eclipse.jgit.lib.Repository} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    @Deprecated
    Repository getRepository() throws GitException;

    /**
     * Runs the computation that requires local access to {@link org.eclipse.jgit.lib.Repository}.
     *
     * @param callable the repository callback used as closure to instance
     * @param <T> type for the repository callback
     * @return a T object.
     * @throws java.io.IOException in case of IO error
     * @throws java.lang.InterruptedException if interrupted
     */
    <T> T withRepository(RepositoryCallback<T> callable) throws IOException, InterruptedException;

    /**
     * The working tree of this repository.
     *
     * @return a {@link hudson.FilePath} object.
     */
    FilePath getWorkTree();

    /**
     * init.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void init() throws GitException, InterruptedException;

    /**
     * add.
     *
     * @param filePattern a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void add(String filePattern) throws GitException, InterruptedException;

    /**
     * commit.
     *
     * @param message a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void commit(String message) throws GitException, InterruptedException;

    /**
     * commit.
     *
     * @deprecated as of 1.1
     *      Use {@link #setAuthor(String, String)} and {@link #setCommitter(String, String)}
     *      then call {@link #commit(String)}
     * @param message a {@link java.lang.String} object.
     * @param author a {@link org.eclipse.jgit.lib.PersonIdent} object.
     * @param committer a {@link org.eclipse.jgit.lib.PersonIdent} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void commit(String message, PersonIdent author, PersonIdent committer) throws GitException, InterruptedException;

    /**
     * Return true if the current workspace has a git repository.
     *
     * @return true if this workspace has a git repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    boolean hasGitRepo() throws GitException, InterruptedException;

    /**
     * Return true if the current workspace has a git repository.
     * If checkParentDirectories is true, searches parent directories.
     * If checkParentDirectories is false, checks workspace directory only.
     *
     * @param checkParentDirectories if true, search upward for a git repository
     * @return true if this workspace has a git repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    boolean hasGitRepo(boolean checkParentDirectories) throws GitException, InterruptedException;

    /**
     * isCommitInRepo.
     *
     * @param commit a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @return true if commit is in repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    boolean isCommitInRepo(ObjectId commit) throws GitException, InterruptedException;

    /**
     * From a given repository, get a remote's URL
     *
     * @param name The name of the remote (e.g. origin)
     * @throws hudson.plugins.git.GitException if executing the git command fails
     * @return a {@link java.lang.String} object.
     * @throws java.lang.InterruptedException if interrupted.
     */
    String getRemoteUrl(String name) throws GitException, InterruptedException;

    /**
     * For a given repository, set a remote's URL
     *
     * @param name The name of the remote (e.g. origin)
     * @param url The new value of the remote's URL
     * @throws hudson.plugins.git.GitException if executing the git command fails
     * @throws java.lang.InterruptedException if interrupted.
     */
    void setRemoteUrl(String name, String url) throws GitException, InterruptedException;

    /**
     * addRemoteUrl.
     *
     * @param name a {@link java.lang.String} object.
     * @param url a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void addRemoteUrl(String name, String url) throws GitException, InterruptedException;

    /**
     * Checks out the specified commit/tag/branch into the workspace.
     * (equivalent of <code>git checkout <em>branch</em></code>.)
     *
     * @param ref A git object references expression (either a sha1, tag or branch)
     * @deprecated use {@link #checkout()} and {@link org.jenkinsci.plugins.gitclient.CheckoutCommand}
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void checkout(String ref) throws GitException, InterruptedException;

    /**
     * Creates a new branch that points to the specified ref.
     * (equivalent to git checkout -b <em>branch</em> <em>commit</em>)
     *
     * This will fail if the branch already exists.
     *
     * @param ref A git object references expression. For backward compatibility, <code>null</code> will checkout current HEAD
     * @param branch name of the branch to create from reference
     * @deprecated use {@link #checkout()} and {@link org.jenkinsci.plugins.gitclient.CheckoutCommand}
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void checkout(String ref, String branch) throws GitException, InterruptedException;

    /**
     * checkout.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CheckoutCommand} object.
     */
    CheckoutCommand checkout();

    /**
     * Regardless of the current state of the workspace (whether there is some dirty files, etc)
     * and the state of the repository (whether the branch of the specified name exists or not),
     * when this method exits the following conditions hold:
     *
     * <ul>
     *     <li>The branch of the specified name <em>branch</em> exists and points to the specified <em>ref</em>
     *     <li><code>HEAD</code> points to <em>branch</em>. In other words, the workspace is on the specified branch.
     *     <li>Both index and workspace are the same tree with <em>ref</em>.
     *         (no dirty files and no staged changes, although this method will not touch untracked files
     *         in the workspace.)
     * </ul>
     *
     * <p>
     * This method is preferred over the {@link #checkout(String, String)} family of methods, as
     * this method is affected far less by the current state of the repository. The <code>checkout</code>
     * methods, in their attempt to emulate the "git checkout" command line behaviour, have too many
     * side effects. In Jenkins, where you care a lot less about throwing away local changes and
     * care a lot more about resetting the workspace into a known state, methods like this is more useful.
     *
     * <p>
     * For compatibility reasons, the order of the parameter is different from {@link #checkout(String, String)}.
     *
     * @since 1.0.6
     * @param branch a {@link java.lang.String} object.
     * @param ref a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void checkoutBranch(@CheckForNull String branch, String ref) throws GitException, InterruptedException;


    /**
     * Clone a remote repository
     *
     * @param url URL for remote repository to clone
     * @param origin upstream track name, defaults to <code>origin</code> by convention
     * @param useShallowClone option to create a shallow clone, that has some restriction but will make clone operation
     * @param reference (optional) reference to a local clone for faster clone operations (reduce network and local storage costs)
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void clone(String url, String origin, boolean useShallowClone, String reference) throws GitException, InterruptedException;

    /**
     * Returns a {@link org.jenkinsci.plugins.gitclient.CloneCommand} to build up the git-log invocation.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    CloneCommand clone_(); // can't use 'clone' as it collides with Object.clone()

    /**
     * Fetch commits from url which match any of the passed in
     * refspecs. Assumes <code>remote.remoteName.url</code> has been set.
     *
     * @deprecated use {@link #fetch_()} and configure a {@link org.jenkinsci.plugins.gitclient.FetchCommand}
     * @param url a {@link org.eclipse.jgit.transport.URIish} object.
     * @param refspecs a {@link java.util.List} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void fetch(URIish url, List<RefSpec> refspecs) throws GitException, InterruptedException;

    /**
     * fetch.
     *
     * @deprecated use {@link #fetch_()} and configure a {@link org.jenkinsci.plugins.gitclient.FetchCommand}
     * @param remoteName a {@link java.lang.String} object.
     * @param refspec a {@link org.eclipse.jgit.transport.RefSpec} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void fetch(String remoteName, RefSpec... refspec) throws GitException, InterruptedException;

    /**
     * fetch.
     *
     * @deprecated use {@link #fetch_()} and configure a {@link org.jenkinsci.plugins.gitclient.FetchCommand}
     * @param remoteName a {@link java.lang.String} object.
     * @param refspec a {@link org.eclipse.jgit.transport.RefSpec} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void fetch(String remoteName, RefSpec refspec) throws GitException, InterruptedException;

    /**
     * fetch_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.FetchCommand} object.
     */
    FetchCommand fetch_(); // can't use 'fetch' as legacy IGitAPI already define this method

    /**
     * push.
     *
     * @deprecated use {@link #push()} and configure a {@link org.jenkinsci.plugins.gitclient.PushCommand}
     * @param remoteName a {@link java.lang.String} object.
     * @param refspec a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void push(String remoteName, String refspec) throws GitException, InterruptedException;

    /**
     * push.
     *
     * @deprecated use {@link #push()} and configure a {@link org.jenkinsci.plugins.gitclient.PushCommand}
     * @param url a {@link org.eclipse.jgit.transport.URIish} object.
     * @param refspec a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void push(URIish url, String refspec) throws GitException, InterruptedException;

    /**
     * push.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     */
    PushCommand push();


    /**
     * merge.
     *
     * @deprecated use {@link #merge()} and configure a {@link org.jenkinsci.plugins.gitclient.MergeCommand}
     * @param rev a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void merge(ObjectId rev) throws GitException, InterruptedException;

    /**
     * merge.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.MergeCommand} object.
     */
    MergeCommand merge();

    /**
     * rebase.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RebaseCommand} object.
     */
    RebaseCommand rebase();

    /**
     * init_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.InitCommand} object.
     */
    InitCommand init_(); // can't use 'init' as legacy IGitAPI already define this method

    /**
     * Prune stale remote tracking branches with "git remote prune" on the specified remote.
     *
     * @param repository a {@link org.eclipse.jgit.transport.RemoteConfig} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void prune(RemoteConfig repository) throws GitException, InterruptedException;

    /**
     * Fully revert working copy to a clean state, i.e. run both
     * <a href="https://www.kernel.org/pub/software/scm/git/docs/git-reset.html">git-reset(1) --hard</a> then
     * <a href="https://www.kernel.org/pub/software/scm/git/docs/git-clean.html">git-clean(1)</a> for working copy to
     * match a fresh clone.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void clean() throws GitException, InterruptedException;

    /**
     * Fully revert working copy to a clean state, i.e. run both
     * <a href="https://www.kernel.org/pub/software/scm/git/docs/git-reset.html">git-reset(1) --hard</a> then
     * <a href="https://www.kernel.org/pub/software/scm/git/docs/git-clean.html">git-clean(1)</a> for working copy to
     * match a fresh clone.
     *
     * @param cleanSubmodule flag to add extra -f
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void clean(boolean cleanSubmodule) throws GitException, InterruptedException;



    // --- manage branches

    /**
     * branch.
     *
     * @param name a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void branch(String name) throws GitException, InterruptedException;

    /**
     * (force) delete a branch.
     *
     * @param name a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void deleteBranch(String name) throws GitException, InterruptedException;

    /**
     * getBranches.
     *
     * @return a {@link java.util.Set} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    Set<Branch> getBranches() throws GitException, InterruptedException;

    /**
     * getRemoteBranches.
     *
     * @return a {@link java.util.Set} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    Set<Branch> getRemoteBranches() throws GitException, InterruptedException;


    // --- manage tags

    /**
     * Create (or update) a tag. If tag already exist it gets updated (equivalent to <code>git tag --force</code>)
     *
     * @param tagName a {@link java.lang.String} object.
     * @param comment a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void tag(String tagName, String comment) throws GitException, InterruptedException;

    /**
     * tagExists.
     *
     * @param tagName a {@link java.lang.String} object.
     * @return true if tag exists in repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    boolean tagExists(String tagName) throws GitException, InterruptedException;

    /**
     * getTagMessage.
     *
     * @param tagName a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    String getTagMessage(String tagName) throws GitException, InterruptedException;

    /**
     * deleteTag.
     *
     * @param tagName a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void deleteTag(String tagName) throws GitException, InterruptedException;

    /**
     * getTagNames.
     *
     * @param tagPattern a {@link java.lang.String} object.
     * @return a {@link java.util.Set} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    Set<String> getTagNames(String tagPattern) throws GitException, InterruptedException;
    /**
     * getRemoteTagNames.
     *
     * @param tagPattern a {@link java.lang.String} object.
     * @return a {@link java.util.Set} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    Set<String> getRemoteTagNames(String tagPattern) throws GitException, InterruptedException;


    // --- manage refs

    /**
     * Create (or update) a ref. The ref will reference HEAD (equivalent to <code>git update-ref ... HEAD</code>).
     *
     * @param refName the full name of the ref (e.g. "refs/myref"). Spaces will be replaced with underscores.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void ref(String refName) throws GitException, InterruptedException;

    /**
     * Check if a ref exists. Equivalent to comparing the return code of <code>git show-ref</code> to zero.
     *
     * @param refName the full name of the ref (e.g. "refs/myref"). Spaces will be replaced with underscores.
     * @return True if the ref exists, false otherwse.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    boolean refExists(String refName) throws GitException, InterruptedException;

    /**
     * Deletes a ref. Has no effect if the ref does not exist, equivalent to <code>git update-ref -d</code>.
     *
     * @param refName the full name of the ref (e.g. "refs/myref"). Spaces will be replaced with underscores.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void deleteRef(String refName) throws GitException, InterruptedException;

    /**
     * List refs with the given prefix. Equivalent to <code>git for-each-ref --format="%(refname)"</code>.
     *
     * @param refPrefix the literal prefix any ref returned will have. The empty string implies all.
     * @return a set of refs, each beginning with the given prefix. Empty if none.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    Set<String> getRefNames(String refPrefix) throws GitException, InterruptedException;

    // --- lookup revision

    /**
     * getHeadRev.
     *
     * @param url a {@link java.lang.String} object.
     * @return a {@link java.util.Map} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    Map<String, ObjectId> getHeadRev(String url) throws GitException, InterruptedException;

    /**
     * getHeadRev.
     *
     * @param remoteRepoUrl a {@link java.lang.String} object.
     * @param branch a {@link java.lang.String} object.
     * @return a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    ObjectId getHeadRev(String remoteRepoUrl, String branch) throws GitException, InterruptedException;

    /**
     * List references in a remote repository. Equivalent to <code>git ls-remote [--heads] [--tags] &lt;repository&gt; [&lt;refs&gt;]</code>.
     *
     * @param remoteRepoUrl
     *      Remote repository URL.
     * @param pattern
     *      Only references matching the given pattern are displayed.
     * @param headsOnly
     *      Limit to only refs/heads.
     * @param tagsOnly
     *      Limit to only refs/tags.
     *      headsOnly and tagsOnly are not mutually exclusive;
     *      when both are true, references stored in refs/heads and refs/tags are displayed.
     * @return a map of reference names and their commit hashes. Empty if none.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    Map<String, ObjectId> getRemoteReferences(String remoteRepoUrl, String pattern, boolean headsOnly, boolean tagsOnly) throws GitException, InterruptedException;

    /**
     * List symbolic references in a remote repository. Equivalent to <code>git ls-remote --symref &lt;repository&gt;
     * [&lt;refs&gt;]</code>. Note: the response may be empty for multiple reasons
     *
     * @param remoteRepoUrl Remote repository URL.
     * @param pattern       Only references matching the given pattern are displayed.
     * @return a map of reference names and their underlying references. Empty if none or if the remote does not report
     * symbolic references (i.e. Git 1.8.4 or earlier) or if the client does not support reporting symbolic references
     * (e.g. command line Git prior to 2.8.0).
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException  if interrupted.
     */
    Map<String, String> getRemoteSymbolicReferences(String remoteRepoUrl, String pattern) throws GitException, InterruptedException;

    /**
     * Retrieve commit object that is direct child for <code>revName</code> revision reference.
     *
     * @param revName a commit sha1 or tag/branch refname
     * @throws hudson.plugins.git.GitException when no such commit / revName is found in repository.
     * @return a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @throws java.lang.InterruptedException if interrupted.
     */
    ObjectId revParse(String revName) throws GitException, InterruptedException;

    /**
     * revList_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     */
    RevListCommand revList_();

    /**
     * revListAll.
     *
     * @return a {@link java.util.List} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    List<ObjectId> revListAll() throws GitException, InterruptedException;

    /**
     * revList.
     *
     * @param ref a {@link java.lang.String} object.
     * @return a {@link java.util.List} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    List<ObjectId> revList(String ref) throws GitException, InterruptedException;


    // --- submodules

    /**
     * subGit.
     *
     * @return a IGitAPI implementation to manage git submodule repository
     * @param subdir a {@link java.lang.String} object.
     */
    GitClient subGit(String subdir);

    /**
     * Returns true if the repository has Git submodules.
     *
     * @return true if this repository has submodules
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    boolean hasGitModules() throws GitException, InterruptedException;

    /**
     * Finds all the submodule references in this repository at the specified tree.
     *
     * @return never null.
     * @param treeIsh a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    List<IndexEntry> getSubmodules( String treeIsh ) throws GitException, InterruptedException;

    /**
     * Create a submodule in subdir child directory for remote repository
     *
     * @param remoteURL a {@link java.lang.String} object.
     * @param subdir a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void addSubmodule(String remoteURL, String subdir) throws GitException, InterruptedException;

    /**
     * Run submodule update optionally recursively on all submodules
     * (equivalent of <code>git submodule update <em>--recursive</em></code>.)
     *
     * @deprecated use {@link #submoduleUpdate()} and {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand}
     * @param recursive a boolean.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void submoduleUpdate(boolean recursive)  throws GitException, InterruptedException;

    /**
     * Run submodule update optionally recursively on all submodules, with a specific
     * reference passed to git clone if needing to --init.
     * (equivalent of <code>git submodule update <em>--recursive</em> <em>--reference 'reference'</em></code>.)
     *
     * @deprecated use {@link #submoduleUpdate()} and {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand}
     * @param recursive a boolean.
     * @param reference a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void submoduleUpdate(boolean recursive, String reference) throws GitException, InterruptedException;

    /**
     * Run submodule update optionally recursively on all submodules, optionally with remoteTracking submodules
     * (equivalent of <code>git submodule update <em>--recursive</em> <em>--remote</em></code>.)
     *
     * @deprecated use {@link #submoduleUpdate()} and {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand}
     * @param recursive a boolean.
     * @param remoteTracking a boolean.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void submoduleUpdate(boolean recursive, boolean remoteTracking)  throws GitException, InterruptedException;
    /**
     * Run submodule update optionally recursively on all submodules, optionally with remoteTracking, with a specific
     * reference passed to git clone if needing to --init.
     * (equivalent of <code>git submodule update <em>--recursive</em> <em>--remote</em> <em>--reference 'reference'</em></code>.)
     *
     * @deprecated use {@link #submoduleUpdate()} and {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand}
     * @param recursive a boolean.
     * @param remoteTracking a boolean.
     * @param reference a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void submoduleUpdate(boolean recursive, boolean remoteTracking, String reference)  throws GitException, InterruptedException;

    /**
     * submoduleUpdate.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand} object.
     */
    SubmoduleUpdateCommand submoduleUpdate();

    /**
     * submoduleClean.
     *
     * @param recursive a boolean.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void submoduleClean(boolean recursive)  throws GitException, InterruptedException;

    /**
     * submoduleInit.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void submoduleInit()  throws GitException, InterruptedException;

    /**
     * Set up submodule URLs so that they correspond to the remote pertaining to
     * the revision that has been checked out.
     *
     * @param rev a {@link hudson.plugins.git.Revision} object.
     * @param listener a {@link hudson.model.TaskListener} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void setupSubmoduleUrls( Revision rev, TaskListener listener ) throws GitException, InterruptedException;


    // --- commit log and notes

    /**
     * changelog.
     *
     * @deprecated use {@link #changelog(String, String, Writer)}
     * @param revFrom a {@link java.lang.String} object.
     * @param revTo a {@link java.lang.String} object.
     * @param os a {@link java.io.OutputStream} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    void changelog(String revFrom, String revTo, OutputStream os) throws GitException, InterruptedException;

    /**
     * Adds the changelog entries for commits in the range revFrom..revTo.
     *
     * This is just a short cut for calling {@link #changelog()} with appropriate parameters.
     *
     * @param revFrom a {@link java.lang.String} object.
     * @param revTo a {@link java.lang.String} object.
     * @param os a {@link java.io.Writer} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void changelog(String revFrom, String revTo, Writer os) throws GitException, InterruptedException;

    /**
     * Returns a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} to build up the git-log invocation.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    ChangelogCommand changelog();

    /**
     * Appends to an existing git-note on the current HEAD commit.
     *
     * If a note doesn't exist, it works just like {@link #addNote(String, String)}
     *
     * @param note
     *      Content of the note.
     * @param namespace
     *      If unqualified, interpreted as "refs/notes/NAMESPACE" just like cgit.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void appendNote(String note, String namespace ) throws GitException, InterruptedException;

    /**
     * Adds a new git-note on the current HEAD commit.
     *
     * @param note
     *      Content of the note.
     * @param namespace
     *      If unqualified, interpreted as "refs/notes/NAMESPACE" just like cgit.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    void addNote(String note, String namespace ) throws GitException, InterruptedException;

    /**
     * Given a Revision, show it as if it were an entry from git whatchanged, so that it
     * can be parsed by GitChangeLogParser.
     *
     * <p>
     * Changes are computed on the [from..to] range. If {@code from} is null, this prints
     * just one commit that {@code to} represents.
     *
     * <p>
     * For merge commit, this method reports one diff per each parent. This makes this method
     * behave differently from {@link #changelog()}.
     *
     * @param r a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @return The git whatchanged output, in <code>raw</code> format.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    List<String> showRevision(ObjectId r) throws GitException, InterruptedException;

    /**
     * Given a Revision, show it as if it were an entry from git whatchanged, so that it
     * can be parsed by GitChangeLogParser.
     *
     * <p>
     * Changes are computed on the [from..to] range. If {@code from} is null, this prints
     * just one commit that {@code to} represents.
     *
     * <p>
     * For merge commit, this method reports one diff per each parent. This makes this method
     * behave differently from {@link #changelog()}.
     *
     * @param from a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @param to a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @return The git whatchanged output, in <code>raw</code> format.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    List<String> showRevision(ObjectId from, ObjectId to) throws GitException, InterruptedException;

    /**
     * Given a Revision, show it as if it were an entry from git whatchanged, so that it
     * can be parsed by GitChangeLogParser.
     *
     * <p>
     * If useRawOutput is true, the '--raw' option will include commit file information to be passed to the
     * GitChangeLogParser.
     *
     * <p>
     * Changes are computed on the [from..to] range. If {@code from} is null, this prints
     * just one commit that {@code to} represents.
     *
     * <p>
     * For merge commit, this method reports one diff per each parent. This makes this method
     * behave differently from {@link #changelog()}.
     *
     * @param from a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @param to a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @param useRawOutput a {java.lang.Boolean} object.
     * @return The git whatchanged output, in <code>raw</code> format.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    List<String> showRevision(ObjectId from, ObjectId to, Boolean useRawOutput) throws GitException, InterruptedException;


    /**
     * Equivalent of "git-describe --tags".
     *
     * Find a nearby tag (including unannotated ones) and come up with a short identifier to describe the tag.
     *
     * @param commitIsh a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    String describe(String commitIsh) throws GitException, InterruptedException;

    /**
     * setCredentials.
     *
     * @param cred a {@link com.cloudbees.plugins.credentials.common.StandardUsernameCredentials} object.
     */
    void setCredentials(StandardUsernameCredentials cred);

    /**
     * setProxy.
     *
     * @param proxy a {@link hudson.ProxyConfiguration} object.
     */
    void setProxy(ProxyConfiguration proxy);

    /**
     * Find all the branches that include the given commit.
     *
     * @param revspec commit id to query for
     * @param allBranches whether remote branches should be also queried (<code>true</code>) or not (<code>false</code>)
     * @return list of branches the specified commit belongs to
     * @throws hudson.plugins.git.GitException on Git exceptions
     * @throws java.lang.InterruptedException on thread interruption
     */
    List<Branch> getBranchesContaining(String revspec, boolean allBranches) throws GitException, InterruptedException;

    /**
     * Return name and object ID of all tags in current repository.
     *
     * @return set of tags in current repository
     * @throws hudson.plugins.git.GitException on Git exceptions
     * @throws java.lang.InterruptedException on thread interruption
     */
    Set<GitObject> getTags() throws GitException, InterruptedException;
}
