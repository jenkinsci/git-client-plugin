package org.jenkinsci.plugins.gitclient;

import hudson.FilePath;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.errors.InvalidPatternException;
import org.eclipse.jgit.fnmatch.FileNameMatcher;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.MaxCountRevFilter;
import org.eclipse.jgit.storage.file.FileRepository;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.eclipse.jgit.api.ResetCommand.ResetType.*;
import static org.eclipse.jgit.lib.Constants.*;

/**
 * GitClient pure Java implementation using JGit.
 * Goal is to eventually get a full java implementation for GitClient
 * <b>
 * For internal use only, don't use directly. See {@link org.jenkinsci.plugins.gitclient.Git}
 * </b>
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class JGitAPIImpl extends AbstractGitAPIImpl {

    private final File workspace;
    private final TaskListener listener;
    private PersonIdent author, committer;

    JGitAPIImpl(File workspace, TaskListener listener) {
        this.workspace = workspace;
        this.listener = listener;
    }

    public GitClient subGit(String subdir) {
        return new JGitAPIImpl(new File(workspace, subdir), listener);
    }

    public void setAuthor(String name, String email) throws GitException {
        author = new PersonIdent(name,email);
    }

    public void setCommitter(String name, String email) throws GitException {
        committer = new PersonIdent(name,email);
    }

    public void init() throws GitException {
        try {
            Git.init().setDirectory(workspace).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void checkout(String ref) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.checkout().setName(ref).setForce(true).call();
        } catch (IOException e) {
            throw new GitException("Could not checkout " + ref, e);
        } catch (GitAPIException e) {
            throw new GitException("Could not checkout " + ref, e);
        }
    }

    public void checkout(String ref, String branch) throws GitException {
        try {
            Repository r = getRepository();
            if (ref == null) ref = r.resolve(HEAD).name();
            Git git = Git.wrap(r);
            git.checkout().setName(branch).setCreateBranch(true).setForce(true).setStartPoint(ref).call();
        } catch (IOException e) {
            throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
        } catch (GitAPIException e) {
            throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
        }
    }

    public void checkoutBranch(String branch, String ref) throws GitException {
        try {
            Repository r = getRepository();
            RefUpdate refUpdate = r.updateRef(Constants.R_HEADS + branch);
            refUpdate.setNewObjectId(r.resolve(ref));
            switch (refUpdate.forceUpdate()) {
            case NOT_ATTEMPTED:
            case LOCK_FAILURE:
            case REJECTED:
            case REJECTED_CURRENT_BRANCH:
            case IO_FAILURE:
            case RENAMED:
                throw new GitException("Could not update " + branch + " to " + ref);
            }

            checkout(ref);
        } catch (IOException e) {
            throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
        }
    }


    public void add(String filePattern) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.add().addFilepattern(filePattern).call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void commit(String message) throws GitException {
        try {
            Git git = Git.open(workspace);
            CommitCommand cmd = git.commit().setMessage(message);
            if (author!=null)
                cmd.setAuthor(author);
            if (committer!=null)
                cmd.setCommitter(new PersonIdent(committer,new Date()));
            cmd.call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void branch(String name) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.branchCreate().setName(name).call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void deleteBranch(String name) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.branchDelete().setForce(true).setBranchNames(name).call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public Set<Branch> getBranches() throws GitException {
        try {
            Git git = Git.open(workspace);
            List<Ref> refs = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call();
            Set<Branch> branches = new HashSet<Branch>(refs.size());
            for (Ref ref : refs) {
                branches.add(new Branch(ref));
            }
            return branches;
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public Set<Branch> getRemoteBranches() throws GitException {
        try {
            Git git = Git.open(workspace);
            List<Ref> refs = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();
            Set<Branch> branches = new HashSet<Branch>(refs.size());
            for (Ref ref : refs) {
                branches.add(new Branch(ref));
            }
            return branches;
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void tag(String name, String message) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.tag().setName(name).setMessage(message).setForceUpdate(true).call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public boolean tagExists(String tagName) throws GitException {
        Repository db = getRepository();
        try {
            Ref tag =  db.getRefDatabase().getRef(R_TAGS + tagName);
            return tag != null;
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            db.close();
        }
    }


    public void fetch(String remoteName, RefSpec refspec) throws GitException {
        try {
            Git git = Git.open(workspace);
            FetchCommand fetch = git.fetch().setTagOpt(TagOpt.FETCH_TAGS);
            if (remoteName != null) fetch.setRemote(remoteName);

            // see http://stackoverflow.com/questions/14876321/jgit-fetch-dont-update-tag
            List<RefSpec> refSpecs = new ArrayList<RefSpec>();
            refSpecs.add(new RefSpec("+refs/tags/*:refs/tags/*"));
            if (refspec != null) refSpecs.add(refspec);
            fetch.setRefSpecs(refSpecs);

            fetch.call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public ObjectId getHeadRev(String remoteRepoUrl, String branch) throws GitException {
        try {
            FileRepository repo = openDummyRepository();
            final Transport tn = Transport.open(repo, new URIish(remoteRepoUrl));
            final FetchConnection c = tn.openFetch();
            try {
                for (final Ref r : c.getRefs()) {
                    if (branch.equals(r.getName())) {
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
        }
        return null;
    }

    /**
     * Creates a empty dummy {@link Repository} to keep JGit happy where it wants a valid {@link Repository} operation
     * for remote objects.
     */
    private FileRepository openDummyRepository() throws IOException {
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

    public String getRemoteUrl(String name) throws GitException {
        Repository db = getRepository();
        try {
            return db.getConfig().getString("remote",name,"url");
        } finally {
            db.close();
        }
    }

    public Repository getRepository() throws GitException {
        try {
            Git git = Git.open(workspace);
            return git.getRepository();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    public FilePath getWorkTree() {
        return new FilePath(workspace);
    }

    public void merge(ObjectId rev) throws GitException {
        try {
            Git git = Git.open(workspace);
            MergeResult mergeResult = git.merge().include(rev).call();
            if (!mergeResult.getMergeStatus().isSuccessful()) {
                git.reset().setMode(HARD).call();
                throw new GitException("Failed to merge " + rev);
            }
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException("Failed to merge " + rev, e);
        }
    }

    public void setRemoteUrl(String name, String url) throws GitException {
        Repository db = getRepository();
        try {
            StoredConfig config = db.getConfig();
            config.setString("remote", name, "url", url);
            config.save();
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            db.close();
        }
    }


    // --- to be implemented

    public void addNote(String note, String namespace) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public void appendNote(String note, String namespace) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public ChangelogCommand changelog() {
        return new ChangelogCommand() {
            Repository repo = getRepository();
            ObjectReader reader = repo.newObjectReader();
            RevWalk walk = new RevWalk(reader);
            Writer out;

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
                    return includes(repo.resolve(rev));
                } catch (IOException e) {
                    throw new GitException(e);
                }
            }

            public ChangelogCommand includes(ObjectId rev) {
                try {
                    walk.markStart(walk.lookupCommit(rev));
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

            public void execute() throws GitException, InterruptedException {
                PrintWriter pw = new PrintWriter(out,false);
                try {
                    final RenameDetector rd = new RenameDetector(repo);

                    for (RevCommit commit : walk) {
                        // git whatachanged doesn't show the merge commits unless -m is given
                        if (commit.getParentCount()>1)  continue;

                        pw.printf("commit %s\n", commit.name());
                        pw.printf("tree %s\n", commit.getTree().name());
                        for (RevCommit parent : commit.getParents())
                            pw.printf("parent %s\n",parent.name());
                        pw.printf("author %s\n", commit.getAuthorIdent().toExternalString());
                        pw.printf("committer %s\n", commit.getCommitterIdent().toExternalString());

                        // indent commit messages by 4 chars
                        String msg = commit.getFullMessage();
                        if (msg.endsWith("\n")) msg=msg.substring(0,msg.length()-1);
                        msg = msg.replace("\n","\n    ");
                        msg="    "+msg+"\n";

                        pw.println(msg);

                        // see man git-diff-tree for the format
                        TreeWalk tw = TreeWalk.forPath(reader, "", commit.getParent(0).getTree(), commit.getTree());
                        tw.setRecursive(true);
                        tw.setFilter(TreeFilter.ANY_DIFF);

                        rd.reset();
                        rd.addAll(DiffEntry.scan(tw));
                        List<DiffEntry> diffs = rd.compute(reader, null);
                        for (DiffEntry diff : diffs) {
                            pw.printf(":%06o %06o %s %s %s %s",
                                    diff.getOldMode().getBits(),
                                    diff.getNewMode().getBits(),
                                    diff.getOldId().name(),
                                    diff.getNewId().name(),
                                    statusOf(diff),
                                    diff.getOldPath());

                            if (hasNewPath(diff)) {
                                pw.printf(" %s",diff.getNewPath()); // copied to
                            }
                            pw.println();
                            pw.println();
                        }
                    }
                } catch (IOException e) {
                    throw new GitException(e);
                } finally {
                    pw.flush();
                    repo.close();
                }
            }

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
        };
    }

    public void clean() throws GitException {
        try {
            Git git = Git.open(workspace);
            git.reset().setMode(HARD).call();
            git.clean().setCleanDirectories(true).setIgnore(false).call();

        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public CloneCommand clone_() {
        final org.eclipse.jgit.api.CloneCommand base = new org.eclipse.jgit.api.CloneCommand();
        base.setDirectory(workspace);
        base.setProgressMonitor(new ProgressMonitor(listener));

        return new CloneCommand() {

            public CloneCommand url(String url) {
                base.setURI(url);
                return this;
            }

            public CloneCommand repositoryName(String name) {
                base.setRemote(name);
                return this;
            }

            public CloneCommand shallow() {
                listener.getLogger().println("[WARNING] JGit doesn't support shallow clone. This flag is ignored");
                return this;
            }

            public CloneCommand reference(String reference) {
                listener.getLogger().println("[WARNING] JGit doesn't support reference repository. This flag is ignored.");
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                try {
                    base.call();
                } catch (GitAPIException e) {
                    throw new GitException(e);
                }
            }
        };
    }

    public void deleteTag(String tagName) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.tagDelete().setTags(tagName).call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public String getTagMessage(String tagName) throws GitException {
        Repository db = getRepository();
        try {
            RevWalk walk = new RevWalk(db);
            String s = walk.parseTag(db.resolve(tagName)).getFullMessage();
            walk.dispose();
            return s.trim();
        } catch (IOException e) {
            throw new GitException(e);
        } finally {
            db.close();
        }
    }

    public List<IndexEntry> getSubmodules(String treeIsh) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public void addSubmodule(String remoteURL, String subdir) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.submoduleAdd().setPath(subdir).setURI(remoteURL).call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public Set<String> getTagNames(String tagPattern) throws GitException {
        if (tagPattern == null) tagPattern = "*";

        Repository db = getRepository();
        try {
            Set<String> tags = new HashSet<String>();
            FileNameMatcher matcher = new FileNameMatcher(tagPattern, '/');
            Map<String, Ref> refList = db.getRefDatabase().getRefs(R_TAGS);
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
            db.close();
        }
    }

    public boolean hasGitModules() throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public boolean hasGitRepo() throws GitException {
        Repository db = null;
        try {
            db = getRepository();
            return true;
        } catch (GitException e) {
            return false;
        } finally {
            if (db != null) db.close();
        }
    }

    public boolean isCommitInRepo(ObjectId commit) throws GitException {
        Repository db = getRepository();
        try {
            return db.hasObject(commit);
        } finally {
            db.close();
        }
    }

    public void prune(RemoteConfig repository) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public void push(String remoteName, String refspec) throws GitException {
        RefSpec ref = (refspec != null) ? new RefSpec(refspec) : Transport.REFSPEC_PUSH_ALL;
        try {
            Git git = Git.open(workspace);
            git.push().setRemote(remoteName).setRefSpecs(ref).setProgressMonitor(new ProgressMonitor(listener)).call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public List<ObjectId> revListAll() throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public List<ObjectId> revList(String ref) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public ObjectId revParse(String revName) throws GitException {
        Repository db = getRepository();
        try {
            ObjectId id = db.resolve(revName + "^{commit}");
            if (id == null)
                throw new GitException("Unknown git object "+ revName);
            return id;
        } catch (IOException e) {
            throw new GitException("Failed to resolve git reference "+ revName, e);
        } finally {
            db.close();
        }
    }

    public void setupSubmoduleUrls(Revision rev, TaskListener listener) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public List<String> showRevision(ObjectId r) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public void submoduleClean(boolean recursive) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public void submoduleUpdate(boolean recursive) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
