package org.jenkinsci.plugins.gitclient;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.git.*;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.InvalidPatternException;
import org.eclipse.jgit.fnmatch.FileNameMatcher;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.eclipse.jgit.api.ResetCommand.ResetType.HARD;
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.R_TAGS;

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
        throw new UnsupportedOperationException("not implemented yet");

        // Require a local repository, so can't be used in git-plugin context
        /*
        // based on org.eclipse.jgit.pgm.LsRemote
        try {
            final Transport tn = Transport.open(null, new URIish(remoteRepoUrl)); // fail NullPointerException
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
                }
            } catch (IOException e) {
                throw new GitException(e);
            } catch (URISyntaxException e) {
                throw new GitException(e);
            }
        return null;
        */
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
        throw new UnsupportedOperationException("not implemented yet");
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
        throw new UnsupportedOperationException("not implemented yet");
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
