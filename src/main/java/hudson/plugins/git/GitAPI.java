package hudson.plugins.git;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
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
    private final GitClient jgit;

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
        this.jgit = Git.USE_CLI ? null : Git.with(listener, environment).in(repository).using("jgit").getClient();
    }

    // --- delegate implemented methods to JGit client

    public void add(String filePattern) throws GitException {
        if (Git.USE_CLI) super.add(filePattern); else  jgit.add(filePattern);
    }

    /*
    public List<ObjectId> revList(String ref) throws GitException {
        return Git.USE_CLI ? super.revList(ref) :  jgit.revList(ref);
    }
    */

    public String getRemoteUrl(String name) throws GitException {
        return Git.USE_CLI ? super.getRemoteUrl(name) :  jgit.getRemoteUrl(name);
    }

    public void push(String remoteName, String refspec) throws GitException {
        if (Git.USE_CLI) super.push(remoteName, refspec); else  jgit.push(remoteName, refspec);
    }

    public String getTagMessage(String tagName) throws GitException {
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

    public GitClient subGit(String subdir) {
        return Git.USE_CLI ? super.subGit(subdir) :  jgit.subGit(subdir);
    }

    public void setRemoteUrl(String name, String url) throws GitException {
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
    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException {
        return Git.USE_CLI ? super.showRevision(from, to) :  jgit.showRevision(from, to);
    }
    */

    /*
    public boolean hasGitModules() throws GitException {
        return Git.USE_CLI ? super.hasGitModules() :  jgit.hasGitModules();
    }
    */

    public Set<Branch> getBranches() throws GitException {
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

    public Set<Branch> getRemoteBranches() throws GitException {
        return Git.USE_CLI ? super.getRemoteBranches() :  jgit.getRemoteBranches();
    }

    public void init() throws GitException {
        if (Git.USE_CLI) super.init(); else  jgit.init();
    }

    public void deleteBranch(String name) throws GitException {
        if (Git.USE_CLI) super.deleteBranch(name); else  jgit.deleteBranch(name);
    }

    public void checkout(String ref, String branch) throws GitException {
        if (Git.USE_CLI) super.checkout(ref, branch); else  jgit.checkout(ref, branch);
    }

    public boolean hasGitRepo() throws GitException {
        return Git.USE_CLI ? super.hasGitRepo() :  jgit.hasGitRepo();
    }

    public boolean isCommitInRepo(ObjectId commit) throws GitException {
        return Git.USE_CLI ? super.isCommitInRepo(commit) :  jgit.isCommitInRepo(commit);
    }

    /*
    public void setupSubmoduleUrls(Revision rev, TaskListener listener) throws GitException {
        if (Git.USE_CLI) super.setupSubmoduleUrls(rev, listener); else  jgit.setupSubmoduleUrls(rev, listener);
    }
    */

    public void commit(String message) throws GitException {
        if (Git.USE_CLI) super.commit(message); else  jgit.commit(message);
    }

    public void commit(String message, PersonIdent author, PersonIdent committer) throws GitException {
        if (Git.USE_CLI) super.commit(message, author, committer); else  jgit.commit(message, author, committer);
    }

    public void checkout(String ref) throws GitException {
        if (Git.USE_CLI) super.checkout(ref); else  jgit.checkout(ref);
    }

    public void deleteTag(String tagName) throws GitException {
        if (Git.USE_CLI) super.deleteTag(tagName); else  jgit.deleteTag(tagName);
    }

    public Repository getRepository() throws GitException {
        return Git.USE_CLI ? super.getRepository() :  jgit.getRepository();
    }

    public void tag(String tagName, String comment) throws GitException {
        if (Git.USE_CLI) super.tag(tagName, comment); else  jgit.tag(tagName, comment);
    }

    /*
    public List<String> showRevision(ObjectId r) throws GitException {
        return Git.USE_CLI ? super.showRevision(r) :  jgit.showRevision(r);
    }
    */

    public void fetch(String remoteName, RefSpec refspec) throws GitException {
        if (Git.USE_CLI) super.fetch(remoteName, refspec); else  jgit.fetch(remoteName, refspec);
    }

    public void merge(ObjectId rev) throws GitException {
        if (Git.USE_CLI) super.merge(rev); else  jgit.merge(rev);
    }

    public boolean tagExists(String tagName) throws GitException {
        return Git.USE_CLI ? super.tagExists(tagName) :  jgit.tagExists(tagName);
    }

    /*
    public void submoduleClean(boolean recursive) throws GitException {
        if (Git.USE_CLI) super.submoduleClean(recursive); else  jgit.submoduleClean(recursive);
    }
    */

    public void clean() throws GitException {
        if (Git.USE_CLI) super.clean(); else  jgit.clean();
    }

    public ObjectId revParse(String revName) throws GitException {
        return Git.USE_CLI ? super.revParse(revName) :  jgit.revParse(revName);
    }

    public void branch(String name) throws GitException {
        if (Git.USE_CLI) super.branch(name); else  jgit.branch(name);
    }





    // --- legacy methods, kept for backward compatibility
    
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
