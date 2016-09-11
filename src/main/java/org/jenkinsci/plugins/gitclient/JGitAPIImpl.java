package org.jenkinsci.plugins.gitclient;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.removeStart;
import static org.eclipse.jgit.api.ResetCommand.ResetType.HARD;
import static org.eclipse.jgit.api.ResetCommand.ResetType.MIXED;
import static org.eclipse.jgit.lib.Constants.CHARSET;
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.R_HEADS;
import static org.eclipse.jgit.lib.Constants.R_REMOTES;
import static org.eclipse.jgit.lib.Constants.R_TAGS;
import static org.eclipse.jgit.lib.Constants.typeString;
import static org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK;
import static org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE;

import hudson.FilePath;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitLockFailedException;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import hudson.util.IOUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.apache.commons.lang.time.FastDateFormat;
import org.eclipse.jgit.api.AddNoteCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.ShowNoteCommand;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.errors.InvalidPatternException;
import org.eclipse.jgit.errors.LockFailedException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.fnmatch.FileNameMatcher;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevFlag;
import org.eclipse.jgit.revwalk.RevFlagSet;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.MaxCountRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jenkinsci.plugins.gitclient.trilead.SmartCredentialsProvider;
import org.jenkinsci.plugins.gitclient.trilead.TrileadSessionFactory;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.google.common.base.Functions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.eclipse.jgit.api.RebaseCommand.Operation;
import org.eclipse.jgit.api.RebaseResult;

/**
 * GitClient pure Java implementation using JGit.
 * Goal is to eventually get a full java implementation for GitClient
 * <b>
 * For internal use only, don't use directly. See {@link org.jenkinsci.plugins.gitclient.Git}
 * </b>
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 * @author Kohsuke Kawaguchi
 */
public class JGitAPIImpl extends LegacyCompatibleGitAPIImpl {
    private static final long serialVersionUID = 1L;

    private final TaskListener listener;
    private PersonIdent author, committer;

    private transient CredentialsProvider provider;

    JGitAPIImpl(File workspace, TaskListener listener) {
        /* If workspace is null, then default to current directory to match 
         * CliGitAPIImpl behavior */
        super(workspace == null ? new File(".") : workspace);
        this.listener = listener;

        // to avoid rogue plugins from clobbering what we use, always
        // make a point of overwriting it with ours.
        SshSessionFactory.setInstance(new TrileadSessionFactory());
    }

    /**
     * clearCredentials.
     */
    public void clearCredentials() {
        asSmartCredentialsProvider().clearCredentials();
    }

    /** {@inheritDoc} */
    public void addCredentials(String url, StandardCredentials credentials) {
        asSmartCredentialsProvider().addCredentials(url, credentials);
    }

    /** {@inheritDoc} */
    public void addDefaultCredentials(StandardCredentials credentials) {
        asSmartCredentialsProvider().addDefaultCredentials(credentials);
    }

    private synchronized SmartCredentialsProvider asSmartCredentialsProvider() {
        if (!(provider instanceof SmartCredentialsProvider)) {
            provider = new SmartCredentialsProvider(listener);
        }
        return ((SmartCredentialsProvider) provider);
    }

    /**
     * setCredentialsProvider.
     *
     * @param prov a {@link org.eclipse.jgit.transport.CredentialsProvider} object.
     */
    public synchronized void setCredentialsProvider(CredentialsProvider prov) {
        this.provider = prov;
    }

    private synchronized CredentialsProvider getProvider() {
        return provider;
    }

    /** {@inheritDoc} */
    public GitClient subGit(String subdir) {
        return new JGitAPIImpl(new File(workspace, subdir), listener);
    }

    /** {@inheritDoc} */
    public void setAuthor(String name, String email) throws GitException {
        author = new PersonIdent(name,email);
    }

    /** {@inheritDoc} */
    public void setCommitter(String name, String email) throws GitException {
        committer = new PersonIdent(name,email);
    }

    /**
     * init.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public void init() throws GitException, InterruptedException {
        init_().workspace(workspace.getAbsolutePath()).execute();
    }

    private void doInit(String workspace, boolean bare) throws GitException {
        try {
            Git.init().setBare(bare).setDirectory(new File(workspace)).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /**
     * checkout.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CheckoutCommand} object.
     */
    public CheckoutCommand checkout() {
        return new CheckoutCommand() {

            public String ref;
            public String branch;
            public boolean deleteBranch;
            public List<String> sparseCheckoutPaths = Collections.emptyList();

            public CheckoutCommand ref(String ref) {
                this.ref = ref;
                return this;
            }

            public CheckoutCommand branch(String branch) {
                this.branch = branch;
                return this;
            }

            public CheckoutCommand deleteBranchIfExist(boolean deleteBranch) {
                this.deleteBranch = deleteBranch;
                return this;
            }

            public CheckoutCommand sparseCheckoutPaths(List<String> sparseCheckoutPaths) {
                this.sparseCheckoutPaths = sparseCheckoutPaths == null ? Collections.<String>emptyList() : sparseCheckoutPaths;
                return this;
            }

            public CheckoutCommand timeout(Integer timeout) {
                // noop in jgit
                return this;
            }

            public void execute() throws GitException, InterruptedException {

                if(! sparseCheckoutPaths.isEmpty()) {
                    listener.getLogger().println("[ERROR] JGit doesn't support sparse checkout.");
                    throw new UnsupportedOperationException("not implemented yet");
                }

                if (branch == null)
                    doCheckout(ref);
                else if (deleteBranch)
                    doCheckoutCleanBranch(branch, ref);
                else
                    doCheckout(ref, branch);
            }
        };
    }

    private void doCheckout(String ref) throws GitException {
        boolean retried = false;
        Repository repo = null;
        while (true) {
            try {
                repo = getRepository();
                try {
                    // force in Jgit is "-B" in Git CLI, meaning no forced switch,
                    // but forces recreation of the branch.
                    // we need to take back all open changes to get the equivalent
                    // of git checkout -f
                    git(repo).reset().setMode(HARD).call();
                } catch (GitAPIException e) {
                    throw new GitException("Could not reset the workspace before checkout of " + ref, e);
                } catch (JGitInternalException e) {
                    if (e.getCause() instanceof LockFailedException){
                        throw new GitLockFailedException("Could not lock repository. Please try again", e);
                    } else {
                        throw e;
                    }
                }

                if (repo.resolve(ref) != null) {
                    // ref is either an existing reference or a shortcut to a tag or branch (without refs/heads/)
                    git(repo).checkout().setName(ref).setForce(true).call();
                    return;
                }

                List<String> remoteTrackingBranches = new ArrayList<String>();
                for (String remote : repo.getRemoteNames()) {
                    // look for exactly ONE remote tracking branch
                    String matchingRemoteBranch = Constants.R_REMOTES + remote + "/" + ref;
                    if (repo.getRef(matchingRemoteBranch) != null) {
                        remoteTrackingBranches.add(matchingRemoteBranch);
                    }
                }

                if (remoteTrackingBranches.isEmpty()) {
                    throw new GitException("No matching revision for " + ref + " found.");
                }
                if (remoteTrackingBranches.size() > 1) {
                    throw new GitException("Found more than one matching remote tracking branches for  " + ref + " : " + remoteTrackingBranches);
                }

                String matchingRemoteBranch = remoteTrackingBranches.get(0);
                listener.getLogger().format("[WARNING] Automatically creating a local branch '%s' tracking remote branch '%s'", ref, removeStart(matchingRemoteBranch, Constants.R_REMOTES));

                git(repo).checkout()
                    .setCreateBranch(true)
                    .setName(ref)
                    .setUpstreamMode(SetupUpstreamMode.SET_UPSTREAM)
                    .setStartPoint(matchingRemoteBranch).call();
                return;
            } catch (CheckoutConflictException e) {
                if (repo != null) {
                    repo.close(); /* Close and null for immediate reuse */
                    repo = null;
                }
                // "git checkout -f" seems to overwrite local untracked files but git CheckoutCommand doesn't.
                // see the test case GitAPITestCase.test_localCheckoutConflict. so in this case we manually
                // clean up the conflicts and try it again

                if (retried)
                    throw new GitException("Could not checkout " + ref, e);
                retried = true;
                repo = getRepository(); /* Reusing repo declared and assigned earlier */
                for (String path : e.getConflictingPaths()) {
                    File conflict = new File(repo.getWorkTree(), path);
                    if (!conflict.delete() && conflict.exists()) {
                        listener.getLogger().println("[WARNING] conflicting path " + conflict + " not deleted");
                    }
                }
            } catch (IOException e) {
                throw new GitException("Could not checkout " + ref, e);
            } catch (GitAPIException e) {
                throw new GitException("Could not checkout " + ref, e);
            } catch (JGitInternalException e) {
                if (Pattern.matches("Cannot lock.+", e.getMessage())){
                    throw new GitLockFailedException("Could not lock repository. Please try again", e);
                } else {
                    throw e;
                }
            } finally {
                if (repo != null) repo.close();
            }
        }
    }

    private void doCheckout(String ref, String branch) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            if (ref == null) ref = repo.resolve(HEAD).name();
            git(repo).checkout().setName(branch).setCreateBranch(true).setForce(true).setStartPoint(ref).call();
        } catch (IOException e) {
            throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
        } catch (GitAPIException e) {
            throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    private void doCheckoutCleanBranch(String branch, String ref) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            RefUpdate refUpdate = repo.updateRef(R_HEADS + branch);
            refUpdate.setNewObjectId(repo.resolve(ref));
            switch (refUpdate.forceUpdate()) {
            case NOT_ATTEMPTED:
            case LOCK_FAILURE:
            case REJECTED:
            case REJECTED_CURRENT_BRANCH:
            case IO_FAILURE:
            case RENAMED:
                throw new GitException("Could not update " + branch + " to " + ref);
            }

            doCheckout(branch);
        } catch (IOException e) {
            throw new GitException("Could not checkout " + branch +  " with start point " + ref, e);
        } finally {
            if (repo != null) repo.close();
        }
    }


    /** {@inheritDoc} */
    public void add(String filePattern) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            git(repo).add().addFilepattern(filePattern).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    private Git git(Repository repo) {
        return Git.wrap(repo);
    }

    /** {@inheritDoc} */
    public void commit(String message) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            CommitCommand cmd = git(repo).commit().setMessage(message);
            if (author!=null)
                cmd.setAuthor(author);
            if (committer!=null)
                cmd.setCommitter(new PersonIdent(committer,new Date()));
            cmd.call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public void branch(String name) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            git(repo).branchCreate().setName(name).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public void deleteBranch(String name) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            git(repo).branchDelete().setForce(true).setBranchNames(name).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /**
     * getBranches.
     *
     * @return a {@link java.util.Set} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    public Set<Branch> getBranches() throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            List<Ref> refs = git(repo).branchList().setListMode(ListBranchCommand.ListMode.ALL).call();
            Set<Branch> branches = new HashSet<Branch>(refs.size());
            for (Ref ref : refs) {
                branches.add(new Branch(ref));
            }
            return branches;
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /**
     * getRemoteBranches.
     *
     * @return a {@link java.util.Set} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    public Set<Branch> getRemoteBranches() throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            List<Ref> refs = git(repo).branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();
            Set<Branch> branches = new HashSet<Branch>(refs.size());
            for (Ref ref : refs) {
                branches.add(new Branch(ref));
            }
            return branches;
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public void tag(String name, String message) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            git(repo).tag().setName(name).setMessage(message).setForceUpdate(true).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public boolean tagExists(String tagName) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            Ref tag =  repo.getRefDatabase().getRef(R_TAGS + tagName);
            return tag != null;
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /**
     * fetch_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.FetchCommand} object.
     */
    public org.jenkinsci.plugins.gitclient.FetchCommand fetch_() {
        return new org.jenkinsci.plugins.gitclient.FetchCommand() {
            public URIish url;
            public List<RefSpec> refspecs;
            // JGit 3.3.0 thru 3.6.0 prune more branches than expected
            // Refer to GitAPITestCase.test_fetch_with_prune()
            private boolean shouldPrune = false;
            public boolean tags = true;

            public org.jenkinsci.plugins.gitclient.FetchCommand from(URIish remote, List<RefSpec> refspecs) {
                this.url = remote;
                this.refspecs = refspecs;
                return this;
            }

            public org.jenkinsci.plugins.gitclient.FetchCommand prune() {
                //throw new UnsupportedOperationException("JGit don't (yet) support pruning during fetch");
                shouldPrune = true;
                return this;
            }

            public org.jenkinsci.plugins.gitclient.FetchCommand shallow(boolean shallow) {
                if (shallow) {
                    listener.getLogger().println("[WARNING] JGit doesn't support shallow clone. This flag is ignored");
                }
                return this;
            }

            public org.jenkinsci.plugins.gitclient.FetchCommand timeout(Integer timeout) {
                // noop in jgit
                return this;
            }

            public org.jenkinsci.plugins.gitclient.FetchCommand tags(boolean tags) {
                this.tags = tags;
                return this;
            }

            public org.jenkinsci.plugins.gitclient.FetchCommand depth(Integer depth) {
                listener.getLogger().println("[WARNING] JGit doesn't support shallow clone and therefore depth is meaningless. This flag is ignored");
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                Repository repo = null;
                FetchCommand fetch = null;
                try {
                    repo = getRepository();
                    Git git = git(repo);

                    List<RefSpec> refSpecs = new ArrayList<RefSpec>();
                    if (tags) {
                        // see http://stackoverflow.com/questions/14876321/jgit-fetch-dont-update-tag
                        refSpecs.add(new RefSpec("+refs/tags/*:refs/tags/*"));
                    }
                    if (refspecs != null)
                        for (RefSpec rs: refspecs)
                            if (rs != null)
                                refSpecs.add(rs);

                    if (shouldPrune) {
                        // since prune is broken in JGit, we go the trivial way:
                        // delete all refs matching the right side of the refspecs
                        // then fetch and let the git recreate them.
                        List<Ref> refs = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();

                        List<String> toDelete = new ArrayList<String>(refs.size());

                        for (ListIterator<Ref> it = refs.listIterator(); it.hasNext(); ) {
                            Ref branchRef = it.next();
                            for (RefSpec rs : refSpecs) {
                                if (rs.matchDestination(branchRef)) {
                                    toDelete.add(branchRef.getName());
                                    break;
                                }
                            }
                        }
                        if (!toDelete.isEmpty()) {
                            // we need force = true because usually not all remote branches will be merged into the current branch.
                            git.branchDelete().setForce(true).setBranchNames(toDelete.toArray(new String[toDelete.size()])).call();
                        }
                    }

                    fetch = git.fetch();
                    fetch.setTagOpt(tags ? TagOpt.FETCH_TAGS : TagOpt.NO_TAGS);
                    fetch.setRemote(url.toString());
                    fetch.setCredentialsProvider(getProvider());

                    fetch.setRefSpecs(refSpecs);
                    // fetch.setRemoveDeletedRefs(shouldPrune);

                    fetch.call();
                } catch (GitAPIException e) {
                    throw new GitException(e);
                } finally {
                    if (fetch != null && fetch.getRepository() != null) fetch.getRepository().close();
                    if (repo != null) repo.close();
                }
            }
        };
    }

    /**
     * {@inheritDoc}
     *
     * @param url a {@link org.eclipse.jgit.transport.URIish} object.
     * @param refspecs a {@link java.util.List} object.
     * @throws hudson.plugins.git.GitException if any.
     * @throws java.lang.InterruptedException if any.
     */
    public void fetch(URIish url, List<RefSpec> refspecs) throws GitException, InterruptedException {
        fetch_().from(url, refspecs).execute();
    }

    /** {@inheritDoc} */
    public void fetch(String remoteName, RefSpec... refspec) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            FetchCommand fetch = git(repo).fetch().setTagOpt(TagOpt.FETCH_TAGS);
            if (remoteName != null) fetch.setRemote(remoteName);
            fetch.setCredentialsProvider(getProvider());

            // see http://stackoverflow.com/questions/14876321/jgit-fetch-dont-update-tag
            List<RefSpec> refSpecs = new ArrayList<RefSpec>();
            refSpecs.add(new RefSpec("+refs/tags/*:refs/tags/*"));
            if (refspec != null && refspec.length > 0)
                for (RefSpec rs: refspec)
                    if (rs != null)
                        refSpecs.add(rs);
            fetch.setRefSpecs(refSpecs);

            fetch.call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public void fetch(String remoteName, RefSpec refspec) throws GitException {
        fetch(remoteName, new RefSpec[] {refspec});
    }

    /** {@inheritDoc} */
    public void ref(String refName) throws GitException, InterruptedException {
	refName = refName.replace(' ', '_');
	Repository repo = null;
	try {
	    repo = getRepository();
	    RefUpdate refUpdate = repo.updateRef(refName);
	    refUpdate.setNewObjectId(repo.getRef(Constants.HEAD).getObjectId());
	    switch (refUpdate.forceUpdate()) {
	    case NOT_ATTEMPTED:
	    case LOCK_FAILURE:
	    case REJECTED:
	    case REJECTED_CURRENT_BRANCH:
	    case IO_FAILURE:
	    case RENAMED:
		throw new GitException("Could not update " + refName + " to HEAD");
	    }
	} catch (IOException e) {
	    throw new GitException("Could not update " + refName + " to HEAD", e);
	} finally {
	    if (repo != null) repo.close();
	}
    }

    /** {@inheritDoc} */
    public boolean refExists(String refName) throws GitException, InterruptedException {
	refName = refName.replace(' ', '_');
	Repository repo = null;
	try {
	    repo = getRepository();
	    Ref ref = repo.getRefDatabase().getRef(refName);
	    return ref != null;
	} catch (IOException e) {
	    throw new GitException("Error checking ref " + refName, e);
	} finally {
	    if (repo != null) repo.close();
	}
    }

    /** {@inheritDoc} */
    public void deleteRef(String refName) throws GitException, InterruptedException {
	refName = refName.replace(' ', '_');
	Repository repo = null;
	try {
	    repo = getRepository();
	    RefUpdate refUpdate = repo.updateRef(refName);
	    // Required, even though this is a forced delete.
	    refUpdate.setNewObjectId(repo.getRef(Constants.HEAD).getObjectId());
	    refUpdate.setForceUpdate(true);
	    switch (refUpdate.delete()) {
	    case NOT_ATTEMPTED:
	    case LOCK_FAILURE:
	    case REJECTED:
	    case REJECTED_CURRENT_BRANCH:
	    case IO_FAILURE:
	    case RENAMED:
		throw new GitException("Could not delete " + refName);
	    }
	} catch (IOException e) {
	    throw new GitException("Could not delete " + refName, e);
	} finally {
	    if (repo != null) repo.close();
	}
    }

    /** {@inheritDoc} */
    public Set<String> getRefNames(String refPrefix) throws GitException, InterruptedException {
	if (refPrefix.isEmpty()) {
	    refPrefix = RefDatabase.ALL;
	} else {
	    refPrefix = refPrefix.replace(' ', '_');
	}
	Repository repo = null;
	try {
	    repo = getRepository();
	    Map<String, Ref> refList = repo.getRefDatabase().getRefs(refPrefix);
	    // The key set for refList will have refPrefix removed, so to recover it we just grab the full name.
	    Set<String> refs = new HashSet<String>(refList.size());
	    for (Ref ref : refList.values()) {
		refs.add(ref.getName());
	    }
	    return refs;
	} catch (IOException e) {
	    throw new GitException("Error retrieving refs with prefix " + refPrefix, e);
	} finally {
	    if (repo != null) repo.close();
	}
    }

    /** {@inheritDoc} */
    public Map<String, ObjectId> getHeadRev(String url) throws GitException, InterruptedException {
        Map<String, ObjectId> heads = new HashMap<String, ObjectId>();
        try {
            Repository repo = openDummyRepository();
            final Transport tn = Transport.open(repo, new URIish(url));
            tn.setCredentialsProvider(getProvider());
            final FetchConnection c = tn.openFetch();
            try {
                for (final Ref r : c.getRefs()) {
                    heads.put(r.getName(), r.getPeeledObjectId() != null ? r.getPeeledObjectId() : r.getObjectId());
                }
            } finally {
                c.close();
                tn.close();
                repo.close();
            }
        } catch (IOException e) {
            throw new GitException(e);
        } catch (URISyntaxException e) {
            throw new GitException(e);
        }
        return heads;
    }

    /** {@inheritDoc} */
    public Map<String, ObjectId> getRemoteReferences(String url, String pattern, boolean headsOnly, boolean tagsOnly)
            throws GitException, InterruptedException {
        Map<String, ObjectId> references = new HashMap<String, ObjectId>();
        String regexPattern = null;
        if (pattern != null) {
            regexPattern = createRefRegexFromGlob(pattern);
        }
        try {
            Repository repo = openDummyRepository();
            LsRemoteCommand lsRemote = new LsRemoteCommand(repo);
            if (headsOnly) {
                lsRemote.setHeads(headsOnly);
            }
            if (tagsOnly) {
                lsRemote.setTags(tagsOnly);
            }
            lsRemote.setRemote(url);
            lsRemote.setCredentialsProvider(getProvider());
            Collection<Ref> refs = lsRemote.call();
            try {
                for (final Ref r : refs) {
                    final String refName = r.getName();
                    final ObjectId refObjectId =
                            r.getPeeledObjectId() != null ? r.getPeeledObjectId() : r.getObjectId();
                    if (regexPattern != null) {
                        if (refName.matches(regexPattern)) {
                            references.put(refName, refObjectId);
                        }
                    } else {
                        references.put(refName, refObjectId);
                    }
                }
            } finally {
                repo.close();
            }
        } catch (GitAPIException e) {
            throw new GitException(e);
        } catch (IOException e) {
            throw new GitException(e);
        }
        return references;
    }

    /* Adapted from http://stackoverflow.com/questions/1247772/is-there-an-equivalent-of-java-util-regex-for-glob-type-patterns */
    private String createRefRegexFromGlob(String glob)
    {
        StringBuilder out = new StringBuilder();
        if(glob.startsWith("refs/")) {
            out.append("^");
        } else {
            out.append("^.*/");
        }

        for (int i = 0; i < glob.length(); ++i) {
            final char c = glob.charAt(i);
            switch(c) {
            case '*':
                out.append(".*");
                break;
            case '?':
                out.append('.');
                break;
            case '.':
                out.append("\\.");
                break;
            case '\\':
                out.append("\\\\");
                break;
            default:
                out.append(c);
                break;
            }
        }
        out.append('$');
        return out.toString();
    }

    /** {@inheritDoc} */
    public ObjectId getHeadRev(String remoteRepoUrl, String branchSpec) throws GitException {
        try {
            final String branchName = extractBranchNameFromBranchSpec(branchSpec);
            String regexBranch = createRefRegexFromGlob(branchName);

            Repository repo = openDummyRepository();
            final Transport tn = Transport.open(repo, new URIish(remoteRepoUrl));
            tn.setCredentialsProvider(getProvider());
            final FetchConnection c = tn.openFetch();
            try {
                for (final Ref r : c.getRefs()) {
                    if (r.getName().matches(regexBranch)) {
                        return r.getPeeledObjectId() != null ? r.getPeeledObjectId() : r.getObjectId();
                    }
                }
            } finally {
                c.close();
                tn.close();
                repo.close();
            }
        } catch (IOException e) {
            throw new GitException(e);
        } catch (URISyntaxException e) {
            throw new GitException(e);
        } catch (IllegalStateException e) {
            // "Cannot open session, connection is not authenticated." from com.trilead.ssh2.Connection.openSession
            throw new GitException(e);
        }
        return null;
    }

    /**
     * Creates a empty dummy {@link Repository} to keep JGit happy where it wants a valid {@link Repository} operation
     * for remote objects.
     */
    private Repository openDummyRepository() throws IOException {
        final File tempDir = Util.createTempDir();
        return new FileRepository(tempDir) {
            @Override
            public void close() {
                super.close();
                try {
                    Util.deleteRecursive(tempDir);
                } catch (IOException e) {
                    // ignore
                }
            }
        };
    }

    /** {@inheritDoc} */
    public String getRemoteUrl(String name) throws GitException {
        final Repository repo = getRepository();
        final String url = repo.getConfig().getString("remote",name,"url");
        repo.close();
        return url;
    }

    /**
     * getRepository.
     *
     * @return a {@link org.eclipse.jgit.lib.Repository} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    @NonNull
    public Repository getRepository() throws GitException {
        try {
            return new RepositoryBuilder().setWorkTree(workspace).build();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /**
     * getWorkTree.
     *
     * @return a {@link hudson.FilePath} object.
     */
    public FilePath getWorkTree() {
        return new FilePath(workspace);
    }

    /** {@inheritDoc} */
    public void setRemoteUrl(String name, String url) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            StoredConfig config = repo.getConfig();
            config.setString("remote", name, "url", url);
            config.save();
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public void addRemoteUrl(String name, String url) throws GitException, InterruptedException {
        Repository repo = null;
        try {
            repo = getRepository();
            StoredConfig config = repo.getConfig();

            List<String> urls = new ArrayList<String>();
            urls.addAll(Arrays.asList(config.getStringList("remote", name, "url")));
            urls.add(url);

            config.setStringList("remote", name, "url", urls);
            config.save();
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public void addNote(String note, String namespace) throws GitException {
        Repository repo = null;
        RevWalk walk = null;
        ObjectReader or = null;
        try {
            repo = getRepository();
            ObjectId head = repo.resolve(HEAD); // commit to put a note on

            AddNoteCommand cmd = git(repo).notesAdd();
            cmd.setMessage(normalizeNote(note));
            cmd.setNotesRef(qualifyNotesNamespace(namespace));
            or = repo.newObjectReader();
            walk = new RevWalk(or);
            cmd.setObjectId(walk.parseAny(head));
            cmd.call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (walk != null) walk.dispose();
            if (or != null) or.release();
            if (repo != null) repo.close();
        }
    }

    /**
     * Git-notes normalizes newlines.
     *
     * This behaviour is reverse engineered from limited experiments, so it may be incomplete.
     */
    private String normalizeNote(String note) {
        note = note.trim();
        note = note.replaceAll("\r\n","\n").replaceAll("\n{3,}","\n\n");
        note += "\n";
        return note;
    }

    private String qualifyNotesNamespace(String namespace) {
        if (!namespace.startsWith("refs/")) namespace = "refs/notes/"+namespace;
        return namespace;
    }

    /** {@inheritDoc} */
    public void appendNote(String note, String namespace) throws GitException {
        Repository repo = null;
        RevWalk walk = null;
        ObjectReader or = null;
        try {
            repo = getRepository();
            ObjectId head = repo.resolve(HEAD); // commit to put a note on

            ShowNoteCommand cmd = git(repo).notesShow();
            cmd.setNotesRef(qualifyNotesNamespace(namespace));
            or = repo.newObjectReader();
            walk = new RevWalk(or);
            cmd.setObjectId(walk.parseAny(head));
            Note n = cmd.call();

            if (n==null) {
                addNote(note,namespace);
            } else {
                ObjectLoader ol = or.open(n.getData());
                StringWriter sw = new StringWriter();
                IOUtils.copy(new InputStreamReader(ol.openStream(),CHARSET),sw);
                sw.write("\n");
                addNote(sw.toString() + normalizeNote(note), namespace);
            }
        } catch (GitAPIException e) {
            throw new GitException(e);
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (walk != null) walk.dispose();
            if (or != null) or.release();
            if (repo != null) repo.close();
        }
    }

    /**
     * changelog.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    public ChangelogCommand changelog() {
        return new ChangelogCommand() {
            Repository repo = getRepository();
            ObjectReader or = repo.newObjectReader();
            RevWalk walk = new RevWalk(or);
            Writer out;
            boolean hasIncludedRev = false;

            public ChangelogCommand excludes(String rev) {
                try {
                    return excludes(repo.resolve(rev));
                } catch (IOException e) {
                    throw new GitException(e);
                }
            }

            public ChangelogCommand excludes(ObjectId rev) {
                try {
                    walk.markUninteresting(walk.lookupCommit(rev));
                    return this;
                } catch (IOException e) {
                    throw new GitException(e);
                }
            }

            public ChangelogCommand includes(String rev) {
                try {
                    includes(repo.resolve(rev));
                    hasIncludedRev = true;
                    return this;
                } catch (IOException e) {
                    throw new GitException(e);
                }
            }

            public ChangelogCommand includes(ObjectId rev) {
                try {
                    walk.markStart(walk.lookupCommit(rev));
                    hasIncludedRev = true;
                    return this;
                } catch (IOException e) {
                    throw new GitException(e);
                }
            }

            public ChangelogCommand to(Writer w) {
                this.out = w;
                return this;
            }

            public ChangelogCommand max(int n) {
                walk.setRevFilter(MaxCountRevFilter.create(n));
                return this;
            }

            private void closeResources() {
                walk.dispose();
                or.release();
                repo.close();
            }

            public void abort() {
                closeResources();
            }

            /** Execute the changelog command.  Assumed that this is
             * only performed once per instance of this object.
             * Resources opened by this ChangelogCommand object are
             * closed at exit from the execute method.  Either execute
             * or abort must be called for each ChangelogCommand or
             * files will remain open.
             */
            public void execute() throws GitException, InterruptedException {
                PrintWriter pw = new PrintWriter(out,false);
                try {
                    RawFormatter formatter= new RawFormatter();
                    if (!hasIncludedRev) {
                        /* If no rev has been included, assume HEAD */
                        this.includes("HEAD");
                    }
                    for (RevCommit commit : walk) {
                        // git whatachanged doesn't show the merge commits unless -m is given
                        if (commit.getParentCount()>1)  continue;

                        formatter.format(commit, null, pw, true);
                    }
                } catch (IOException e) {
                    throw new GitException(e);
                } finally {
                    closeResources();
                    pw.flush();
                }
            }
        };
    }

    /**
     * Formats {@link RevCommit}.
     */
    class RawFormatter {
        private boolean hasNewPath(DiffEntry d) {
            return d.getChangeType()==ChangeType.COPY || d.getChangeType()==ChangeType.RENAME;
        }

        private String statusOf(DiffEntry d) {
            switch (d.getChangeType()) {
            case ADD:       return "A";
            case MODIFY:    return "M";
            case DELETE:    return "D";
            case RENAME:    return "R"+d.getScore();
            case COPY:      return "C"+d.getScore();
            default:
                throw new AssertionError("Unexpected change type: "+d.getChangeType());
            }
        }

        public static final String ISO_8601 = "yyyy-MM-dd'T'HH:mm:ssZ";

        /**
         * Formats a commit into the raw format.
         *
         * @param commit
         *      Commit to format.
         * @param parent
         *      Optional parent commit to produce the diff against. This only matters
         *      for merge commits, and git-log/git-whatchanged/etc behaves differently with respect to this.
         */
        void format(RevCommit commit, @Nullable RevCommit parent, PrintWriter pw, Boolean useRawOutput) throws IOException {
            if (parent!=null)
                pw.printf("commit %s (from %s)\n", commit.name(), parent.name());
            else
                pw.printf("commit %s\n", commit.name());

            pw.printf("tree %s\n", commit.getTree().name());
            for (RevCommit p : commit.getParents())
                pw.printf("parent %s\n",p.name());
            FastDateFormat iso = FastDateFormat.getInstance(ISO_8601);
            PersonIdent a = commit.getAuthorIdent();
            pw.printf("author %s <%s> %s\n", a.getName(), a.getEmailAddress(), iso.format(a.getWhen()));
            PersonIdent c = commit.getCommitterIdent();
            pw.printf("committer %s <%s> %s\n", c.getName(), c.getEmailAddress(), iso.format(c.getWhen()));

            // indent commit messages by 4 chars
            String msg = commit.getFullMessage();
            if (msg.endsWith("\n")) msg=msg.substring(0,msg.length()-1);
            msg = msg.replace("\n","\n    ");
            msg="\n    "+msg+"\n";

            pw.println(msg);

            // see man git-diff-tree for the format
            Repository repo = getRepository();
            ObjectReader or = repo.newObjectReader();
            TreeWalk tw = new TreeWalk(or);
            if (parent != null) {
                /* Caller provided a parent commit, use it */
                tw.reset(parent.getTree(), commit.getTree());
            } else {
                if (commit.getParentCount() > 0) {
                    /* Caller failed to provide parent, but a parent
                     * is available, so use the parent in the walk
                     */
                    tw.reset(commit.getParent(0).getTree(), commit.getTree());
                } else {
                    /* First commit in repo has 0 parent count, but
                     * the TreeWalk requires exactly two nodes for its
                     * walk.  Use the same node twice to satisfy
                     * TreeWalk. See JENKINS-22343 for details.
                     */
                    tw.reset(commit.getTree(), commit.getTree());
                }
            }
            tw.setRecursive(true);
            tw.setFilter(TreeFilter.ANY_DIFF);

            final RenameDetector rd = new RenameDetector(repo);

            rd.reset();
            rd.addAll(DiffEntry.scan(tw));
            List<DiffEntry> diffs = rd.compute(or, null);
            tw.release();
            or.release();
            repo.close();
            if (useRawOutput) {
	            for (DiffEntry diff : diffs) {
	                pw.printf(":%06o %06o %s %s %s\t%s",
	                        diff.getOldMode().getBits(),
	                        diff.getNewMode().getBits(),
	                        diff.getOldId().name(),
	                        diff.getNewId().name(),
	                        statusOf(diff),
	                        diff.getChangeType()==ChangeType.ADD ? diff.getNewPath() : diff.getOldPath());

	                if (hasNewPath(diff)) {
	                    pw.printf(" %s",diff.getNewPath()); // copied to
	                }
	                pw.println();
	                pw.println();
	            }
            }
        }
    }

    /**
     * clean.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    public void clean() throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            Git git = git(repo);
            git.reset().setMode(HARD).call();
            git.clean().setCleanDirectories(true).setIgnore(false).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /**
     * clone_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    public CloneCommand clone_() {

        return new CloneCommand() {
            String url;
            String remote = Constants.DEFAULT_REMOTE_NAME;
            String reference;
            Integer timeout;
            boolean shared;
            boolean tags = true;
            List<RefSpec> refspecs;

            public CloneCommand url(String url) {
                this.url = url;
                return this;
            }

            public CloneCommand repositoryName(String name) {
                this.remote = name;
                return this;
            }

            public CloneCommand shallow() {
                listener.getLogger().println("[WARNING] JGit doesn't support shallow clone. This flag is ignored");
                return this;
            }

            public CloneCommand shared() {
                this.shared = true;
                return this;
            }

            public CloneCommand reference(String reference) {
                this.reference = reference;
                return this;
            }

            public CloneCommand refspecs(List<RefSpec> refspecs) {
                this.refspecs = new ArrayList<RefSpec>(refspecs); 
                return this;
            }

            public CloneCommand timeout(Integer timeout) {
            	this.timeout = timeout;
            	return this;
            }

            public CloneCommand tags(boolean tags) {
                this.tags = tags;
                return this;
            }

            public CloneCommand noCheckout() {
                // this.noCheckout = true; ignored, we never do a checkout
                return this;
            }

            public CloneCommand depth(Integer depth) {
                listener.getLogger().println("[WARNING] JGit doesn't support shallow clone and therefore depth is meaningless. This flag is ignored");
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                Repository repository = null;

                try {
                    // the directory needs to be clean or else JGit complains
                    if (workspace.exists())
                        Util.deleteContentsRecursive(workspace);

                    // since jgit clone/init commands do not support object references (e.g. alternates),
                    // we build the repository directly using the RepositoryBuilder

                    RepositoryBuilder builder = new RepositoryBuilder();
                    builder.readEnvironment().setGitDir(new File(workspace, Constants.DOT_GIT));

                    if (shared) {
                        if (reference == null || reference.isEmpty()) {
                            // we use origin as reference
                            reference = url;
                        } else {
                            listener.getLogger().println("[WARNING] Both 'shared' and 'reference' is used, shared is ignored.");
                        }
                    }

                    if (reference != null && !reference.isEmpty())
                        builder.addAlternateObjectDirectory(new File(reference));

                    repository = builder.build();
                    repository.create();

                    // the repository builder does not create the alternates file
                    if (reference != null && !reference.isEmpty()) {
                        File referencePath = new File(reference);
                        if (!referencePath.exists())
                            listener.error("Reference path does not exist: " + reference);
                        else if (!referencePath.isDirectory())
                            listener.error("Reference path is not a directory: " + reference);
                        else {
                            // reference path can either be a normal or a base repository
                            File objectsPath = new File(referencePath, ".git/objects");
                            if (!objectsPath.isDirectory()) {
                                // reference path is bare repo
                                objectsPath = new File(referencePath, "objects");
                            }
                            if (!objectsPath.isDirectory())
                                listener.error("Reference path does not contain an objects directory (no git repo?): " + objectsPath);
                            else {
                                try {
                                    File alternates = new File(workspace, ".git/objects/info/alternates");
                                    PrintWriter w = new PrintWriter(alternates, "UTF-8");
                                    // git implementations on windows also use
                                    w.print(objectsPath.getAbsolutePath().replace('\\', '/'));
                                    w.close();
                                } catch (FileNotFoundException e) {
                                    listener.error("Failed to setup reference");
                                }
                            }
                        }
                    }

                    // Jgit repository has alternates directory set, but seems to ignore them
                    // Workaround: close this repo and create a new one
                    repository.close();
                    repository = getRepository();

                    if (refspecs == null) {
                        refspecs = Collections.singletonList(new RefSpec("+refs/heads/*:refs/remotes/"+remote+"/*"));
                    }
                    FetchCommand fetch = new Git(repository).fetch()
                            .setProgressMonitor(new JGitProgressMonitor(listener))
                            .setRemote(url)
                            .setCredentialsProvider(getProvider())
                            .setTagOpt(tags ? TagOpt.FETCH_TAGS : TagOpt.NO_TAGS)
                            .setRefSpecs(refspecs);
                    if (timeout != null) fetch.setTimeout(timeout);
                    fetch.call();

                    StoredConfig config = repository.getConfig();
                    config.setString("remote", remote, "url", url);
                    config.setStringList("remote", remote, "fetch", Lists.newArrayList(Iterables.transform(refspecs, Functions.toStringFunction())));
                    config.save();

                } catch (GitAPIException e) {
                    throw new GitException(e);
                } catch (IOException e) {
                    throw new GitException(e);
                } finally {
                    if (repository != null) repository.close();
                }
            }
        };
    }

    /**
     * merge.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.MergeCommand} object.
     */
    public MergeCommand merge() {
        return new MergeCommand() {

            ObjectId rev;
            MergeStrategy strategy;
            FastForwardMode fastForwardMode;
            boolean squash;
            boolean commit = true;
            String comment;

            public MergeCommand setRevisionToMerge(ObjectId rev) {
                this.rev = rev;
                return this;
            }

            public MergeCommand setStrategy(MergeCommand.Strategy strategy) {
                if (strategy != null && !strategy.toString().isEmpty() && strategy != MergeCommand.Strategy.DEFAULT) {
                    if (strategy == MergeCommand.Strategy.OURS) {
                        this.strategy = MergeStrategy.OURS;
                        return this;
                    }
                    if (strategy == MergeCommand.Strategy.RESOLVE) {
                        this.strategy = MergeStrategy.RESOLVE;
                        return this;
                    }
                    if (strategy == MergeCommand.Strategy.OCTOPUS) {
                        this.strategy = MergeStrategy.SIMPLE_TWO_WAY_IN_CORE;
                        return this;
                    }
                    listener.getLogger().println("[WARNING] JGit doesn't fully support merge strategies. This flag is ignored");
                }
                return this;
            }

            public MergeCommand setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode fastForwardMode) {
                if (fastForwardMode == MergeCommand.GitPluginFastForwardMode.FF) {
                    this.fastForwardMode = FastForwardMode.FF;
                } else if (fastForwardMode == MergeCommand.GitPluginFastForwardMode.FF_ONLY) {
                    this.fastForwardMode = FastForwardMode.FF_ONLY;
                } else if (fastForwardMode == MergeCommand.GitPluginFastForwardMode.NO_FF) {
                    this.fastForwardMode = FastForwardMode.NO_FF;
                }
                return this;
            }

            public MergeCommand setSquash(boolean squash){
                this.squash = squash;
                return this;
            }

            public MergeCommand setMessage(String comment) {
                this.comment = comment;
                return this;
            }

            public MergeCommand setCommit(boolean commit) {
                this.commit = commit;
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                Repository repo = null;
                try {
                    repo = getRepository();
                    Git git = git(repo);
                    MergeResult mergeResult;
                    if (strategy != null)
                        mergeResult = git.merge().setMessage(comment).setStrategy(strategy).setFastForward(fastForwardMode).setSquash(squash).setCommit(commit).include(rev).call();
                    else
                        mergeResult = git.merge().setMessage(comment).setFastForward(fastForwardMode).setSquash(squash).setCommit(commit).include(rev).call();
                    if (!mergeResult.getMergeStatus().isSuccessful()) {
                        git.reset().setMode(HARD).call();
                        throw new GitException("Failed to merge " + rev);
                    }
                } catch (GitAPIException e) {
                    throw new GitException("Failed to merge " + rev, e);
                } finally {
                    if (repo != null) repo.close();
                }
            }
        };
    }

    /**
     * init_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.InitCommand} object.
     */
    public InitCommand init_() {
        return new InitCommand() {

            public String workspace;
            public boolean bare;

            public InitCommand workspace(String workspace) {
                this.workspace = workspace;
                return this;
            }

            public InitCommand bare(boolean bare) {
                this.bare = bare;
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                doInit(workspace, bare);
            }
        };
    }

    public RebaseCommand rebase() {
        return new RebaseCommand() {
            private String upstream;

            public RebaseCommand setUpstream(String upstream) {
                this.upstream = upstream;
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                Repository repo = null;
                try {
                    repo = getRepository();
                    Git git = git(repo);
                    RebaseResult rebaseResult = git.rebase().setUpstream(upstream).call();
                    if (!rebaseResult.getStatus().isSuccessful()) {
                        git.rebase().setOperation(Operation.ABORT).call();
                        throw new GitException("Failed to rebase " + upstream);
                    }
                } catch (GitAPIException e) {
                    throw new GitException("Failed to rebase " + upstream, e);
                } finally {
                    if (repo != null) repo.close();
                }
            }
        };
    }

    /** {@inheritDoc} */
    public void deleteTag(String tagName) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            git(repo).tagDelete().setTags(tagName).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public String getTagMessage(String tagName) throws GitException {
        Repository repo = null;
        ObjectReader or = null;
        RevWalk walk = null;
        try {
            repo = getRepository();
            or = repo.newObjectReader();
            walk = new RevWalk(or);
            return walk.parseTag(repo.resolve(tagName)).getFullMessage().trim();
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
             if (walk != null) walk.dispose();
             if (or != null) or.release();
             if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public List<IndexEntry> getSubmodules(String treeIsh) throws GitException {
        Repository repo = null;
        ObjectReader or = null;
        RevWalk w = null;
        try {
            List<IndexEntry> r = new ArrayList<IndexEntry>();

            repo = getRepository();
            or = repo.newObjectReader();
            w=new RevWalk(or);
            RevTree t = w.parseTree(repo.resolve(treeIsh));
            SubmoduleWalk walk = new SubmoduleWalk(repo);
            walk.setTree(t);
            walk.setRootTree(t);
            while (walk.next()) {
                r.add(new IndexEntry(walk));
            }

            return r;
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (w != null) w.dispose();
            if (or != null) or.release();
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public void addSubmodule(String remoteURL, String subdir) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            git(repo).submoduleAdd().setPath(subdir).setURI(remoteURL).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public Set<String> getTagNames(String tagPattern) throws GitException {
        if (tagPattern == null) tagPattern = "*";

        Repository repo = null;
        try {
            Set<String> tags = new HashSet<String>();
            FileNameMatcher matcher = new FileNameMatcher(tagPattern, '/');
            repo = getRepository();
            Map<String, Ref> refList = repo.getRefDatabase().getRefs(R_TAGS);
            for (Ref ref : refList.values()) {
                String name = ref.getName().substring(R_TAGS.length());
                matcher.reset();
                matcher.append(name);
                if (matcher.isMatch()) tags.add(name);
            }
            return tags;
        } catch (IOException e) {
            throw new GitException(e);
        } catch (InvalidPatternException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public Set<String> getRemoteTagNames(String tagPattern) throws GitException {
        if (tagPattern == null) tagPattern = "*";

        Repository repo = null;
        try {
            Set<String> tags = new HashSet<String>();
            FileNameMatcher matcher = new FileNameMatcher(tagPattern, '/');
            repo = getRepository();
            Map<String, Ref> refList = repo.getRefDatabase().getRefs(R_TAGS);
            for (Ref ref : refList.values()) {
                String name = ref.getName().substring(R_TAGS.length());
                matcher.reset();
                matcher.append(name);
                if (matcher.isMatch()) tags.add(name);
            }
            return tags;
        } catch (IOException e) {
            throw new GitException(e);
        } catch (InvalidPatternException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /**
     * hasGitRepo.
     *
     * @return true if this workspace has a git repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    public boolean hasGitRepo() throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            return repo.getObjectDatabase().exists();
        } catch (GitException e) {
            return false;
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public boolean isCommitInRepo(ObjectId commit) throws GitException {
        if (commit == null) {
            return false;
        }
        final Repository repo = getRepository();
        final boolean found = repo.hasObject(commit);
        repo.close();
        return found;
    }

    /** {@inheritDoc} */
    public void prune(RemoteConfig repository) throws GitException {
        Repository gitRepo = null;
        try {
            String remote = repository.getName();
            String prefix = "refs/remotes/" + remote + "/";

            Set<String> branches = listRemoteBranches(remote);

            gitRepo = getRepository();
            for (Ref r : new ArrayList<Ref>(gitRepo.getAllRefs().values())) {
                if (r.getName().startsWith(prefix) && !branches.contains(r.getName())) {
                    // delete this ref
                    RefUpdate update = gitRepo.updateRef(r.getName());
                    update.setRefLogMessage("remote branch pruned", false);
                    update.setForceUpdate(true);
                    Result res = update.delete();
                }
            }
        } catch (URISyntaxException e) {
            throw new GitException(e);
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (gitRepo != null) gitRepo.close();
        }
    }

    private Set<String> listRemoteBranches(String remote) throws NotSupportedException, TransportException, URISyntaxException {
        final Repository repo = getRepository();
        StoredConfig config = repo.getConfig();

        Set<String> branches = new HashSet<String>();
        final Transport tn = Transport.open(repo, new URIish(config.getString("remote",remote,"url")));
        tn.setCredentialsProvider(getProvider());
        final FetchConnection c = tn.openFetch();
        try {
            for (final Ref r : c.getRefs()) {
                if (r.getName().startsWith(R_HEADS))
                    branches.add("refs/remotes/"+remote+"/"+r.getName().substring(R_HEADS.length()));
            }
        } finally {
            repo.close();
            c.close();
            tn.close();
        }
        return branches;
    }

    /**
     * push.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     */
    public PushCommand push() {
        return new PushCommand() {
            public URIish remote;
            public String refspec;
            public boolean force;
            public boolean tags;

            public PushCommand to(URIish remote) {
                this.remote = remote;
                return this;
            }

            public PushCommand ref(String refspec) {
                this.refspec = refspec;
                return this;
            }

            public PushCommand force() {
                this.force = true;
                return this;
            }

            public PushCommand tags(boolean tags) {
                this.tags = tags;
                return this;
            }

            public PushCommand timeout(Integer timeout) {
            	// noop in jgit
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                Repository repo = null;
                try {
                    repo = getRepository();
                    RefSpec ref = (refspec != null) ? new RefSpec(fixRefSpec(repo)) : Transport.REFSPEC_PUSH_ALL;
                    listener.getLogger().println("RefSpec is \""+ref+"\".");
                    Git g = git(repo);
                    Config config = g.getRepository().getConfig();
                    config.setString("remote", "org_jenkinsci_plugins_gitclient_JGitAPIImpl", "url", remote.toPrivateASCIIString());
                    org.eclipse.jgit.api.PushCommand pc = g.push().setRemote("org_jenkinsci_plugins_gitclient_JGitAPIImpl").setRefSpecs(ref)
                            .setProgressMonitor(new JGitProgressMonitor(listener))
                            .setCredentialsProvider(getProvider())
                            .setForce(force);
                    if(tags) {
                        pc.setPushTags();
                    }
                    Iterable<PushResult> results = pc.call();
                    for(PushResult result:results) for(RemoteRefUpdate update:result.getRemoteUpdates()) {
                        RemoteRefUpdate.Status status = update.getStatus();
                        if(!OK.equals(status)&&!UP_TO_DATE.equals(status)) {
                            throw new GitException(update.getMessage() + " " + status + " for '" + ref + 
                                "' refspec '" + refspec + "' to " + remote.toPrivateASCIIString());
                        }
                    }
                    config.unset("remote", "org_jenkinsci_plugins_gitclient_JGitAPIImpl", "url");
                } catch (IOException e) {
                    throw new GitException(e);
                } catch (JGitInternalException je) {
                    throw new GitException(je);
                } catch (GitAPIException e) {
                    throw new GitException(e);
                } finally {
                    if (repo != null) repo.close();
                }
            }

            /**
             * Currently JGit does not parse refspecs as well as Git CLI.
             * This method attempts to fix the refspec as a workaround until JGit
             * implements parsing arbitrary refspecs (see JENKINS-20393).
             *
             * @return a (hopefully) fixed refspec string.
             */
            private String fixRefSpec(Repository repository) throws IOException {
                int colon = refspec.indexOf(':');
                String[] specs = new String[]{(colon != -1 ? refspec.substring(0, colon) : refspec).trim(), refspec.substring(colon + 1).trim()};
                for (int spec = 0; spec < specs.length; spec++) {
                    if (specs[spec].isEmpty() || "HEAD".equalsIgnoreCase(specs[spec])) {
                        switch (spec) {
                            default:
                            case 0:
                                break; //empty / HEAD for the first ref. if fine for JGit (see https://github.com/eclipse/jgit/blob/master/org.eclipse.jgit/src/org/eclipse/jgit/transport/RefSpec.java#L104-L122)
                            case 1: //empty second ref. generally means to push "matching" branches, hard to implement the right way, same goes for special case "HEAD" / "HEAD:HEAD" simple-fix here
                                specs[spec] = repository.getFullBranch();
                                break;
                        }
                    } else if (!specs[spec].startsWith("refs/") && !specs[spec].startsWith("+refs/")) {
                        switch (spec) {
                            default:
                            case 0: //for the source ref. we use the repository to determine what should be pushed
                                Ref ref = repository.getRef(specs[spec]);
                                if (ref == null) {
                                    throw new IOException(String.format("Ref %s not found.", specs[spec]));
                                }
                                specs[spec] = ref.getTarget().getName();
                                break;
                            case 1: //for the target ref. we can't use the repository, so we try our best to determine the ref. (see http://git.661346.n2.nabble.com/JGit-Push-to-new-Amazon-S3-does-not-work-quot-funny-refname-quot-td2441026.html)
                                if (!specs[spec].startsWith("/")) {
                                    specs[spec] = "/" + specs[spec];
                                }
                                if (!specs[spec].startsWith("/heads/") && !specs[spec].startsWith("/remotes/") && !specs[spec].startsWith("/tags/")) {
                                    specs[spec] = "/heads" + specs[spec];
                                }
                                specs[spec] = "refs" + specs[spec];
                                break;
                        }
                    }
                }
                return specs[0] + ":" + specs[1];
            }
        };
    }

    /**
     * revList_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     */
    public RevListCommand revList_()
    {
        return new RevListCommand() {
            public boolean all;
            public boolean firstParent;
            public String refspec;
            public List<ObjectId> out;

            public RevListCommand all() {
                this.all = true;
                return this;
            }

            public RevListCommand firstParent() {
                this.firstParent = true;
                return this;
            }

            public RevListCommand to(List<ObjectId> revs){
                this.out = revs;
                return this;
            }

            public RevListCommand reference(String reference){
                this.refspec = reference;
                return this;
            }

            public void execute() throws GitException, InterruptedException {

                Repository repo = null;
                ObjectReader or = null;
                RevWalk walk = null;

                if (firstParent) {
                  throw new UnsupportedOperationException("not implemented yet");
                }

                try {
                    repo = getRepository();
                    or = repo.newObjectReader();
                    walk = new RevWalk(or);

                    if (all)
                    {
                        markAllRefs(walk);
                    }
                    else if (refspec != null)
                    {
                        walk.markStart(walk.parseCommit(repo.resolve(refspec)));
                    }

                    walk.setRetainBody(false);
                    walk.sort(RevSort.COMMIT_TIME_DESC);

                    for (RevCommit c : walk) {
                        out.add(c.copy());
                    }
                } catch (IOException e) {
                    throw new GitException(e);
                } finally {
                    if (walk != null) walk.dispose();
                    if (or != null) or.release();
                    if (repo != null) repo.close();
                }
            }
        };
    }

    /**
     * revListAll.
     *
     * @return a {@link java.util.List} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    public List<ObjectId> revListAll() throws GitException {
        List<ObjectId> oidList = new ArrayList<ObjectId>();
        RevListCommand revListCommand = revList_();
        revListCommand.all();
        revListCommand.to(oidList);
        try {
            revListCommand.execute();
        } catch (InterruptedException e) {
            throw new GitException(e);
        }
        return oidList;
    }

    /** {@inheritDoc} */
    public List<ObjectId> revList(String ref) throws GitException {
        List<ObjectId> oidList = new ArrayList<ObjectId>();
        RevListCommand revListCommand = revList_();
        revListCommand.reference(ref);
        revListCommand.to(oidList);
        try {
            revListCommand.execute();
        } catch (InterruptedException e) {
            throw new GitException(e);
        }
        return oidList;
    }

    /** {@inheritDoc} */
    public ObjectId revParse(String revName) throws GitException {
        Repository repo = null;
        try {
            repo = getRepository();
            ObjectId id = repo.resolve(revName + "^{commit}");
            if (id == null)
                throw new GitException("Unknown git object "+ revName);
            return id;
        } catch (IOException e) {
            throw new GitException("Failed to resolve git reference "+ revName, e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException {
        return showRevision(from, to, true);
    }

    /** {@inheritDoc} */
    public List<String> showRevision(ObjectId from, ObjectId to, Boolean useRawOutput) throws GitException {
        Repository repo = null;
        ObjectReader or = null;
        RevWalk w = null;
        try {
            repo = getRepository();
            or = repo.newObjectReader();
            w = new RevWalk(or);
            w.markStart(w.parseCommit(to));
            if (from!=null)
                w.markUninteresting(w.parseCommit(from));
            else
                w.setRevFilter(MaxCountRevFilter.create(1));

            List<String> r = new ArrayList<String>();
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            RawFormatter f = new RawFormatter();
            for (RevCommit c : w) {
                // do not duplicate merge commits unless using raw output
                if (c.getParentCount()<=1 || !useRawOutput) {
                    f.format(c,null,pw,useRawOutput);
                } else {
                    // the effect of the -m option, which makes the diff produce for each parent of a merge commit
                    for (RevCommit p : c.getParents()) {
                        f.format(c,p,pw,useRawOutput);
                    }
                }

                pw.flush();
                r.addAll(Arrays.asList(sw.toString().split("\n")));
                sw.getBuffer().setLength(0);
            }
            return r;
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (w != null) w.dispose();
            if (or != null) or.release();
            if (repo != null) repo.close();
        }
    }

    private Iterable<JGitAPIImpl> submodules() throws IOException {
        List<JGitAPIImpl> submodules = new ArrayList<JGitAPIImpl>();
        final Repository repo = getRepository();
        SubmoduleWalk generator = SubmoduleWalk.forIndex(repo);
        while (generator.next()) {
            submodules.add(new JGitAPIImpl(generator.getDirectory(), listener));
        }
        repo.close();
        return submodules;
    }

    /** {@inheritDoc} */
    public void submoduleClean(boolean recursive) throws GitException {
        try {
            for (JGitAPIImpl sub : submodules()) {
                sub.clean();
                if (recursive) {
                    sub.submoduleClean(true);
                }
            }
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /**
     * submoduleUpdate.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand} object.
     */
    public SubmoduleUpdateCommand submoduleUpdate() {
        return new SubmoduleUpdateCommand() {
            boolean recursive      = false;
            boolean force          = false;
            boolean remoteTracking = false;
            String  ref            = null;

            public SubmoduleUpdateCommand recursive(boolean recursive) {
                this.recursive = recursive;
                return this;
            }

            public SubmoduleUpdateCommand force(boolean force){
                this.force = force;
                return this;
            }

            public SubmoduleUpdateCommand remoteTracking(boolean remoteTracking) {
                this.remoteTracking = remoteTracking;
                return this;
            }

            public SubmoduleUpdateCommand ref(String ref) {
                this.ref = ref;
                return this;
            }

            public SubmoduleUpdateCommand timeout(Integer timeout) {
            	// noop in jgit
                return this;
            }

            public SubmoduleUpdateCommand useBranch(String submodule, String branchname) {
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                Repository repo = null;

                if (remoteTracking) {
                    listener.getLogger().println("[ERROR] JGit doesn't support remoteTracking submodules yet.");
                    throw new UnsupportedOperationException("not implemented yet");
                }
                if (force) {
                    listener.getLogger().println("[ERROR] JGit doesn't support force updating submodules yet.");
                    throw new UnsupportedOperationException("not implemented yet");
                }
                if ((ref != null) && !ref.isEmpty()) {
                    listener.getLogger().println("[ERROR] JGit doesn't support submodule update --reference yet.");
                    throw new UnsupportedOperationException("not implemented yet");
                }

                try {
                    repo = getRepository();
                    git(repo).submoduleUpdate().call();
                    if (recursive) {
                        for (JGitAPIImpl sub : submodules()) {
                            sub.submoduleUpdate(recursive);
                        }
                    }
                } catch (IOException e) {
                    throw new GitException(e);
                } catch (GitAPIException e) {
                    throw new GitException(e);
                } finally {
                    if (repo != null) repo.close();
                }
            }
        };
    }





    //
    //
    // Legacy Implementation of IGitAPI
    //
    //

    /** {@inheritDoc} */
    @Deprecated
    public void merge(String refSpec) throws GitException, InterruptedException {
        Repository repo = null;
        try {
            repo = getRepository();
            merge(repo.resolve(refSpec));
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    public void push(RemoteConfig repository, String refspec) throws GitException, InterruptedException {
        push(repository.getName(),refspec);
    }

    /** {@inheritDoc} */
    public List<Branch> getBranchesContaining(String revspec) throws GitException, InterruptedException {
        // For the reasons of backward compatibility - we do not query remote branches here.
        return getBranchesContaining(revspec, false);
    }

    /**
     * {@inheritDoc}
     *
     * "git branch --contains=X" is a pretty plain traversal. We walk the commit graph until we find the target
     * revision we want.
     *
     * Doing this individually for every branch is too expensive, so we use flags to track multiple branches
     * at once. JGit gives us 24 bits of flags, so we divide up all the branches to batches of 24, then
     * perform a graph walk. For flags to carry correctly over from children to parents, all the children
     * must be visited before we see the parent. This requires a topological sorting order. In addition,
     * we want kind of a "breadth first search" to avoid going down a part of the graph that's not terribly
     * interesting and topo sort helps with that, too (imagine the following commit graph,
     * and compute "git branch --contains=t"; we don't want to visit all the way to c1 before visiting c.)
     *
     *
     *   INIT -&gt; c1 -&gt; c2 -&gt; ... long history of commits --+--&gt; c1000 --+--&gt; branch1
     *                                                     |            |
     *                                                      --&gt; t ------
     *
     * <p>
     * Since we reuse {@link RevWalk}, it'd be nice to flag commits reachable from 't' as uninteresting
     * and keep them across resets, but I'm not sure how to do it.
     */
    public List<Branch> getBranchesContaining(String revspec, boolean allBranches) throws GitException, InterruptedException {
        Repository repo = null;
        ObjectReader or = null;
        RevWalk walk = null;
        try {
            repo = getRepository();
            or = repo.newObjectReader();
            walk = new RevWalk(or);
            walk.setRetainBody(false);
            walk.sort(RevSort.TOPO);// so that by the time we hit target we have all that we want

            ObjectId id = repo.resolve(revspec);
            if (id==null)   throw new GitException("Invalid commit: "+revspec);
            RevCommit target = walk.parseCommit(id);

            // we can track up to 24 flags at a time in JGit, so that's how many branches we will traverse in every iteration
            List<RevFlag> flags = new ArrayList<RevFlag>(24);
            for (int i=0; i<24; i++)
                flags.add(walk.newFlag("branch" + i));
            walk.carry(flags);

            List<Branch> result = new ArrayList<Branch>();  // we'll built up the return value in here

            List<Ref> branches = getAllBranchRefs(allBranches);
            while (!branches.isEmpty()) {
                List<Ref> batch = branches.subList(0,Math.min(flags.size(),branches.size()));
                branches = branches.subList(batch.size(),branches.size());  // remaining

                walk.reset();
                int idx=0;
                for (Ref r : batch) {
                    RevCommit c = walk.parseCommit(r.getObjectId());
                    walk.markStart(c);
                    c.add(flags.get(idx));
                    idx++;
                }

                // anything reachable from the target commit in question is not worth traversing.
                for (RevCommit p : target.getParents()) {
                    walk.markUninteresting(p);
                }

                for (RevCommit c : walk) {
                    if (c.equals(target))
                        break;
                }


                idx=0;
                for (Ref r : batch) {
                    if (target.has(flags.get(idx))) {
                        result.add(new Branch(r));
                    }
                    idx++;
                }
            }

            return result;
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (walk != null) walk.dispose();
            if (or != null) or.release();
            if (repo != null) repo.close();
        }
    }

    private List<Ref> getAllBranchRefs(boolean originBranches) {
        List<Ref> branches = new ArrayList<Ref>();
        final Repository repo = getRepository();
        for (Ref r : repo.getAllRefs().values()) {
            final String branchName = r.getName();
            if (branchName.startsWith(R_HEADS)
                    || (originBranches && branchName.startsWith(R_REMOTES))) {
                branches.add(r);
            }
        }
        repo.close();
        return branches;
    }

    /** {@inheritDoc} */
    @Deprecated
    public ObjectId mergeBase(ObjectId id1, ObjectId id2) throws InterruptedException {
        Repository repo = null;
        ObjectReader or = null;
        RevWalk walk = null;
        try {
            repo = getRepository();
            or = repo.newObjectReader();
            walk = new RevWalk(or);
            walk.setRetainBody(false);  // we don't need the body for this computation
            walk.setRevFilter(RevFilter.MERGE_BASE);

            walk.markStart(walk.parseCommit(id1));
            walk.markStart(walk.parseCommit(id2));

            RevCommit base = walk.next();
            if (base==null)     return null;    // no common base
            return base.getId();
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (walk != null) walk.dispose();
            if (or != null) or.release();
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    public String getAllLogEntries(String branch) throws InterruptedException {
        Repository repo = null;
        ObjectReader or = null;
        RevWalk walk = null;
        try {
            StringBuilder w = new StringBuilder();

            repo = getRepository();
            or = repo.newObjectReader();
            walk = new RevWalk(or);
            markAllRefs(walk);
            walk.setRetainBody(false);

            for (RevCommit c : walk) {
                w.append('\'').append(c.name()).append('#').append(c.getCommitTime()).append("'\n");
            }
            return w.toString().trim();
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (walk != null) walk.dispose();
            if (or != null) or.release();
            if (repo != null) repo.close();
        }
    }

    /**
     * Adds all the refs as start commits.
     */
    private void markAllRefs(RevWalk walk) throws IOException {
        markRefs(walk, Predicates.<Ref>alwaysTrue());
    }

    /**
     * Adds all matching refs as start commits.
     */
    private void markRefs(RevWalk walk, Predicate<Ref> filter) throws IOException {
        Repository repo = getRepository();
        for (Ref r : repo.getAllRefs().values()) {
            if (filter.apply(r)) {
                RevCommit c = walk.parseCommit(r.getObjectId());
                walk.markStart(c);
            }
        }
        repo.close();
    }

    static class PrefixPredicate implements Predicate<Ref> {
        private final String prefix;

        PrefixPredicate(String prefix) {
            this.prefix = prefix;
        }

        public boolean apply(Ref r) {
            return r.getName().startsWith(prefix);
        }
    }

    /**
     * submoduleInit.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    public void submoduleInit() throws GitException, InterruptedException {
        Repository repo = null;
        try {
            repo = getRepository();
            git(repo).submoduleInit().call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /**
     * submoduleSync.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    public void submoduleSync() throws GitException, InterruptedException {
        Repository repo = null;
        try {
            repo = getRepository();
            git(repo).submoduleSync().call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    public String getSubmoduleUrl(String name) throws GitException, InterruptedException {
        Repository repo = getRepository();
        String v = repo.getConfig().getString("submodule", name, "url");
        repo.close();
        if (v==null)    throw new GitException("No such submodule: "+name);
        return v.trim();
    }

    /** {@inheritDoc} */
    @Deprecated
    public void setSubmoduleUrl(String name, String url) throws GitException, InterruptedException {
        Repository repo = null;
        try {
            repo = getRepository();
            StoredConfig config = repo.getConfig();
            config.setString("submodule", name, "url", url);
            config.save();
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /**
     * {@inheritDoc}
     *
     * I don't think anyone is using this method, and I don't think we ever need to implement this.
     *
     * This kind of logic doesn't belong here, as it lacks generality. It should be
     * whoever manipulating Git.
     */
    @Deprecated
    public void setupSubmoduleUrls(Revision rev, TaskListener listener) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * {@inheritDoc}
     *
     * I don't think anyone is using this method, and I don't think we ever need to implement this.
     *
     * This kind of logic doesn't belong here, as it lacks generality. It should be
     * whoever manipulating Git.
     */
    @Deprecated
    public void fixSubmoduleUrls(String remote, TaskListener listener) throws GitException, InterruptedException {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     *
     * This implementation is based on my reading of the cgit source code at https://github.com/git/git/blob/master/builtin/describe.c
     *
     * <p>
     * The basic structure of the algorithm is as follows. We walk the commit graph,
     * find tags, and mark commits that are reachable from those tags. The marking
     * uses flags given by JGit, so there's a fairly small upper bound in the number of tags
     * we can keep track of.
     *
     * <p>
     * As we walk commits, we count commits that each tag doesn't contain.
     * We call it "depth", following the variable name in C Git.
     * As we walk further and find enough tags, we go into wind-down mode and only walk
     * to the point of accurately determining all the depths.
     */
    public String describe(String tip) throws GitException, InterruptedException {
        Repository repo = null;
        try {
            repo = getRepository();
            final ObjectReader or = repo.newObjectReader();
            final RevWalk w = new RevWalk(or); // How to dispose of this ?
            w.setRetainBody(false);

            Map<ObjectId,Ref> tags = new HashMap<ObjectId, Ref>();
            for (Ref r : repo.getTags().values()) {
                ObjectId key = repo.peel(r).getPeeledObjectId();
                if (key==null)  key = r.getObjectId();
                tags.put(key, r);
            }

            final RevFlagSet allFlags = new RevFlagSet(); // combined flags of all the Candidate instances

            /**
             * Tracks the depth of each tag as we find them.
             */
            class Candidate {
                final RevCommit commit;
                final Ref tag;
                final RevFlag flag;

                /**
                 * This field number of commits that are reachable from the tip but
                 * not reachable from the tag.
                 */
                int depth;

                Candidate(RevCommit commit, Ref tag) {
                    this.commit = commit;
                    this.tag = tag;
                    this.flag = w.newFlag(tag.getName());
                    // we'll mark all the nodes reachable from this tag accordingly
                    allFlags.add(flag);
                    w.carry(flag);
                    commit.add(flag);
                    commit.carry(flag);
                }

                /**
                 * Does this tag contains the given commit?
                 */
                public boolean reaches(RevCommit c) {
                    return c.has(flag);
                }

                public String describe(ObjectId tip) throws IOException {
                    return String.format("%s-%d-g%s", tag.getName().substring(R_TAGS.length()),
                            depth, or.abbreviate(tip).name());
                }
            }
            List<Candidate> candidates = new ArrayList<Candidate>();    // all the candidates we find

            ObjectId tipId = repo.resolve(tip);

            Ref lucky = tags.get(tipId);
            if (lucky!=null)
                return lucky.getName().substring(R_TAGS.length());

            w.markStart(w.parseCommit(tipId));

            int maxCandidates = 10;

            int seen = 0;   // commit seen thus far
            RevCommit c;
            while ((c=w.next())!=null) {
                if (!c.hasAny(allFlags)) {
                    // if a tag already dominates this commit,
                    // then there's no point in picking a tag on this commit
                    // since the one that dominates it is always more preferable
                    Ref t = tags.get(c);
                    if (t!=null) {
                        Candidate cd = new Candidate(c, t);
                        candidates.add(cd);
                        cd.depth = seen;
                    }
                }

                // if the newly discovered commit isn't reachable from a tag that we've seen
                // it counts toward the total depth.
                for (Candidate cd : candidates) {
                    if (!cd.reaches(c)) {
                        cd.depth++;
                    }
                }

                // if we have search going for enough tags, we wil start closing down.
                // JGit can only give us a finite number of bits, so we can't track
                // all tags even if we wanted to.
                if (candidates.size()>=maxCandidates)
                    break;

                // TODO: if all the commits in the queue of RevWalk has allFlags
                // there's no point in continuing search as we'll not discover any more
                // tags. But RevWalk doesn't expose this.

                seen++;
            }

            // at this point we aren't adding any more tags to our search,
            // but we still need to count all the depths correctly.
            while ((c=w.next())!=null) {
                if (c.hasAll(allFlags)) {
                    // no point in visiting further from here, so cut the search here
                    for (RevCommit p : c.getParents())
                        p.add(RevFlag.SEEN);
                } else {
                    for (Candidate cd : candidates) {
                        if (!cd.reaches(c)) {
                            cd.depth++;
                        }
                    }
                }
            }

            if (candidates.isEmpty())
                throw new GitException("No tags can describe "+tip);

            // if all the nodes are dominated by all the tags, the walk stops
            Collections.sort(candidates,new Comparator<Candidate>() {
                public int compare(Candidate o1, Candidate o2) {
                    return o1.depth-o2.depth;
                }
            });

            return candidates.get(0).describe(tipId);
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    public List<IndexEntry> lsTree(String treeIsh, boolean recursive) throws GitException, InterruptedException {
        Repository repo = null;
        ObjectReader or = null;
        RevWalk w = null;
        try {
            repo = getRepository();
            or = repo.newObjectReader();
            w = new RevWalk(or);

            TreeWalk tree = new TreeWalk(or);
            tree.addTree(w.parseTree(repo.resolve(treeIsh)));
            tree.setRecursive(recursive);

            List<IndexEntry> r = new ArrayList<IndexEntry>();
            while (tree.next()) {
                RevObject rev = w.parseAny(tree.getObjectId(0));
                r.add(new IndexEntry(
                        String.format("%06o", tree.getRawMode(0)),
                        typeString(rev.getType()),
                        tree.getObjectId(0).name(),
                        tree.getNameString()));
            }
            return r;
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            if (w != null) w.dispose();
            if (or != null) or.release();
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    public void reset(boolean hard) throws GitException, InterruptedException {
        Repository repo = null;
        try {
            repo = getRepository();
            ResetCommand reset = new ResetCommand(repo);
            reset.setMode(hard?HARD:MIXED);
            reset.call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    public boolean isBareRepository(String GIT_DIR) throws GitException, InterruptedException {
        Repository repo = null;
        boolean isBare = false;
        if (GIT_DIR == null) {
            throw new GitException("Not a git repository"); // Compatible with CliGitAPIImpl
        }
        try {
            if (isBlank(GIT_DIR) || !(new File(GIT_DIR)).isAbsolute()) {
                if ((new File(workspace, ".git")).exists()) {
                    repo = getRepository();
                } else {
                    repo = new RepositoryBuilder().setGitDir(workspace).build();
                }
            } else {
                repo = new RepositoryBuilder().setGitDir(new File(GIT_DIR)).build();
            }
            isBare = repo.isBare();
        } catch (IOException ioe) {
            throw new GitException(ioe);
        } finally {
            if (repo != null) repo.close();
        }
        return isBare;
    }

    /** {@inheritDoc} */
    @Deprecated
    public String getDefaultRemote(String _default_) throws GitException, InterruptedException {
        Set<String> remotes = getConfig(null).getSubsections("remote");
        if (remotes.contains(_default_))    return _default_;
        else    return com.google.common.collect.Iterables.getFirst(remotes, null);
    }

    /** {@inheritDoc} */
    @Deprecated
    public void setRemoteUrl(String name, String url, String GIT_DIR) throws GitException, InterruptedException {
        Repository repo = null;
        try {
            repo = new RepositoryBuilder().setGitDir(new File(GIT_DIR)).build();
            StoredConfig config = repo.getConfig();
            config.setString("remote", name, "url", url);
            config.save();
        } catch (IOException ioe) {
            throw new GitException(ioe);
        } finally {
            if (repo != null) repo.close();
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    public String getRemoteUrl(String name, String GIT_DIR) throws GitException, InterruptedException {
        return getConfig(GIT_DIR).getString("remote", name, "url");
    }

    private StoredConfig getConfig(String GIT_DIR) throws GitException {
        StoredConfig config;
        Repository repo = null;
        if (isBlank(GIT_DIR)) {
            repo = getRepository();
        } else {
            try {
                /* Construct a Repository using GIT_DIR as its working tree */
                repo = new RepositoryBuilder().setWorkTree(new File(GIT_DIR)).build();
            } catch (IOException ioe) {
                throw new GitException(ioe);
            }
        }
        config = repo.getConfig();
        repo.close();
        return config;
    }
}
