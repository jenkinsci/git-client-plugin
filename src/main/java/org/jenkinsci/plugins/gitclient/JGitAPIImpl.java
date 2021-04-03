package org.jenkinsci.plugins.gitclient;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.removeStart;
import static org.eclipse.jgit.api.ResetCommand.ResetType.HARD;
import static org.eclipse.jgit.api.ResetCommand.ResetType.MIXED;
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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.time.FastDateFormat;
import org.eclipse.jgit.api.AddNoteCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.SubmoduleUpdateCommand;
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
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.jenkinsci.plugins.gitclient.jgit.PreemptiveAuthHttpClientConnectionFactory;
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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.plugins.git.GitObject;
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
        this(workspace, listener, null);
    }

    JGitAPIImpl(File workspace, TaskListener listener, final PreemptiveAuthHttpClientConnectionFactory httpConnectionFactory) {
        /* If workspace is null, then default to current directory to match 
         * CliGitAPIImpl behavior */
        super(workspace == null ? new File(".") : workspace);
        this.listener = listener;

        // to avoid rogue plugins from clobbering what we use, always
        // make a point of overwriting it with ours.
        SshSessionFactory.setInstance(new TrileadSessionFactory());

        if (httpConnectionFactory != null) {
            httpConnectionFactory.setCredentialsProvider(asSmartCredentialsProvider());
            // allow override of HttpConnectionFactory to avoid JENKINS-37934
            HttpTransport.setConnectionFactory(httpConnectionFactory);
        }
    }

    /**
     * clearCredentials.
     */
    @Override
    public void clearCredentials() {
        asSmartCredentialsProvider().clearCredentials();
    }

    /** {@inheritDoc} */
    @Override
    public void addCredentials(String url, StandardCredentials credentials) {
        asSmartCredentialsProvider().addCredentials(url, credentials);
    }

    /** {@inheritDoc} */
    @Override
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
    @Override
    public GitClient subGit(String subdir) {
        return new JGitAPIImpl(new File(workspace, subdir), listener);
    }

    /** {@inheritDoc} */
    @Override
    public void setAuthor(String name, String email) throws GitException {
        author = new PersonIdent(name,email);
    }

    /** {@inheritDoc} */
    @Override
    public void setCommitter(String name, String email) throws GitException {
        committer = new PersonIdent(name,email);
    }

    /**
     * init.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
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
    @Override
    public CheckoutCommand checkout() {
        return new CheckoutCommand() {
            private String ref;
            private String branch;
            private boolean deleteBranch;
            private List<String> sparseCheckoutPaths = Collections.emptyList();

            @Override
            public CheckoutCommand ref(String ref) {
                this.ref = ref;
                return this;
            }

            @Override
            public CheckoutCommand branch(String branch) {
                this.branch = branch;
                return this;
            }

            @Override
            public CheckoutCommand deleteBranchIfExist(boolean deleteBranch) {
                this.deleteBranch = deleteBranch;
                return this;
            }

            @Override
            public CheckoutCommand sparseCheckoutPaths(List<String> sparseCheckoutPaths) {
                this.sparseCheckoutPaths = sparseCheckoutPaths == null ? Collections.emptyList() : sparseCheckoutPaths;
                return this;
            }

            @Override
            public CheckoutCommand timeout(Integer timeout) {
                // noop in jgit
                return this;
            }

            private CheckoutCommand lfsCheckoutIsNotSupported() {
                listener.getLogger().println("[WARNING] JGit doesn't support LFS checkout. This flag is ignored.");
                return this;
            }

            @Override
            public CheckoutCommand lfsRemote(String lfsRemote) {
                return lfsCheckoutIsNotSupported();
            }

            @Override
            public CheckoutCommand lfsCredentials(StandardCredentials lfsCredentials) {
                return lfsCheckoutIsNotSupported();
            }

            @Override
            public void execute() throws GitException, InterruptedException {

                if(! sparseCheckoutPaths.isEmpty()) {
                    listener.getLogger().println("[ERROR] JGit doesn't support sparse checkout.");
                    throw new UnsupportedOperationException("not implemented yet");
                }

                if (branch == null)
                    doCheckoutWithResetAndRetry(ref);
                else if (deleteBranch)
                    doCheckoutWithResetAndRetryAndCleanBranch(branch, ref);
                else
                    doCheckout(ref, branch);
            }
        };
    }

    /* Separate method call for benefit of spotbugs */
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification = "Spotbugs and Netbeans disagree on potential for null")
    private void closeRepo(Repository repo) {
        if (repo != null) {
            repo.close();
        }
    }

    private void doCheckoutWithResetAndRetry(String ref) throws GitException {
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

                List<String> remoteTrackingBranches = new ArrayList<>();
                for (String remote : repo.getRemoteNames()) {
                    // look for exactly ONE remote tracking branch
                    String matchingRemoteBranch = Constants.R_REMOTES + remote + "/" + ref;
                    if (repo.exactRef(matchingRemoteBranch) != null) {
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
                closeRepo(repo); /* Ready to reuse repo */
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
            } catch (IOException | GitAPIException e) {
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
        try (Repository repo = getRepository()) {
            git(repo).checkout().setName(branch).setCreateBranch(true).setForce(true).setStartPoint(ref).call();
        } catch (GitAPIException e) {
            throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
        }
    }

    private void doCheckoutWithResetAndRetryAndCleanBranch(String branch, String ref) throws GitException {
        try (Repository repo = getRepository()) {
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

            doCheckoutWithResetAndRetry(branch);
        } catch (IOException e) {
            throw new GitException("Could not checkout " + branch +  " with start point " + ref, e);
        }
    }


    /** {@inheritDoc} */
    @Override
    public void add(String filePattern) throws GitException {
        try (Repository repo = getRepository()) {
            git(repo).add().addFilepattern(filePattern).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    private Git git(Repository repo) {
        return Git.wrap(repo);
    }

    /** {@inheritDoc} */
    @Override
    public void commit(String message) throws GitException {
        try (Repository repo = getRepository()) {
            CommitCommand cmd = git(repo).commit().setMessage(message).setAuthor(author);
            if (committer!=null)
                cmd.setCommitter(new PersonIdent(committer,new Date()));
            cmd.call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void branch(String name) throws GitException {
        try (Repository repo = getRepository()) {
            git(repo).branchCreate().setName(name).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deleteBranch(String name) throws GitException {
        try (Repository repo = getRepository()) {
            git(repo).branchDelete().setForce(true).setBranchNames(name).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /**
     * getBranches.
     *
     * @return a {@link java.util.Set} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    @Override
    public Set<Branch> getBranches() throws GitException {
        return getBranchesInternal(ListBranchCommand.ListMode.ALL);
    }

    /**
     * getRemoteBranches.
     *
     * @return a {@link java.util.Set} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    @Override
    public Set<Branch> getRemoteBranches() throws GitException {
        return getBranchesInternal(ListBranchCommand.ListMode.REMOTE);
    }

    public Set<Branch> getBranchesInternal(ListBranchCommand.ListMode mode) throws GitException {
        try (Repository repo = getRepository()) {
            List<Ref> refs = git(repo).branchList().setListMode(mode).call();
            Set<Branch> branches = new HashSet<>(refs.size());
            for (Ref ref : refs) {
                branches.add(new Branch(ref));
            }
            return branches;
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void tag(String name, String message) throws GitException {
        try (Repository repo = getRepository()) {
            git(repo).tag().setName(name).setMessage(message).setForceUpdate(true).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean tagExists(String tagName) throws GitException {
        try (Repository repo = getRepository()) {
            Ref tag =  repo.exactRef(R_TAGS + tagName);
            return tag != null;
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /**
     * fetch_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.FetchCommand} object.
     */
    @Override
    public org.jenkinsci.plugins.gitclient.FetchCommand fetch_() {
        return new org.jenkinsci.plugins.gitclient.FetchCommand() {
            private URIish url;
            private List<RefSpec> refspecs;
            private boolean shouldPrune = false;
            private boolean tags = true;

            @Override
            public org.jenkinsci.plugins.gitclient.FetchCommand from(URIish remote, List<RefSpec> refspecs) {
                this.url = remote;
                this.refspecs = refspecs;
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.FetchCommand prune() {
                return prune(true);
            }

            @Override
            public org.jenkinsci.plugins.gitclient.FetchCommand prune(boolean prune) {
                shouldPrune = prune;
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.FetchCommand shallow(boolean shallow) {
                if (shallow) {
                    listener.getLogger().println("[WARNING] JGit doesn't support shallow clone. This flag is ignored");
                }
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.FetchCommand timeout(Integer timeout) {
                // noop in jgit
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.FetchCommand tags(boolean tags) {
                this.tags = tags;
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.FetchCommand depth(Integer depth) {
                listener.getLogger().println("[WARNING] JGit doesn't support shallow clone and therefore depth is meaningless. This flag is ignored");
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                try (Repository repo = getRepository()) {
                    Git git = git(repo);

                    List<RefSpec> allRefSpecs = new ArrayList<>();
                    if (refspecs != null)
                        for (RefSpec rs: refspecs)
                            if (rs != null)
                                allRefSpecs.add(rs);

                    FetchCommand fetch = git.fetch();
                    fetch.setTagOpt(tags ? TagOpt.FETCH_TAGS : TagOpt.NO_TAGS);
                    /* JGit 4.5 required a work around that the tags refspec had to be passed in addition to setting
                     * the FETCH_TAGS tagOpt.  JGit 4.9.0 fixed that bug.
                     * However, JGit 4.9 and later will not accept an empty refspec.
                     * If the refspec is empty and tag fetch is requested, must add the tags refspec to fetch.
                     */
                    if (allRefSpecs.isEmpty() && tags) {
                        allRefSpecs.add(new RefSpec("+refs/tags/*:refs/tags/*"));
                    }
                    if (url == null) {
                        throw new GitException("FetchCommand requires a valid repository url in remote config");
                    }
                    fetch.setRemote(url.toString());
                    fetch.setCredentialsProvider(getProvider());

                    fetch.setRefSpecs(allRefSpecs);
                    fetch.setRemoveDeletedRefs(shouldPrune);

                    fetch.call();
                } catch (GitAPIException e) {
                    throw new GitException(e);
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
    @Override
    public void fetch(URIish url, List<RefSpec> refspecs) throws GitException, InterruptedException {
        fetch_().from(url, refspecs).execute();
    }

    /** {@inheritDoc} */
    @Override
    public void fetch(String remoteName, RefSpec... refspec) throws GitException {
        try (Repository repo = getRepository()) {
            FetchCommand fetch = git(repo).fetch().setTagOpt(TagOpt.FETCH_TAGS);
            if (remoteName != null) fetch.setRemote(remoteName);
            fetch.setCredentialsProvider(getProvider());

            List<RefSpec> refSpecs = new ArrayList<>();
            if (refspec != null && refspec.length > 0)
                for (RefSpec rs: refspec)
                    if (rs != null)
                        refSpecs.add(rs);
            fetch.setRefSpecs(refSpecs);

            fetch.call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void fetch(String remoteName, RefSpec refspec) throws GitException {
        fetch(remoteName, new RefSpec[] {refspec});
    }

    /** {@inheritDoc} */
    @Override
    public void ref(String refName) throws GitException, InterruptedException {
	refName = refName.replace(' ', '_');
	try (Repository repo = getRepository()) {
	    RefUpdate refUpdate = repo.updateRef(refName);
	    refUpdate.setNewObjectId(repo.exactRef(Constants.HEAD).getObjectId());
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
	}
    }

    /** {@inheritDoc} */
    @Override
    public boolean refExists(String refName) throws GitException, InterruptedException {
	refName = refName.replace(' ', '_');
	try (Repository repo = getRepository()) {
	    Ref ref = repo.findRef(refName);
	    return ref != null;
	} catch (IOException e) {
	    throw new GitException("Error checking ref " + refName, e);
	}
    }

    /** {@inheritDoc} */
    @Override
    public void deleteRef(String refName) throws GitException, InterruptedException {
	refName = refName.replace(' ', '_');
	try (Repository repo = getRepository()) {
	    RefUpdate refUpdate = repo.updateRef(refName);
	    // Required, even though this is a forced delete.
	    refUpdate.setNewObjectId(repo.exactRef(Constants.HEAD).getObjectId());
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
	}
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getRefNames(String refPrefix) throws GitException, InterruptedException {
	if (refPrefix.isEmpty()) {
	    refPrefix = RefDatabase.ALL;
	} else {
	    refPrefix = refPrefix.replace(' ', '_');
	}
	try (Repository repo = getRepository()) {
	    List<Ref> refList = repo.getRefDatabase().getRefsByPrefix(refPrefix);
	    Set<String> refs = new HashSet<>(refList.size());
	    for (Ref ref : refList) {
		refs.add(ref.getName());
	    }
	    return refs;
	} catch (IOException e) {
	    throw new GitException("Error retrieving refs with prefix " + refPrefix, e);
	}
    }

    /** {@inheritDoc} */
    @Override
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification = "Java 11 spotbugs error")
    public Map<String, ObjectId> getHeadRev(String url) throws GitException, InterruptedException {
        return getRemoteReferences(url, null, true, false);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, ObjectId> getRemoteReferences(String url, String pattern, boolean headsOnly, boolean tagsOnly)
            throws GitException, InterruptedException {
        Map<String, ObjectId> references = new HashMap<>();
        String regexPattern = null;
        if (pattern != null) {
            regexPattern = createRefRegexFromGlob(pattern);
        }
        try (Repository repo = openDummyRepository()) {
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
        } catch (JGitInternalException | GitAPIException | IOException e) {
            throw new GitException(e);
        }
        return references;
    }

    @Override
    public Map<String, String> getRemoteSymbolicReferences(String url, String pattern)
            throws GitException, InterruptedException {
        Map<String, String> references = new HashMap<>();
        String regexPattern = null;
        if (pattern != null) {
            regexPattern = replaceGlobCharsWithRegExChars(pattern);
        }
        if (regexPattern != null && !Constants.HEAD.matches(regexPattern)) {
            return references;
        }
        try (Repository repo = openDummyRepository()) {
            LsRemoteCommand lsRemote = new LsRemoteCommand(repo);
            lsRemote.setRemote(url);
            lsRemote.setCredentialsProvider(getProvider());
            Collection<Ref> refs = lsRemote.call();
            for (final Ref r : refs) {
                if (!r.isSymbolic()) { // Skip reference if it is not symbolic
                    continue;
                }
                if (regexPattern == null) {
                    references.put(r.getName(), r.getTarget().getName());
                } else if (r.getName().matches(regexPattern)) {
                    references.put(r.getName(), r.getTarget().getName());
                } // else do not return symbolic references with names that do not match pattern
            }
        } catch (GitAPIException | IOException e) {
            throw new GitException(e);
        }
        return references;
    }

    /* Adapted from http://stackoverflow.com/questions/1247772/is-there-an-equivalent-of-java-util-regex-for-glob-type-patterns */
    private String createRefRegexFromGlob(String glob)
    {
        StringBuilder out = new StringBuilder();
        out.append('^');
        if(!glob.startsWith("refs/")) {
            out.append(".*/");
        }
        out.append(replaceGlobCharsWithRegExChars(glob));
        out.append('$');
        return out.toString();
    }

    private String replaceGlobCharsWithRegExChars(String glob)
    {
        StringBuilder out = new StringBuilder();
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
        return out.toString();
    }

    /** {@inheritDoc} */
    @Override
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification = "Java 11 spotbugs error")
    public ObjectId getHeadRev(String remoteRepoUrl, String branchSpec) throws GitException {
        try (Repository repo = openDummyRepository();
             final Transport tn = Transport.open(repo, new URIish(remoteRepoUrl))) {
            final String branchName = extractBranchNameFromBranchSpec(branchSpec);
            String regexBranch = createRefRegexFromGlob(branchName);

            tn.setCredentialsProvider(getProvider());
            try (FetchConnection c = tn.openFetch()) {
                for (final Ref r : c.getRefs()) {
                    if (r.getName().matches(regexBranch)) {
                        return r.getPeeledObjectId() != null ? r.getPeeledObjectId() : r.getObjectId();
                    }
                }
            }
        } catch (IOException | URISyntaxException | IllegalStateException e) {
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
        return new FileRepositoryImpl(tempDir, tempDir);
    }

    /** {@inheritDoc} */
    @Override
    public String getRemoteUrl(String name) throws GitException {
        try (Repository repo = getRepository()) {
            return repo.getConfig().getString("remote",name,"url");
        }
    }

    /**
     * getRepository.
     *
     * @return a {@link org.eclipse.jgit.lib.Repository} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    @SuppressFBWarnings(value = "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE",
                        justification = "JGit interaction with spotbugs")
    @NonNull
    @Override
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
    @Override
    public FilePath getWorkTree() {
        return new FilePath(workspace);
    }

    /** {@inheritDoc} */
    @Override
    public void setRemoteUrl(String name, String url) throws GitException {
        try (Repository repo = getRepository()) {
            StoredConfig config = repo.getConfig();
            config.setString("remote", name, "url", url);
            config.save();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void addRemoteUrl(String name, String url) throws GitException, InterruptedException {
        try (Repository repo = getRepository()) {
            StoredConfig config = repo.getConfig();

            List<String> urls = new ArrayList<>();
            urls.addAll(Arrays.asList(config.getStringList("remote", name, "url")));
            urls.add(url);

            config.setStringList("remote", name, "url", urls);
            config.save();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void addNote(String note, String namespace) throws GitException {
        try (Repository repo = getRepository()) {
            ObjectId head = repo.resolve(HEAD); // commit to put a note on

            AddNoteCommand cmd = git(repo).notesAdd();
            cmd.setMessage(normalizeNote(note));
            cmd.setNotesRef(qualifyNotesNamespace(namespace));
            try (ObjectReader or = repo.newObjectReader();
                 RevWalk walk = new RevWalk(or)) {
                cmd.setObjectId(walk.parseAny(head));
                cmd.call();
            }
        } catch (GitAPIException | IOException e) {
            throw new GitException(e);
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
    @Override
    public void appendNote(String note, String namespace) throws GitException {
        try (Repository repo = getRepository()) {
            ObjectId head = repo.resolve(HEAD); // commit to put a note on

            ShowNoteCommand cmd = git(repo).notesShow();
            cmd.setNotesRef(qualifyNotesNamespace(namespace));
            try (ObjectReader or = repo.newObjectReader();
                 RevWalk walk = new RevWalk(or)) {
                cmd.setObjectId(walk.parseAny(head));
                Note n = cmd.call();

                if (n==null) {
                    addNote(note,namespace);
                } else {
                    ObjectLoader ol = or.open(n.getData());
                    StringWriter sw = new StringWriter();
                    IOUtils.copy(new InputStreamReader(ol.openStream(), UTF_8),sw);
                    sw.write("\n");
                    addNote(sw.toString() + normalizeNote(note), namespace);
                }
            }
        } catch (GitAPIException | IOException e) {
            throw new GitException(e);
        }
    }

    /**
     * changelog.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    @Override
    public ChangelogCommand changelog() {
        return new ChangelogCommand() {
            private Repository repo = getRepository();
            private ObjectReader or = repo.newObjectReader();
            private RevWalk walk = new RevWalk(or);
            private Writer out;
            private boolean hasIncludedRev = false;

            @Override
            public ChangelogCommand excludes(String rev) {
                try {
                    return excludes(repo.resolve(rev));
                } catch (IOException e) {
                    throw new GitException(e);
                }
            }

            @Override
            public ChangelogCommand excludes(ObjectId rev) {
                try {
                    walk.markUninteresting(walk.lookupCommit(rev));
                    return this;
                } catch (IOException e) {
                    throw new GitException("Error: jgit excludes() in " + workspace + " " + e.getMessage(), e);
                }
            }

            @Override
            public ChangelogCommand includes(String rev) {
                try {
                    includes(repo.resolve(rev));
                    hasIncludedRev = true;
                    return this;
                } catch (IOException e) {
                    throw new GitException(e);
                }
            }

            @Override
            public ChangelogCommand includes(ObjectId rev) {
                try {
                    walk.markStart(walk.lookupCommit(rev));
                    hasIncludedRev = true;
                    return this;
                } catch (IOException e) {
                    throw new GitException("Error: jgit includes() in " + workspace + " " + e.getMessage(), e);
                }
            }

            @Override
            public ChangelogCommand to(Writer w) {
                this.out = w;
                return this;
            }

            @Override
            public ChangelogCommand max(int n) {
                walk.setRevFilter(MaxCountRevFilter.create(n));
                return this;
            }

            private void closeResources() {
                walk.close();
                or.close();
                repo.close();
            }

            @Override
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
            @Override
            public void execute() throws GitException, InterruptedException {
                if (out == null) {
                    throw new IllegalStateException(); // Match CliGitAPIImpl
                }
                try (PrintWriter pw = new PrintWriter(out,false)) {
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
                    throw new GitException("Error: jgit whatchanged in " + workspace + " " + e.getMessage(), e);
                } finally {
                    closeResources();
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
        @SuppressFBWarnings(value = "VA_FORMAT_STRING_USES_NEWLINE",
                justification = "Windows git implementation requires specific line termination")
        void format(RevCommit commit, RevCommit parent, PrintWriter pw, Boolean useRawOutput) throws IOException {
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
            try (Repository repo = getRepository();
                 ObjectReader or = repo.newObjectReader();
                 TreeWalk tw = new TreeWalk(or)) {
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
    }

    /**
     * clean.
     *
     * @param cleanSubmodule flag to add extra -f
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    @Override
    public void clean(boolean cleanSubmodule) throws GitException {
        try (Repository repo = getRepository()) {
            Git git = git(repo);
            git.reset().setMode(HARD).call();
            git.clean().setCleanDirectories(true).setIgnore(false).setForce(cleanSubmodule).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /**
     * clean.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    @Override
    public void clean() throws GitException {
        this.clean(false);
    }

    /**
     * clone_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    @Override
    public CloneCommand clone_() {

        return new CloneCommand() {
            private String url;
            private String remote = Constants.DEFAULT_REMOTE_NAME;
            private String reference;
            private Integer timeout;
            private boolean shared;
            private boolean tags = true;
            private List<RefSpec> refspecs;

            @Override
            public CloneCommand url(String url) {
                this.url = url;
                return this;
            }

            @Override
            public CloneCommand repositoryName(String name) {
                this.remote = name;
                return this;
            }

            @Override
            public CloneCommand shallow() {
                return shallow(true);
            }

            @Override
            public CloneCommand shallow(boolean shallow) {
                if (shallow) {
                    listener.getLogger().println("[WARNING] JGit doesn't support shallow clone. This flag is ignored");
                }
                return this;
            }

            @Override
            public CloneCommand shared() {
                return shared(true);
            }

            @Override
            public CloneCommand shared(boolean shared) {
                this.shared = shared;
                return this;
            }

            @Override
            public CloneCommand reference(String reference) {
                this.reference = reference;
                return this;
            }

            @Override
            public CloneCommand refspecs(List<RefSpec> refspecs) {
                this.refspecs = new ArrayList<>(refspecs);
                return this;
            }

            @Override
            public CloneCommand timeout(Integer timeout) {
            	this.timeout = timeout;
            	return this;
            }

            @Override
            public CloneCommand tags(boolean tags) {
                this.tags = tags;
                return this;
            }

            @Override
            public CloneCommand noCheckout() {
                // this.noCheckout = true; ignored, we never do a checkout
                return this;
            }

            @Override
            public CloneCommand depth(Integer depth) {
                listener.getLogger().println("[WARNING] JGit doesn't support shallow clone and therefore depth is meaningless. This flag is ignored");
                return this;
            }

            @SuppressFBWarnings(value = "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE",
                                justification = "JGit interaction with spotbugs")
            private RepositoryBuilder newRepositoryBuilder() {
                RepositoryBuilder builder = new RepositoryBuilder();
                builder.setGitDir(new File(workspace, Constants.DOT_GIT)).readEnvironment();
                return builder;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                Repository repository = null;

                try {
                    // the directory needs to be clean or else JGit complains
                    if (workspace.exists())
                        Util.deleteContentsRecursive(workspace);

                    // since jgit clone/init commands do not support object references (e.g. alternates),
                    // we build the repository directly using the RepositoryBuilder

                    RepositoryBuilder builder = newRepositoryBuilder();

                    if (shared) {
                        if (reference == null || reference.isEmpty()) {
                            // we use origin as reference
                            reference = url;
                        } else {
                            listener.getLogger().println("[WARNING] Both 'shared' and 'reference' are used, shared is ignored.");
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
                            listener.getLogger().println("[WARNING] Reference path does not exist: " + reference);
                        else if (!referencePath.isDirectory())
                            listener.getLogger().println("[WARNING] Reference path is not a directory: " + reference);
                        else {
                            // reference path can either be a normal or a base repository
                            File objectsPath = new File(referencePath, ".git/objects");
                            if (!objectsPath.isDirectory()) {
                                // reference path is bare repo
                                objectsPath = new File(referencePath, "objects");
                            }
                            if (!objectsPath.isDirectory())
                                listener.getLogger().println("[WARNING] Reference path does not contain an objects directory (no git repo?): " + objectsPath);
                            else {
                                try {
                                    File alternates = new File(workspace, ".git/objects/info/alternates");
                                    String absoluteReference = objectsPath.getAbsolutePath().replace('\\', '/');
                                    listener.getLogger().println("Using reference repository: " + reference);
                                    // git implementations on windows also use
                                    try (PrintWriter w = new PrintWriter(alternates, "UTF-8")) {
                                        // git implementations on windows also use
                                        w.print(absoluteReference);
                                    }
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

                } catch (GitAPIException | IOException e) {
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
    @Override
    public MergeCommand merge() {
        return new MergeCommand() {
            private ObjectId rev;
            private MergeStrategy strategy;
            private FastForwardMode fastForwardMode;
            private boolean squash;
            private boolean commit = true;
            private String comment;

            @Override
            public MergeCommand setRevisionToMerge(ObjectId rev) {
                this.rev = rev;
                return this;
            }

            @Override
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
                    if (strategy == MergeCommand.Strategy.RECURSIVE_THEIRS) {
                        this.strategy = MergeStrategy.THEIRS;
                        return this;
                    }

                    listener.getLogger().println("[WARNING] JGit doesn't fully support merge strategies. This flag is ignored");
                }
                return this;
            }

            @Override
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

            @Override
            public MergeCommand setSquash(boolean squash){
                this.squash = squash;
                return this;
            }

            @Override
            public MergeCommand setMessage(String comment) {
                this.comment = comment;
                return this;
            }

            @Override
            public MergeCommand setCommit(boolean commit) {
                this.commit = commit;
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                try (Repository repo = getRepository()) {
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
                }
            }
        };
    }

    /**
     * init_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.InitCommand} object.
     */
    @Override
    public InitCommand init_() {
        return new InitCommand() {
            private String workspace;
            private boolean bare;

            @Override
            public InitCommand workspace(String workspace) {
                this.workspace = workspace;
                return this;
            }

            @Override
            public InitCommand bare(boolean bare) {
                this.bare = bare;
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                doInit(workspace, bare);
            }
        };
    }

    @Override
    public RebaseCommand rebase() {
        return new RebaseCommand() {
            private String upstream;

            @Override
            public RebaseCommand setUpstream(String upstream) {
                this.upstream = upstream;
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                try (Repository repo = getRepository()) {
                    Git git = git(repo);
                    RebaseResult rebaseResult = git.rebase().setUpstream(upstream).call();
                    if (!rebaseResult.getStatus().isSuccessful()) {
                        git.rebase().setOperation(Operation.ABORT).call();
                        throw new GitException("Failed to rebase " + upstream);
                    }
                } catch (GitAPIException e) {
                    throw new GitException("Failed to rebase " + upstream, e);
                }
            }
        };
    }

    /** {@inheritDoc} */
    @Override
    public void deleteTag(String tagName) throws GitException {
        try (Repository repo = getRepository()) {
            git(repo).tagDelete().setTags(tagName).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String getTagMessage(String tagName) throws GitException {
        try (Repository repo = getRepository();
            ObjectReader or = repo.newObjectReader();
            RevWalk walk = new RevWalk(or)) {
            return walk.parseTag(repo.resolve(tagName)).getFullMessage().trim();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<IndexEntry> getSubmodules(String treeIsh) throws GitException {
        try (Repository repo = getRepository();
             ObjectReader or = repo.newObjectReader();
             RevWalk w = new RevWalk(or)) {
            List<IndexEntry> r = new ArrayList<>();

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
        }
    }

    /** {@inheritDoc} */
    @Override
    public void addSubmodule(String remoteURL, String subdir) throws GitException {
        try (Repository repo = getRepository()) {
            git(repo).submoduleAdd().setPath(subdir).setURI(remoteURL).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getTagNames(String tagPattern) throws GitException {
        if (tagPattern == null) tagPattern = "*";

        Set<String> tags = new HashSet<>();
        try (Repository repo = getRepository()) {
            FileNameMatcher matcher = new FileNameMatcher(tagPattern, null);
            Map<String, Ref> tagList = repo.getTags();
            for (String name : tagList.keySet()) {
                matcher.reset();
                matcher.append(name);
                if (matcher.isMatch()) tags.add(name);
            }
        } catch (InvalidPatternException e) {
            throw new GitException(e);
        }
        return tags;
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getRemoteTagNames(String tagPattern) throws GitException {
        /* BUG: Lists local tag names, not remote tag names */
        if (tagPattern == null) tagPattern = "*";

        try (Repository repo = getRepository()) {
            Set<String> tags = new HashSet<>();
            FileNameMatcher matcher = new FileNameMatcher(tagPattern, '/');
            List<Ref> refList = repo.getRefDatabase().getRefsByPrefix(R_TAGS);
            for (Ref ref : refList) {
                String name = ref.getName().substring(R_TAGS.length());
                matcher.reset();
                matcher.append(name);
                if (matcher.isMatch()) tags.add(name);
            }
            return tags;
        } catch (IOException | InvalidPatternException e) {
            throw new GitException(e);
        }
    }

    /**
     * Returns true if the current workspace has a git repository.
     * Does not search parent directories for a repository.
     *
     * @return true if this workspace has a git repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    @Override
    public boolean hasGitRepo() throws GitException {
        try (Repository repo = getRepository()) {
            return repo.getObjectDatabase().exists();
        } catch (GitException e) {
            return false;
        }
    }

    /**
     * Returns true if the current workspace has a git repository.
     * If checkParentDirectories is true, searches parent directories.
     * If checkParentDirectories is false, checks workspace directory only.
     *
     * @param checkParentDirectories if true, search upwards for a git repository
     * @return true if this workspace has a git repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    @Override
    public boolean hasGitRepo(boolean checkParentDirectories) throws GitException {
        if (!checkParentDirectories) { // JGit hasGitRepo() does not check parent directories
            return hasGitRepo();
        }
        if (hasGitRepo(".git")) {
            try (Repository repo = getRepository()) {
                if (repo.getObjectDatabase().exists()) {
                    return true;
                }
                /* Emulate command line git searching up the file system hierarchy */
                FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
                FileRepositoryBuilder parentRepoBuilder = repositoryBuilder.findGitDir(workspace);
                return parentRepoBuilder.getGitDir() != null;
            } catch (GitException e) {
                return false;
            }
        }
        return false;
    }

    private boolean hasGitRepo(String gitDirectory) throws GitException {
        try {
            File dotGit = new File(workspace, gitDirectory);
            return dotGit.exists();
        } catch (SecurityException ex) {
            throw new GitException("Security error when trying to check for .git. Are you sure you have correct permissions?",
                                   ex);
        } catch (Exception e) {
            throw new GitException("Couldn't check for .git", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isCommitInRepo(ObjectId commit) throws GitException {
        if (commit == null) {
            return false;
        }
        final boolean found;
        try (Repository repo = getRepository()) {
            found = repo.hasObject(commit);
        }
        return found;
    }

    /** {@inheritDoc} */
    @Override
    public void prune(RemoteConfig repository) throws GitException {
        try (Repository gitRepo = getRepository()) {
            String remote = repository.getName();
            String prefix = "refs/remotes/" + remote + "/";

            Set<String> branches = listRemoteBranches(remote);

            for (Ref r : new ArrayList<>(gitRepo.getAllRefs().values())) {
                if (r.getName().startsWith(prefix) && !branches.contains(r.getName())) {
                    // delete this ref
                    RefUpdate update = gitRepo.updateRef(r.getName());
                    update.setRefLogMessage("remote branch pruned", false);
                    update.setForceUpdate(true);
                    update.delete();
                }
            }
        } catch (URISyntaxException | IOException e) {
            throw new GitException(e);
        }
    }

    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification = "Java 11 spotbugs error")
    private Set<String> listRemoteBranches(String remote) throws NotSupportedException, TransportException, URISyntaxException {
        Set<String> branches = new HashSet<>();
        try (final Repository repo = getRepository()) {
            StoredConfig config = repo.getConfig();
            try (final Transport tn = Transport.open(repo, new URIish(config.getString("remote",remote,"url")))) {
                tn.setCredentialsProvider(getProvider());
                try (final FetchConnection c = tn.openFetch()) {
                    for (final Ref r : c.getRefs()) {
                        if (r.getName().startsWith(R_HEADS))
                            branches.add("refs/remotes/"+remote+"/"+r.getName().substring(R_HEADS.length()));
                    }
                }
            }
        }
        return branches;
    }

    /**
     * push.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     */
    @Override
    public PushCommand push() {
        return new PushCommand() {
            private URIish remote;
            private String refspec;
            private boolean force;
            private boolean tags;

            @Override
            public PushCommand to(URIish remote) {
                this.remote = remote;
                return this;
            }

            @Override
            public PushCommand ref(String refspec) {
                this.refspec = refspec;
                return this;
            }

            @Override
            public PushCommand force() {
                return force(true);
            }

            @Override
            public PushCommand force(boolean force) {
                this.force = force;
                return this;
            }

            @Override
            public PushCommand tags(boolean tags) {
                this.tags = tags;
                return this;
            }

            @Override
            public PushCommand timeout(Integer timeout) {
            	// noop in jgit
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                try (Repository repo = getRepository()) {
                    RefSpec ref = (refspec != null) ? new RefSpec(fixRefSpec(refspec, repo)) : Transport.REFSPEC_PUSH_ALL;
                    listener.getLogger().println("RefSpec is \""+ref+"\".");
                    Git g = git(repo);
                    Config config = g.getRepository().getConfig();
                    if (remote == null) {
                        throw new GitException("PushCommand requires a remote repository URL");
                    }
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
                } catch (IOException | JGitInternalException | GitAPIException e) {
                    throw new GitException(e);
                }
            }

            /**
             * Currently JGit does not parse refspecs as well as Git CLI.
             * This method attempts to fix the refspec as a workaround until JGit
             * implements parsing arbitrary refspecs (see JENKINS-20393).
             *
             * @return a (hopefully) fixed refspec string.
             */
            private String fixRefSpec(@NonNull String srcRefspec, Repository repository) throws IOException {
                int colon = srcRefspec.indexOf(':');
                String[] specs = new String[]{(colon != -1 ? srcRefspec.substring(0, colon) : srcRefspec).trim(), srcRefspec.substring(colon + 1).trim()};
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
                                Ref ref = repository.findRef(specs[spec]);
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
    @Override
    public RevListCommand revList_()
    {
        return new RevListCommand() {
            private boolean all;
            private boolean nowalk;
            private boolean firstParent;
            private String refspec;
            private List<ObjectId> out;

            @Override
            public RevListCommand all() {
                return all(true);
            }

            @Override
            public RevListCommand all(boolean all) {
                this.all = all;
                return this;
            }

            @Override
            public RevListCommand nowalk(boolean nowalk) {
                this.nowalk = nowalk;
                return this;
            }

            @Override
            public RevListCommand firstParent() {
                return firstParent(true);
            }

            @Override
            public RevListCommand firstParent(boolean firstParent) {
                this.firstParent = firstParent;
                return this;
            }

            @Override
            public RevListCommand to(List<ObjectId> revs){
                this.out = revs;
                return this;
            }

            @Override
            public RevListCommand reference(String reference){
                this.refspec = reference;
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                if (firstParent) {
                  throw new UnsupportedOperationException("not implemented yet");
                }

                try (Repository repo = getRepository();
                     ObjectReader or = repo.newObjectReader();
                     RevWalk walk = new RevWalk(or)) {

                    if (nowalk) {
                        if (out == null) {
                            throw new GitException("RevListCommand requires a 'to' value");
                        }
                        RevCommit c = walk.parseCommit(repo.resolve(refspec));
                        out.add(c.copy());

                        if (all) {
                            for (Ref r : repo.getAllRefs().values()) {
                                c = walk.parseCommit(r.getObjectId());
                                out.add(c.copy());
                            }
                        }
                        return;
                    }

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

                    if (out == null) {
                        throw new GitException("RevListCommand requires a 'to' value");
                    }
                    for (RevCommit c : walk) {
                        out.add(c.copy());
                    }
                } catch (IOException e) {
                    throw new GitException(e);
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
    @Override
    public List<ObjectId> revListAll() throws GitException {
        List<ObjectId> oidList = new ArrayList<>();
        RevListCommand revListCommand = revList_();
        revListCommand.all(true);
        revListCommand.to(oidList);
        try {
            revListCommand.execute();
        } catch (InterruptedException e) {
            throw new GitException(e);
        }
        return oidList;
    }

    /** {@inheritDoc} */
    @Override
    public List<ObjectId> revList(String ref) throws GitException {
        List<ObjectId> oidList = new ArrayList<>();
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
    @Override
    public ObjectId revParse(String revName) throws GitException {
        try (Repository repo = getRepository()) {
            ObjectId id = repo.resolve(revName + "^{commit}");
            if (id == null)
                throw new GitException("Unknown git object "+ revName);
            return id;
        } catch (IOException e) {
            throw new GitException("Failed to resolve git reference "+ revName, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException {
        return showRevision(from, to, true);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> showRevision(ObjectId from, ObjectId to, Boolean useRawOutput) throws GitException {
        try (Repository repo = getRepository();
             ObjectReader or = repo.newObjectReader();
             RevWalk w = new RevWalk(or)) {
            w.markStart(w.parseCommit(to));
            if (from!=null)
                w.markUninteresting(w.parseCommit(from));
            else
                w.setRevFilter(MaxCountRevFilter.create(1));

            List<String> r = new ArrayList<>();
            StringWriter sw = new StringWriter();
            RawFormatter f = new RawFormatter();
            try (PrintWriter pw = new PrintWriter(sw)) {
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

                    r.addAll(Arrays.asList(sw.toString().split("\n")));
                    sw.getBuffer().setLength(0);
                }
            }
            return r;
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    private Iterable<JGitAPIImpl> submodules() throws IOException {
        List<JGitAPIImpl> submodules = new ArrayList<>();
        try (Repository repo = getRepository()) {
            SubmoduleWalk generator = SubmoduleWalk.forIndex(repo);
            while (generator.next()) {
                submodules.add(new JGitAPIImpl(generator.getDirectory(), listener));
            }
        }
        return submodules;
    }

    /** {@inheritDoc} */
    @Override
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
     * Update submodules.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand} object.
     */
    @Override
    public org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand submoduleUpdate() {
        return new org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand() {
            private boolean recursive      = false;
            private boolean remoteTracking = false;
            private String  ref            = null;

            @Override
            public org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand recursive(boolean recursive) {
                this.recursive = recursive;
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand remoteTracking(boolean remoteTracking) {
                this.remoteTracking = remoteTracking;
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand parentCredentials(boolean parentCredentials) {
                // No-op for JGit implementation
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand ref(String ref) {
                this.ref = ref;
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand timeout(Integer timeout) {
            	// noop in jgit
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand shallow(boolean shallow) {
                if (shallow) {
                    listener.getLogger().println("[WARNING] JGit doesn't support shallow clone. This flag is ignored");
                }
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand depth(Integer depth) {
                listener.getLogger().println("[WARNING] JGit doesn't support shallow clone and therefore depth is meaningless. This flag is ignored");
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand threads(int threads) {
                // TODO: I have no idea if JGit can update submodules in parallel
                // It might work, or it might blow up horribly. This probably depends on
                // whether JGit relies on any global/shared state. Since I have no
                // experience with JGit, I'm leaving this unimplemented for the time
                // being. But if some brave soul wants to test this, feel free to provide
                // an implementation similar to the one in the CliGitAPIImpl class using
                // an ExecutorService.
                listener.getLogger().println("[WARNING] JGit doesn't support updating submodules in parallel. This flag is ignored");
                return this;
            }

            @Override
            public org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand useBranch(String submodule, String branchname) {
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                if (remoteTracking) {
                    listener.getLogger().println("[ERROR] JGit doesn't support remoteTracking submodules yet.");
                    throw new UnsupportedOperationException("not implemented yet");
                }
                if ((ref != null) && !ref.isEmpty()) {
                    listener.getLogger().println("[ERROR] JGit doesn't support submodule update --reference yet.");
                    throw new UnsupportedOperationException("not implemented yet");
                }

                try (Repository repo = getRepository()) {
                    SubmoduleUpdateCommand update = git(repo).submoduleUpdate();
                    update.setCredentialsProvider(getProvider());
                    update.call();
                    if (recursive) {
                        for (JGitAPIImpl sub : submodules()) {
                            /* Intentionally using the deprecated method because the replacement method is not serializable. */
                            sub.submoduleUpdate(recursive);
                        }
                    }
                } catch (IOException | GitAPIException e) {
                    throw new GitException(e);
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
    @Override
    public void merge(String refSpec) throws GitException, InterruptedException {
        try (Repository repo = getRepository()) {
            merge(repo.resolve(refSpec));
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public void push(RemoteConfig repository, String refspec) throws GitException, InterruptedException {
        push(repository.getName(),refspec);
    }

    /** {@inheritDoc} */
    @Override
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
    @Override
    public List<Branch> getBranchesContaining(String revspec, boolean allBranches) throws GitException, InterruptedException {
        try (Repository repo = getRepository();
             ObjectReader or = repo.newObjectReader();
             RevWalk walk = new RevWalk(or)) {
            walk.setRetainBody(false);
            walk.sort(RevSort.TOPO);// so that by the time we hit target we have all that we want

            ObjectId id = repo.resolve(revspec);
            if (id==null)   throw new GitException("Invalid commit: "+revspec);
            RevCommit target = walk.parseCommit(id);

            // we can track up to 24 flags at a time in JGit, so that's how many branches we will traverse in every iteration
            List<RevFlag> flags = new ArrayList<>(24);
            for (int i=0; i<24; i++)
                flags.add(walk.newFlag("branch" + i));
            walk.carry(flags);

            List<Branch> result = new ArrayList<>();  // we'll built up the return value in here

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
        }
    }

    private List<Ref> getAllBranchRefs(boolean originBranches) {
        List<Ref> branches = new ArrayList<>();
        try (Repository repo = getRepository()) {
            for (Ref r : repo.getAllRefs().values()) {
                final String branchName = r.getName();
                if (branchName.startsWith(R_HEADS)
                        || (originBranches && branchName.startsWith(R_REMOTES))) {
                    branches.add(r);
                }
            }
        }
        return branches;
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public ObjectId mergeBase(ObjectId id1, ObjectId id2) throws InterruptedException {
        try (Repository repo = getRepository();
             ObjectReader or = repo.newObjectReader();
             RevWalk walk = new RevWalk(or)) {
            walk.setRetainBody(false);  // we don't need the body for this computation
            walk.setRevFilter(RevFilter.MERGE_BASE);

            walk.markStart(walk.parseCommit(id1));
            walk.markStart(walk.parseCommit(id2));

            RevCommit base = walk.next();
            if (base==null)     return null;    // no common base
            return base.getId();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public String getAllLogEntries(String branch) throws InterruptedException {
        try (Repository repo = getRepository();
             ObjectReader or = repo.newObjectReader();
             RevWalk walk = new RevWalk(or)) {
            StringBuilder w = new StringBuilder();
            markAllRefs(walk);
            walk.setRetainBody(false);

            for (RevCommit c : walk) {
                w.append('\'').append(c.name()).append('#').append(c.getCommitTime()).append("'\n");
            }
            return w.toString().trim();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /**
     * Adds all the refs as start commits.
     */
    private void markAllRefs(RevWalk walk) throws IOException {
        markRefs(walk, Predicates.alwaysTrue());
    }

    /**
     * Adds all matching refs as start commits.
     */
    private void markRefs(RevWalk walk, Predicate<Ref> filter) throws IOException {
        try (Repository repo = getRepository()) {
            for (Ref r : repo.getAllRefs().values()) {
                if (filter.apply(r)) {
                    RevCommit c = walk.parseCommit(r.getObjectId());
                    walk.markStart(c);
                }
            }
        }
    }

    /**
     * submoduleInit.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    @Override
    public void submoduleInit() throws GitException, InterruptedException {
        try (Repository repo = getRepository()) {
            git(repo).submoduleInit().call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /**
     * submoduleSync.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    @Override
    public void submoduleSync() throws GitException, InterruptedException {
        try (Repository repo = getRepository()) {
            git(repo).submoduleSync().call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public String getSubmoduleUrl(String name) throws GitException, InterruptedException {
        String v = null;
        try (Repository repo = getRepository()) {
            v = repo.getConfig().getString("submodule", name, "url");
        }
        if (v==null)    throw new GitException("No such submodule: "+name);
        return v.trim();
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public void setSubmoduleUrl(String name, String url) throws GitException, InterruptedException {
        try (Repository repo = getRepository()) {
            StoredConfig config = repo.getConfig();
            config.setString("submodule", name, "url", url);
            config.save();
        } catch (IOException e) {
            throw new GitException(e);
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
    @Override
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
    @Override
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
    @Override
    public String describe(String tip) throws GitException, InterruptedException {
        try (Repository repo = getRepository()) {
            final ObjectReader or = repo.newObjectReader();
            final RevWalk w = new RevWalk(or); // How to dispose of this ?
            w.setRetainBody(false);

            Map<ObjectId,Ref> tags = new HashMap<>();
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
                final Ref tag;
                final RevFlag flag;

                /**
                 * This field number of commits that are reachable from the tip but
                 * not reachable from the tag.
                 */
                int depth;

                Candidate(RevCommit commit, Ref tag) {
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
            List<Candidate> candidates = new ArrayList<>();    // all the candidates we find

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
            Collections.sort(candidates, (Candidate o1, Candidate o2) -> o1.depth-o2.depth);

            return candidates.get(0).describe(tipId);
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public List<IndexEntry> lsTree(String treeIsh, boolean recursive) throws GitException, InterruptedException {
        try (Repository repo = getRepository();
             ObjectReader or = repo.newObjectReader();
             RevWalk w = new RevWalk(or)) {
            TreeWalk tree = new TreeWalk(or);
            tree.addTree(w.parseTree(repo.resolve(treeIsh)));
            tree.setRecursive(recursive);

            List<IndexEntry> r = new ArrayList<>();
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
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public void reset(boolean hard) throws GitException, InterruptedException {
        try (Repository repo = getRepository()) {
            ResetCommand reset = new ResetCommand(repo);
            reset.setMode(hard?HARD:MIXED);
            reset.call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    /** {@inheritDoc} */
    @SuppressFBWarnings(value = "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE",
                        justification = "JGit interaction with spotbugs")
    @Deprecated
    @Override
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
    @Override
    public String getDefaultRemote(String _default_) throws GitException, InterruptedException {
        Set<String> remotes = getConfig(null).getSubsections("remote");
        if (remotes.contains(_default_))    return _default_;
        else    return com.google.common.collect.Iterables.getFirst(remotes, null);
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    @SuppressFBWarnings(value = { "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
                                  "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE" },
                        justification = "Java 11 spotbugs error and JGit interaction with spotbugs")
    public void setRemoteUrl(String name, String url, String GIT_DIR) throws GitException, InterruptedException {
        try (Repository repo = new RepositoryBuilder().setGitDir(new File(GIT_DIR)).build()) {
            StoredConfig config = repo.getConfig();
            config.setString("remote", name, "url", url);
            config.save();
        } catch (IOException ioe) {
            throw new GitException(ioe);
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public String getRemoteUrl(String name, String GIT_DIR) throws GitException, InterruptedException {
        return getConfig(GIT_DIR).getString("remote", name, "url");
    }

    @SuppressFBWarnings(value = {"RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
                                 "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE"},
                        justification = "Java 11 spotbugs error and JGit interaction with spotbugs")
    private StoredConfig getConfig(String GIT_DIR) throws GitException {
        try (Repository repo = isBlank(GIT_DIR) ? getRepository() : new RepositoryBuilder().setWorkTree(new File(GIT_DIR)).build()) {
            return repo.getConfig();
        } catch (IOException ioe) {
            throw new GitException(ioe);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Set<GitObject> getTags() throws GitException, InterruptedException {
        Set<GitObject> peeledTags = new HashSet<>();
        Set<String> tagNames = new HashSet<>();
        try (Repository repo = getRepository()) {
            Map<String, Ref> tagsRead = repo.getTags();
            for (Map.Entry<String, Ref> entry : tagsRead.entrySet()) {
                /* Prefer peeled ref if available (for tag commit), otherwise take first tag reference seen */
                String tagName = entry.getKey();
                Ref tagRef = entry.getValue();
                if (!tagRef.isPeeled()) {
                    Ref peeledRef = repo.peel(tagRef);
                    if (peeledRef.getPeeledObjectId() != null) {
                        tagRef = peeledRef; // Use peeled ref instead of annotated ref
                    }
                }
                /* Packed lightweight (non-annotated) tags can wind up peeled with no peeled obj ID */
                if (tagRef.isPeeled() && tagRef.getPeeledObjectId() != null) {
                    peeledTags.add(new GitObject(tagName, tagRef.getPeeledObjectId()));
                } else if (!tagNames.contains(tagName)) {
                    peeledTags.add(new GitObject(tagName, tagRef.getObjectId()));
                }
                tagNames.add(tagName);
            }
        }
        return peeledTags;
    }

    private static class FileRepositoryImpl extends FileRepository {

        private final File tempDir;

        public FileRepositoryImpl(File gitDir, File tempDir) throws IOException {
            super(gitDir);
            this.tempDir = tempDir;
        }

        @Override
        public void close() {
            super.close();
            try {
                Util.deleteRecursive(tempDir);
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
