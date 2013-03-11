package hudson.plugins.git;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.jenkinsci.plugins.gitclient.CliGitAPIImpl;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backward compatible class to match the one some plugins used to get from git-plugin.
 * Extends CliGitAPIImpl to implement deprecated IGitAPI methods, but delegates supported methods to JGit implementation
 * until {@link Git#USE_CLI} is set.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 * @deprecated
 */
public class GitAPI extends CliGitAPIImpl implements IGitAPI {

    private final File repository;
    private final GitClient delegate;

    @Deprecated
    public GitAPI(String gitExe, FilePath repository, TaskListener listener, EnvVars environment) {
        this(gitExe, new File(repository.getRemote()), listener, environment);
    }

    @Deprecated
    public GitAPI(String gitExe, FilePath repository, TaskListener listener, EnvVars environment, String reference) {
        this(gitExe, repository, listener, environment);
    }

    @Deprecated
    public GitAPI(String gitExe, File repository, TaskListener listener, EnvVars environment) {
        super(gitExe, repository, listener, environment);
        this.repository = repository;

        // If USE_CLI is forced, don't delegate to JGit client
        this.delegate = Git.USE_CLI ? this : Git.with(listener, environment).in(repository).using("jgit").getClient();
    }

    // --- delegate implemented methods to JGit client

    public void add(String filePattern) throws GitException {
        delegate.add(filePattern);
    }

    /*
    public List<ObjectId> revList(String ref) throws GitException {
        return delegate.revList(ref);
    }
    */

    public String getRemoteUrl(String name) throws GitException {
        return delegate.getRemoteUrl(name);
    }

    public void push(String remoteName, String refspec) throws GitException {
        delegate.push(remoteName, refspec);
    }

    public String getTagMessage(String tagName) throws GitException {
        return delegate.getTagMessage(tagName);
    }

    /*
    public List<ObjectId> revListAll() throws GitException {
        return delegate.revListAll();
    }
    */

    /*
    public void addNote(String note, String namespace) throws GitException {
        delegate.addNote(note, namespace);
    }
    */

    /*
    public void appendNote(String note, String namespace) throws GitException {
        delegate.appendNote(note, namespace);
    }
    */

    /*
    public void changelog(String revFrom, String revTo, OutputStream fos) throws GitException {
        delegate.changelog(revFrom, revTo, fos);
    }
    */

    /*
    public List<IndexEntry> getSubmodules(String treeIsh) throws GitException {
        return delegate.getSubmodules(treeIsh);
    }
    */

    /*
    public ObjectId getHeadRev(String remoteRepoUrl, String branch) throws GitException {
        return delegate.getHeadRev(remoteRepoUrl, branch);
    }
    */

    /*
    public Set<String> getTagNames(String tagPattern) throws GitException {
        return delegate.getTagNames(tagPattern);
    }
    */

    public GitClient subGit(String subdir) {
        return delegate.subGit(subdir);
    }

    public void setRemoteUrl(String name, String url) throws GitException {
        delegate.setRemoteUrl(name, url);
    }

    /*
    public void prune(RemoteConfig repository) throws GitException {
        delegate.prune(repository);
    }
    */

    /*
    public void submoduleUpdate(boolean recursive) throws GitException {
        delegate.submoduleUpdate(recursive);
    }
    */

    /*
    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException {
        return delegate.showRevision(from, to);
    }
    */

    /*
    public boolean hasGitModules() throws GitException {
        return delegate.hasGitModules();
    }
    */

    public Set<Branch> getBranches() throws GitException {
        return delegate.getBranches();
    }

    /*
    public void addSubmodule(String remoteURL, String subdir) throws GitException {
        delegate.addSubmodule(remoteURL, subdir);
    }
    */

    /*
    public void clone(String url, String origin, boolean useShallowClone, String reference) throws GitException {
        delegate.clone(url, origin, useShallowClone, reference);
    }
    */

    public Set<Branch> getRemoteBranches() throws GitException {
        return delegate.getRemoteBranches();
    }

    public void init() throws GitException {
        delegate.init();
    }

    public void deleteBranch(String name) throws GitException {
        delegate.deleteBranch(name);
    }

    public void checkout(String ref, String branch) throws GitException {
        delegate.checkout(ref, branch);
    }

    public boolean hasGitRepo() throws GitException {
        return delegate.hasGitRepo();
    }

    public boolean isCommitInRepo(ObjectId commit) throws GitException {
        return delegate.isCommitInRepo(commit);
    }

    /*
    public void setupSubmoduleUrls(Revision rev, TaskListener listener) throws GitException {
        delegate.setupSubmoduleUrls(rev, listener);
    }
    */

    public void commit(String message) throws GitException {
        delegate.commit(message);
    }

    public void checkout(String ref) throws GitException {
        delegate.checkout(ref);
    }

    public void deleteTag(String tagName) throws GitException {
        delegate.deleteTag(tagName);
    }

    public Repository getRepository() throws GitException {
        return delegate.getRepository();
    }

    public void tag(String tagName, String comment) throws GitException {
        delegate.tag(tagName, comment);
    }

    /*
    public List<String> showRevision(ObjectId r) throws GitException {
        return delegate.showRevision(r);
    }
    */

    public void fetch(String remoteName, RefSpec refspec) throws GitException {
        delegate.fetch(remoteName, refspec);
    }

    public void merge(ObjectId rev) throws GitException {
        delegate.merge(rev);
    }

    public boolean tagExists(String tagName) throws GitException {
        return delegate.tagExists(tagName);
    }

    /*
    public void submoduleClean(boolean recursive) throws GitException {
        delegate.submoduleClean(recursive);
    }
    */

    public void clean() throws GitException {
        delegate.clean();
    }

    public ObjectId revParse(String revName) throws GitException {
        return delegate.revParse(revName);
    }

    public void branch(String name) throws GitException {
        delegate.branch(name);
    }





    // --- legacy methods, kept for backward compatibility
    
    @Deprecated
    public void checkoutBranch(String branch, String ref) throws GitException {
        try {
            // First, checkout to detached HEAD, so we can delete the branch.
            checkout(ref);

            if (branch!=null) {
                // Second, check to see if the branch actually exists, and then delete it if it does.
                for (Branch b : getBranches()) {
                    if (b.name.equals(branch)) {
                        deleteBranch(branch);
                    }
                }
                // Lastly, checkout the branch, creating it in the process, using commitish as the start point.
                checkout(ref, branch);
            }
        } catch (GitException e) {
            throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
        }
    }

    @Deprecated
    public void merge(String refSpec) throws GitException {
        try {
            launchCommand("merge", refSpec);
        } catch (GitException e) {
            throw new GitException("Could not merge " + refSpec, e);
        }
    }

    @Deprecated
    public boolean hasGitModules(String treeIsh) throws GitException {
        try {
            return new File(repository, ".gitmodules").exists();
        } catch (SecurityException ex) {
            throw new GitException(
                    "Security error when trying to check for .gitmodules. Are you sure you have correct permissions?",
                    ex);
        } catch (Exception e) {
            throw new GitException("Couldn't check for .gitmodules", e);
        }

    }

    @Deprecated
    public void setupSubmoduleUrls(String remote, TaskListener listener) throws GitException {
        // This is to make sure that we don't miss any new submodules or
        // changes in submodule origin paths...
        submoduleInit();
        submoduleSync();
        // This allows us to seamlessly use bare and non-bare superproject
        // repositories.
        fixSubmoduleUrls( remote, listener );
    }

    @Deprecated
    public void fetch(String repository, String refspec) throws GitException {
        fetch(repository, new RefSpec(refspec));
    }

    @Deprecated
    public void fetch(RemoteConfig remoteRepository) {
        // Assume there is only 1 URL / refspec for simplicity
        fetch(remoteRepository.getURIs().get(0).toPrivateString(), remoteRepository.getFetchRefSpecs().get(0).toString());
    }

    @Deprecated
    public void fetch() throws GitException {
        fetch(null, (RefSpec) null);
    }


    public void reset() throws GitException {
        reset(false);
    }

    @Deprecated
    public void push(RemoteConfig repository, String refspec) throws GitException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("push", repository.getURIs().get(0).toPrivateString());

        if (refspec != null)
            args.add(refspec);

        launchCommand(args);
        // Ignore output for now as there's many different formats
        // That are possible.

    }

    @Deprecated
    public void clone(RemoteConfig source) throws GitException {
        clone(source, false);
    }

    @Deprecated
    public void clone(RemoteConfig rc, boolean useShallowClone) throws GitException {
        // Assume only 1 URL for this repository
        final String source = rc.getURIs().get(0).toPrivateString();
        clone(source, rc.getName(), useShallowClone, null);
    }

    @Deprecated
    public List<Branch> getBranchesContaining(String revspec) throws GitException {
        return parseBranches(launchCommand("branch", "-a", "--contains", revspec));
    }

    @Deprecated
    private List<Branch> parseBranches(String fos) throws GitException {
        // TODO: git branch -a -v --abbrev=0 would do this in one shot..
        List<Branch> tags = new ArrayList<Branch>();
        BufferedReader rdr = new BufferedReader(new StringReader(fos));
        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                // Ignore the 1st
                line = line.substring(2);
                // Ignore '(no branch)' or anything with " -> ", since I think
                // that's just noise
                if ((!line.startsWith("("))
                        && (line.indexOf(" -> ") == -1)) {
                    tags.add(new Branch(line, revParse(line)));
                }
            }
        } catch (IOException e) {
            throw new GitException("Error parsing branches", e);
        }

        return tags;
    }

    @Deprecated
    public List<ObjectId> revListBranch(String branchId) throws GitException {
        return revList(branchId);
    }

    @Deprecated
    public List<String> showRevision(Revision r) throws GitException {
        return showRevision(null, r.getSha1());
    }


        @Deprecated
    public List<Tag> getTagsOnCommit(String revName) throws GitException, IOException {
        final Repository db = getRepository();
        try {
            final ObjectId commit = db.resolve(revName);
            final List<Tag> ret = new ArrayList<Tag>();

            for (final Map.Entry<String, Ref> tag : db.getTags().entrySet()) {
                final ObjectId tagId = tag.getValue().getObjectId();
                if (commit.equals(tagId))
                    ret.add(new Tag(tag.getKey(), tagId));
            }
            return ret;
        } finally {
            db.close();
        }
    }

    @Deprecated
    public ObjectId mergeBase(ObjectId id1, ObjectId id2) {
        try {
            String result;
            try {
                result = launchCommand("merge-base", id1.name(), id2.name());
            } catch (GitException ge) {
                return null;
            }


            BufferedReader rdr = new BufferedReader(new StringReader(result));
            String line;

            while ((line = rdr.readLine()) != null) {
                // Add the SHA1
                return ObjectId.fromString(line);
            }
        } catch (Exception e) {
            throw new GitException("Error parsing merge base", e);
        }

        return null;
    }

    @Deprecated
    public String getAllLogEntries(String branch) {
        return launchCommand("log", "--all", "--pretty=format:'%H#%ct'", branch);

    }

}
