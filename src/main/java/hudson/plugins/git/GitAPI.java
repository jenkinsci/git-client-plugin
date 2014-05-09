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
 * Backward compatible class to match the one some plugins used to get from git-plugin.
 * Extends CliGitAPIImpl to implement deprecated IGitAPI methods, but delegates supported methods to JGit implementation
 * until {@link Git#USE_CLI} is set.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 * @deprecated
 */
public class GitAPI extends CliGitAPIImpl {
    private final GitClient jgit;

    @Deprecated
    public GitAPI(String gitExe, FilePath repository, TaskListener listener, EnvVars environment) throws IOException, InterruptedException {
        this(gitExe, new File(repository.getRemote()), listener, environment);
    }

    @Deprecated
    public GitAPI(String gitExe, FilePath repository, TaskListener listener, EnvVars environment, String reference) throws IOException, InterruptedException {
        this(gitExe, repository, listener, environment);
    }

    @Deprecated
    public GitAPI(String gitExe, File repository, TaskListener listener, EnvVars environment) throws IOException, InterruptedException {
        super(gitExe, repository, listener, environment);

        // If USE_CLI is forced, don't delegate to JGit client
        this.jgit = Git.USE_CLI ? null : Git.with(listener, environment).in(repository).using("jgit").getClient();
    }

    // --- delegate implemented methods to JGit client

    public void add(String filePattern) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.add(filePattern); else  jgit.add(filePattern);
    }

    /*
    public List<ObjectId> revList(String ref) throws GitException {
        return Git.USE_CLI ? super.revList(ref) :  jgit.revList(ref);
    }
    */

    public String getRemoteUrl(String name) throws GitException, InterruptedException {
        return Git.USE_CLI ? super.getRemoteUrl(name) :  jgit.getRemoteUrl(name);
    }

    public void push(String remoteName, String refspec) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.push(remoteName, refspec); else  jgit.push(remoteName, refspec);
    }

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

    public GitClient subGit(String subdir) {
        return Git.USE_CLI ? super.subGit(subdir) :  jgit.subGit(subdir);
    }

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

    public Set<Branch> getRemoteBranches() throws GitException, InterruptedException {
        return Git.USE_CLI ? super.getRemoteBranches() :  jgit.getRemoteBranches();
    }

    public Set<Branch> getLocalBranches() throws GitException, InterruptedException {
        return Git.USE_CLI ? super.getLocalBranches() :  jgit.getLocalBranches();
    }
    
    public void init() throws GitException, InterruptedException {
        if (Git.USE_CLI) super.init(); else  jgit.init();
    }

    public void deleteBranch(String name) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.deleteBranch(name); else  jgit.deleteBranch(name);
    }

    public void checkout(String ref, String branch) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.checkout(ref, branch); else  jgit.checkout(ref, branch);
    }

    public boolean hasGitRepo() throws GitException, InterruptedException {
        return Git.USE_CLI ? super.hasGitRepo() :  jgit.hasGitRepo();
    }

    public boolean isCommitInRepo(ObjectId commit) throws GitException, InterruptedException {
        return Git.USE_CLI ? super.isCommitInRepo(commit) :  jgit.isCommitInRepo(commit);
    }

    /*
    public void setupSubmoduleUrls(Revision rev, TaskListener listener) throws GitException {
        if (Git.USE_CLI) super.setupSubmoduleUrls(rev, listener); else  jgit.setupSubmoduleUrls(rev, listener);
    }
    */

    public void commit(String message) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.commit(message); else  jgit.commit(message);
    }

    public void commit(String message, PersonIdent author, PersonIdent committer) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.commit(message, author, committer); else  jgit.commit(message, author, committer);
    }

    public void checkout(String ref) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.checkout(ref); else  jgit.checkout(ref);
    }

    public void deleteTag(String tagName) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.deleteTag(tagName); else  jgit.deleteTag(tagName);
    }

    @NonNull
    public Repository getRepository() throws GitException {
        return Git.USE_CLI ? super.getRepository() :  jgit.getRepository();
    }

    public void tag(String tagName, String comment) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.tag(tagName, comment); else  jgit.tag(tagName, comment);
    }

    /*
    public List<String> showRevision(ObjectId r) throws GitException {
        return Git.USE_CLI ? super.showRevision(r) :  jgit.showRevision(r);
    }
    */

    public void fetch(URIish url, List<RefSpec> refspecs) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.fetch(url, refspecs); else  jgit.fetch(url, refspecs);
    }

    public void fetch(String remoteName, RefSpec... refspec) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.fetch(remoteName, refspec); else  jgit.fetch(remoteName, refspec);
    }

    public void fetch(String remoteName, RefSpec refspec) throws GitException, InterruptedException {
        fetch(remoteName, new RefSpec[] {refspec});
    }

    public boolean tagExists(String tagName) throws GitException, InterruptedException {
        return Git.USE_CLI ? super.tagExists(tagName) :  jgit.tagExists(tagName);
    }

    /*
    public void submoduleClean(boolean recursive) throws GitException {
        if (Git.USE_CLI) super.submoduleClean(recursive); else  jgit.submoduleClean(recursive);
    }
    */

    public void clean() throws GitException, InterruptedException {
        if (Git.USE_CLI) super.clean(); else  jgit.clean();
    }

    public ObjectId revParse(String revName) throws GitException, InterruptedException {
        return Git.USE_CLI ? super.revParse(revName) :  jgit.revParse(revName);
    }

    public void branch(String name) throws GitException, InterruptedException {
        if (Git.USE_CLI) super.branch(name); else  jgit.branch(name);
    }
}
