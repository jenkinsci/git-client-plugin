package org.jenkinsci.plugins.gitclient;

import hudson.model.TaskListener;
import hudson.plugins.git.*;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public interface GitClient {

    boolean verbose = Boolean.getBoolean(IGitAPI.class.getName() + ".verbose");

    // If true, do not print the list of remote branches.
    boolean quietRemoteBranches = Boolean.getBoolean(GitClient.class.getName() + ".quietRemoteBranches");

    /**
     * Expose the JGit repository this GitClient is using.
     * Don't forget to call {@link org.eclipse.jgit.lib.Repository#close()}, to avoid JENKINS-12188.
     */
    Repository getRepository() throws GitException;

    public void init() throws GitException;

    void add(String filePattern) throws GitException;

    /**
     * @deprecated use {@link #commit(String, org.eclipse.jgit.lib.PersonIdent, org.eclipse.jgit.lib.PersonIdent)} as
     *             this method is environment dependent to have GIT_AUTHOR/COMMITTER set
     */
    void commit(String message) throws GitException;

    void commit(String message, PersonIdent author, PersonIdent committer) throws GitException;

    boolean hasGitRepo() throws GitException;

    boolean isCommitInRepo(ObjectId commit) throws GitException;

    /**
     * From a given repository, get a remote's URL
     * @param name The name of the remote (e.g. origin)
     * @throws GitException if executing the git command fails
     */
    String getRemoteUrl(String name) throws GitException;

    /**
     * For a given repository, set a remote's URL
     * @param name The name of the remote (e.g. origin)
     * @param url The new value of the remote's URL
     * @throws GitException if executing the git command fails
     */
    void setRemoteUrl(String name, String url) throws GitException;

    /**
     * Checks out the specified commit/tag/branch into the workspace.
     * @param ref A git object references expression (either a sha1, tag or branch)
     */
    void checkout(String ref) throws GitException;

    /**
     * Checks out the specified commit/ref into the workspace, creating specified branch
     * (equivalent to git checkout -b <em>branch</em> <em>commit</em>
     * @param ref A git object references expression. For backward compatibility, <tt>null</tt> will checkout current HEAD
     * @param branch name of the branch to create from reference
     */
    void checkout(String ref, String branch) throws GitException;

    /**
     * Clone a remote repository
     * @param url URL for remote repository to clone
     * @param origin upstream track name, defaults to <tt>origin</tt> by convention
     * @param useShallowClone option to create a shallow clone, that has some restriction but will make clone operation
     * @param reference (optional) reference to a local clone for faster clone operations (reduce network and local storage costs)
     */
    void clone(String url, String origin, boolean useShallowClone, String reference) throws GitException;


    /**
     * Fetch a remote repository. Assumes <tt>remote.remoteName.url</tt> has been set.
     */
    void fetch(String remoteName, RefSpec refspec) throws GitException;

    void push(String remoteName, String refspec) throws GitException;

    void merge(ObjectId rev) throws GitException;

    void prune(RemoteConfig repository) throws GitException;

    /**
     * Fully revert working copy to a clean state, i.e. run both
     * <a href="https://www.kernel.org/pub/software/scm/git/docs/git-reset.html">git-reset(1) --hard</a> then
     * <a href="https://www.kernel.org/pub/software/scm/git/docs/git-clean.html">git-clean(1)</a> for working copy to
     * match a fresh clone.
     * @throws GitException
     */
    void clean() throws GitException;



    // --- manage branches

    void branch(String name) throws GitException;

    /**
     * (force) delete a branch.
     */
    void deleteBranch(String name) throws GitException;

    Set<Branch> getBranches() throws GitException;

    Set<Branch> getRemoteBranches() throws GitException;


    // --- manage tags

    /**
     * Create (or update) a tag. If tag already exist it gets updated (equivalent to <tt>git tag --force</tt>)
     */
    void tag(String tagName, String comment) throws GitException;

    boolean tagExists(String tagName) throws GitException;

    String getTagMessage(String tagName) throws GitException;

    void deleteTag(String tagName) throws GitException;

    Set<String> getTagNames(String tagPattern) throws GitException;


    // --- lookup revision

    ObjectId getHeadRev(String remoteRepoUrl, String branch) throws GitException;

    /**
     * Retrieve commit object that is direct child for <tt>revName</tt> revision reference.
     * @param revName a commit sha1 or tag/branch refname
     * @throws GitException when no such commit / revName is found in repository.
     */
    ObjectId revParse(String revName) throws GitException;

    List<ObjectId> revListAll() throws GitException;

    List<ObjectId> revList(String ref) throws GitException;


    // --- submodules

    /**
     * @return a IGitAPI implementation to manage git submodule repository
     */
    GitClient subGit(String subdir);

    /**
     * Returns true if the repository has Git submodules.
     */
    boolean hasGitModules() throws GitException;

    List<IndexEntry> getSubmodules( String treeIsh ) throws GitException;

    /**
     * Create a submodule in subdir child directory for remote repository
     */
    void addSubmodule(String remoteURL, String subdir) throws GitException;

    void submoduleUpdate(boolean recursive)  throws GitException;

    void submoduleClean(boolean recursive)  throws GitException;

    /**
     * Set up submodule URLs so that they correspond to the remote pertaining to
     * the revision that has been checked out.
     */
    void setupSubmoduleUrls( Revision rev, TaskListener listener ) throws GitException;


    // --- commit log and notes

    /**
     * Adds the changelog entries for commits in the range revFrom..revTo.
     */
    void changelog(String revFrom, String revTo, OutputStream fos) throws GitException;

    void appendNote(String note, String namespace ) throws GitException;

    void addNote(String note, String namespace ) throws GitException;

    public List<String> showRevision(ObjectId r) throws GitException;

    /**
     * Given a Revision, show it as if it were an entry from git whatchanged, so that it
     * can be parsed by GitChangeLogParser.
     * <p>
     * Changes are computed on the [from..to] range.
     * @return The git show output, in <tt>raw</tt> format.
     */
    List<String> showRevision(ObjectId from, ObjectId to) throws GitException;
}
