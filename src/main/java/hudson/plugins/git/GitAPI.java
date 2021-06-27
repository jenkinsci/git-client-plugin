package hudson.plugins.git;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.CliGitAPIImpl;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

import java.io.*;
import java.util.List;
import java.util.Set;

/**
 * Backward compatible class to match the one some plugins used from git-plugin.
 * Extends CliGitAPIImpl to implement deprecated IGitAPI methods, but delegates supported methods to the selected git implementation (based on 
 * {@link org.jenkinsci.plugins.gitclient.Git#USE_CLI}).
 *
 * New implementations should use {@link org.jenkinsci.plugins.gitclient.GitClient}.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 * @deprecated
 */
@Deprecated
public class GitAPI extends CliGitAPIImpl {
    private static final long serialVersionUID = 1L;
    private final GitClient jgit;

    /**
     * Constructor for GitAPI.
     *
     * @param gitExe name of git executable (git or git.exe or jgit)
     * @param repository a {@link hudson.FilePath} for the repository directory
     * @param listener a {@link hudson.model.TaskListener} which monitors the git work
     * @param environment the {@link hudson.EnvVars} environment for the build
     * @throws java.io.IOException if any IO failure
     * @throws java.lang.InterruptedException if interrupted
     */
    @Deprecated
    public GitAPI(String gitExe, FilePath repository, TaskListener listener, EnvVars environment) throws IOException, InterruptedException {
        this(gitExe, new File(repository.getRemote()), listener, environment);
    }

    /**
     * Constructor for GitAPI.
     *
     * @param gitExe name of git executable (git or git.exe or jgit)
     * @param repository a {@link hudson.FilePath} for the repository directory
     * @param listener a {@link hudson.model.TaskListener} which monitors the git work
     * @param environment the {@link hudson.EnvVars} environment for the build
     * @param reference SHA1 for checkout
     * @throws java.io.IOException if any IO failure
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    public GitAPI(String gitExe, FilePath repository, TaskListener listener, EnvVars environment, String reference) throws IOException, InterruptedException {
        this(gitExe, repository, listener, environment);
    }

    /**
     * Constructor for GitAPI.
     *
     * @param gitExe name of git executable (git or git.exe or jgit)
     * @param repository a {@link hudson.FilePath} for the repository directory
     * @param listener a {@link hudson.model.TaskListener} which monitors the git work
     * @param environment the {@link hudson.EnvVars} environment for the build
     * @throws java.io.IOException if any IO failure
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    public GitAPI(String gitExe, File repository, TaskListener listener, EnvVars environment) throws IOException, InterruptedException {
        super(gitExe, repository, listener, environment);

        // If USE_CLI is forced, don't delegate to JGit client
        this.jgit = Git.USE_CLI ? null : Git.with(listener, environment).in(repository).using("jgit").getClient();
    }

    // --- delegate implemented methods to JGit client

    /** {@inheritDoc} */
    public void add(String filePattern) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.add(filePattern); else  jgit.add(filePattern);
    }

    /*
    public List<ObjectId> revList(String ref) throws GitException {
        return Git.USE_CLI ? super.revList(ref) :  jgit.revList(ref);
    }
    */

    /** {@inheritDoc} */
    public String getRemoteUrl(String name) throws GitException, InterruptedException {
        return Git.USE_CLI ? super.getRemoteUrl(name) :  jgit.getRemoteUrl(name);
    }

    /** {@inheritDoc} */
    public void push(String remoteName, String refspec) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.push(remoteName, refspec); else  jgit.push(remoteName, refspec);
    }

    /** {@inheritDoc} */
    public String getTagMessage(String tagName) throws GitException, InterruptedException {
        return Git.USE_CLI ? super.getTagMessage(tagName) :  jgit.getTagMessage(tagName);
    }

    /*
    public List<ObjectId> revListAll() throws GitException {
        return Git.USE_CLI ? super.revListAll() :  jgit.revListAll();
    }
    */

    /*
    public void addNote(String note, String namespace) throws GitException {
        if (Git.USE_CLI) super.addNote(note, namespace); else  jgit.addNote(note, namespace);
    }
    */

    /*
    public void appendNote(String note, String namespace) throws GitException {
        if (Git.USE_CLI) super.appendNote(note, namespace); else  jgit.appendNote(note, namespace);
    }
    */

    /*
    public void changelog(String revFrom, String revTo, OutputStream fos) throws GitException {
        if (Git.USE_CLI) super.changelog(revFrom, revTo, fos); else  jgit.changelog(revFrom, revTo, fos);
    }
    */

    /*
    public List<IndexEntry> getSubmodules(String treeIsh) throws GitException {
        return Git.USE_CLI ? super.getSubmodules(treeIsh) :  jgit.getSubmodules(treeIsh);
    }
    */

    /*
    public ObjectId getHeadRev(String remoteRepoUrl, String branch) throws GitException {
        return Git.USE_CLI ? super.getHeadRev(remoteRepoUrl, branch) :  jgit.getHeadRev(remoteRepoUrl, branch);
    }
    */

    /*
    public Set<String> getTagNames(String tagPattern) throws GitException {
        return Git.USE_CLI ? super.getTagNames(tagPattern) :  jgit.getTagNames(tagPattern);
    }
    */

    /** {@inheritDoc} */
    public GitClient subGit(String subdir) {
        return Git.USE_CLI ? super.subGit(subdir) :  jgit.subGit(subdir);
    }

    /** {@inheritDoc} */
    public void setRemoteUrl(String name, String url) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.setRemoteUrl(name, url); else  jgit.setRemoteUrl(name, url);
    }

    /*
    public void prune(RemoteConfig repository) throws GitException {
        if (Git.USE_CLI) super.prune(repository); else  jgit.prune(repository);
    }
    */

    /*
    public void submoduleUpdate(boolean recursive) throws GitException {
        if (Git.USE_CLI) super.submoduleUpdate(recursive); else  jgit.submoduleUpdate(recursive);
    }
    */

    /*
    public void submoduleUpdate(boolean recursive, String reference) throws GitException {
        if (Git.USE_CLI) super.submoduleUpdate(recursive, String reference); else  jgit.submoduleUpdate(recursive, String reference);
    }
    */

    /*
    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException {
        return Git.USE_CLI ? super.showRevision(from, to) :  jgit.showRevision(from, to);
    }
    */

    /*
    public boolean hasGitModules() throws GitException {
        return Git.USE_CLI ? super.hasGitModules() :  jgit.hasGitModules();
    }
    */

    /** {@inheritDoc} */
    public Set<Branch> getBranches() throws GitException, InterruptedException {
        return Git.USE_CLI ? super.getBranches() :  jgit.getBranches();
    }

    /*
    public void addSubmodule(String remoteURL, String subdir) throws GitException {
        if (Git.USE_CLI) super.addSubmodule(remoteURL, subdir); else  jgit.addSubmodule(remoteURL, subdir);
    }
    */

    /*
    public void clone(String url, String origin, boolean useShallowClone, String reference) throws GitException {
        if (Git.USE_CLI) super.clone(url, origin, useShallowClone, reference); else  jgit.clone(url, origin, useShallowClone, reference);
    }
    */

    /** {@inheritDoc} */
    public Set<Branch> getRemoteBranches() throws GitException, InterruptedException {
        return Git.USE_CLI ? super.getRemoteBranches() :  jgit.getRemoteBranches();
    }

    /** {@inheritDoc} */
    public void init() throws GitException, InterruptedException {
        if (Git.USE_CLI) super.init(); else  jgit.init();
    }

    /** {@inheritDoc} */
    public void deleteBranch(String name) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.deleteBranch(name); else  jgit.deleteBranch(name);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("deprecation")
    public void checkout(String ref, String branch) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        if (Git.USE_CLI) super.checkout(ref, branch); else  jgit.checkout(ref, branch);
    }

    /** {@inheritDoc} */
    public boolean hasGitRepo() throws GitException, InterruptedException {
        return Git.USE_CLI ? super.hasGitRepo() :  jgit.hasGitRepo();
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasGitRepo(boolean checkParentDirectories) throws GitException, InterruptedException {
        return Git.USE_CLI ? super.hasGitRepo(checkParentDirectories) :  jgit.hasGitRepo(checkParentDirectories);
    }

    /** {@inheritDoc} */
    public boolean isCommitInRepo(ObjectId commit) throws GitException, InterruptedException {
        return Git.USE_CLI ? super.isCommitInRepo(commit) :  jgit.isCommitInRepo(commit);
    }

    /*
    public void setupSubmoduleUrls(Revision rev, TaskListener listener) throws GitException {
        if (Git.USE_CLI) super.setupSubmoduleUrls(rev, listener); else  jgit.setupSubmoduleUrls(rev, listener);
    }
    */

    /** {@inheritDoc} */
    public void commit(String message) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.commit(message); else  jgit.commit(message);
    }

    /** {@inheritDoc} */
    public void commit(String message, PersonIdent author, PersonIdent committer) throws GitException, InterruptedException {
        if (Git.USE_CLI) {
            super.setAuthor(author);
            super.setCommitter(committer);
            super.commit(message);
        } else {
            jgit.setAuthor(author);
            jgit.setCommitter(committer);
            jgit.commit(message);
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("deprecation")
    public void checkout(String ref) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        if (Git.USE_CLI) super.checkout(ref); else  jgit.checkout(ref);
    }

    /** {@inheritDoc} */
    public void deleteTag(String tagName) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.deleteTag(tagName); else  jgit.deleteTag(tagName);
    }

    /** {@inheritDoc} */
    @NonNull
    public Repository getRepository() throws GitException {
        return Git.USE_CLI ? super.getRepository() :  jgit.getRepository();
    }

    /** {@inheritDoc} */
    public void tag(String tagName, String comment) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.tag(tagName, comment); else  jgit.tag(tagName, comment);
    }

    /*
    public List<String> showRevision(ObjectId r) throws GitException {
        return Git.USE_CLI ? super.showRevision(r) :  jgit.showRevision(r);
    }
    */

    /** {@inheritDoc} */
    @SuppressWarnings("deprecation")
    public void fetch(URIish url, List<RefSpec> refspecs) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        if (Git.USE_CLI) super.fetch(url, refspecs); else  jgit.fetch(url, refspecs);
    }

    /** {@inheritDoc} */
    public void fetch(String remoteName, RefSpec... refspec) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.fetch(remoteName, refspec); else  jgit.fetch(remoteName, refspec);
    }

    /** {@inheritDoc} */
    public void fetch(String remoteName, RefSpec refspec) throws GitException, InterruptedException {
        fetch(remoteName, new RefSpec[] {refspec});
    }

    /** {@inheritDoc} */
    public boolean tagExists(String tagName) throws GitException, InterruptedException {
        return Git.USE_CLI ? super.tagExists(tagName) :  jgit.tagExists(tagName);
    }

    /*
    public void submoduleClean(boolean recursive) throws GitException {
        if (Git.USE_CLI) super.submoduleClean(recursive); else  jgit.submoduleClean(recursive);
    }
    */

    /** {@inheritDoc} */
    public void clean() throws GitException, InterruptedException {
        if (Git.USE_CLI) super.clean(); else  jgit.clean();
    }

    /** {@inheritDoc} */
    public ObjectId revParse(String revName) throws GitException, InterruptedException {
        return Git.USE_CLI ? super.revParse(revName) :  jgit.revParse(revName);
    }

    /** {@inheritDoc} */
    public void branch(String name) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.branch(name); else  jgit.branch(name);
    }
}
