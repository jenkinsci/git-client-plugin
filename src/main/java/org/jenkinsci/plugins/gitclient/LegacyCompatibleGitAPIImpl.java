package org.jenkinsci.plugins.gitclient;

import static java.util.Arrays.copyOfRange;
import static org.apache.commons.lang.StringUtils.join;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import hudson.plugins.git.Tag;
import hudson.remoting.Channel;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Partial implementation of {@link IGitAPI} by delegating to {@link GitClient} APIs.
 *
 * <p>
 * {@link IGitAPI} is still used by many others, such as git-plugin, so we want to support them in
 * both JGit and CGit, and often they can be implemented in terms of other methods, hence it's here.
 *
 * @author Kohsuke Kawaguchi
 */
abstract class LegacyCompatibleGitAPIImpl extends AbstractGitAPIImpl implements IGitAPI {

    public boolean isBareRepository() throws GitException, InterruptedException {
        return isBareRepository("");
    }

    // --- legacy methods, kept for backward compatibility
    protected final File workspace;

    protected LegacyCompatibleGitAPIImpl(File workspace) {
        this.workspace = workspace;
    }

    @Deprecated
    public boolean hasGitModules(String treeIsh) throws GitException {
        try {
            return new File(workspace, ".gitmodules").exists();
        } catch (SecurityException ex) {
            throw new GitException(
                    "Security error when trying to check for .gitmodules. Are you sure you have correct permissions?",
                    ex);
        } catch (Exception e) {
            throw new GitException("Couldn't check for .gitmodules", e);
        }

    }

    @Deprecated
    public void setupSubmoduleUrls(String remote, TaskListener listener) throws GitException, InterruptedException {
        // This is to make sure that we don't miss any new submodules or
        // changes in submodule origin paths...
        submoduleInit();
        submoduleSync();
        // This allows us to seamlessly use bare and non-bare superproject
        // repositories.
        fixSubmoduleUrls( remote, listener );
    }

    @Deprecated
    public void fetch(String repository, String refspec) throws GitException, InterruptedException {
        fetch(repository, new RefSpec(refspec));
    }

    @Deprecated
    public void fetch(RemoteConfig remoteRepository) throws InterruptedException {
        // Assume there is only 1 URL for simplicity
        fetch(remoteRepository.getURIs().get(0), remoteRepository.getFetchRefSpecs());
    }

    @Deprecated
    public void fetch() throws GitException, InterruptedException {
        fetch(null, (RefSpec) null);
    }

    @Deprecated
    public void reset() throws GitException, InterruptedException {
        reset(false);
    }


    @Deprecated
    public void push(URIish url, String refspec) throws GitException, InterruptedException {
        push().ref(refspec).to(url).execute();
    }

    @Deprecated
    public void push(String remoteName, String refspec) throws GitException, InterruptedException {
        String url = getRemoteUrl(remoteName);
        if (url == null) {
            throw new GitException("bad remote name, URL not set in working copy");
        }

        try {
            push(new URIish(url), refspec);
        } catch (URISyntaxException e) {
            throw new GitException("bad repository URL", e);
        }
    }

    @Deprecated
    public void clone(RemoteConfig source) throws GitException, InterruptedException {
        clone(source, false);
    }

    @Deprecated
    public void clone(RemoteConfig rc, boolean useShallowClone) throws GitException, InterruptedException {
        // Assume only 1 URL for this repository
        final String source = rc.getURIs().get(0).toPrivateString();
        clone(source, rc.getName(), useShallowClone, null);
    }

    @Deprecated
    public List<ObjectId> revListBranch(String branchId) throws GitException, InterruptedException {
        return revList(branchId);
    }

    @Deprecated
    public List<String> showRevision(Revision r) throws GitException, InterruptedException {
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

    public final List<IndexEntry> lsTree(String treeIsh) throws GitException, InterruptedException {
        return lsTree(treeIsh,false);
    }

    @Override
    protected Object writeReplace() {
        return remoteProxyFor(Channel.current().export(IGitAPI.class, this));
    }

    public boolean hasGitModules() throws GitException {
        try {

            File dotGit = new File(workspace, ".gitmodules");

            return dotGit.exists();

        } catch (SecurityException ex) {
            throw new GitException(
                                   "Security error when trying to check for .gitmodules. Are you sure you have correct permissions?",
                                   ex);
        } catch (Exception e) {
            throw new GitException("Couldn't check for .gitmodules", e);
        }
    }

    public List<String> showRevision(ObjectId r) throws GitException, InterruptedException {
        return showRevision(null, r);
    }
    
    /**
     * This method takes a branch specification and normalizes it get unambiguous results.
     * This is the case when using "refs/heads/"<br/>
     * <br/>
     * TODO: Currently only for specs starting with "refs/heads/" the implementation is correct.
     * All others branch specs should also be normalized to "refs/heads/" in order to get unambiguous results.
     * To achieve this it is necessary to identify remote names in the branch spec and to discuss how
     * to handle clashes (e.g. "remoteName/master" for branch "master" (refs/heads/master) in remote "remoteName" and branch "remoteName/master" (refs/heads/remoteName/master)). 
     * <br/><br/>  
     * E.g.
     * <table>
     * <tr><th align="left">branch spec</th><th align="left">normalized</th></tr>
     * <tr><td><tt>master</tt></th><td><tt>master*</td></tr>
     * <tr><td><tt>feature1</tt></th><td><tt>feature1*</td></tr>
     * <tr><td><tt>feature1/master</tt></th><td><div style="color:red">master <strike><tt>feature1/master</tt></strike>*</div></td></tr>
     * <tr><td><tt>origin/master</tt></th><td><tt>master*</td></tr>
     * <tr><td><tt>repo2/feature1</tt></th><td><tt>feature1*</td></tr>
     * <tr><td><tt>refs/heads/feature1</tt></th><td><tt>refs/heads/feature1</td></tr>
     * <tr><td valign="top">origin/namespaceA/fix15</th>
     *     <td><div style="color:red">fix15 <strike><tt>namespaceA/fix15</tt></strike>*</div></td><td></td></tr>
     * <tr><td><tt>refs/heads/namespaceA/fix15</tt></th><td><tt>refs/heads/namespaceA/fix15</td></tr>
     * <tr><td><tt>remotes/origin/namespaceA/fix15</tt></th><td><tt>refs/heads/namespaceA/fix15</tt></td></tr>
     * </table><br/>
     * *) TODO: Normalize to "refs/heads/"
     * @param branchSpec
     * @return normalized branchSpec
     */
    protected String normalizeBranchSpec(String branchSpec) {
        String branch = branchSpec;
        String[] branchExploded = branchSpec.split("/");
        if (branchSpec.startsWith("remotes/")) {
            branch = "refs/heads/" + join(copyOfRange(branchExploded, 2, branchExploded.length), "/");
        } else if (branchSpec.startsWith("refs/remotes/")) {
            branch = "refs/heads/" + join(copyOfRange(branchExploded, 3, branchExploded.length), "/");
        } else if (branchSpec.startsWith("refs/heads/")) {
            branch = branchSpec;
        } else if (branchSpec.startsWith("refs/tags/")) {
            //TODO: Discuss if tags shall be allowed.
            //hudson.plugins.git.util.DefaultBuildChooser.getCandidateRevisions() in git plugin 2.0.1 explicitly allowed it.
            branch = branchSpec;
        } else {
            /* Old behaviour. 
             * TODO: Taking last element is not enough! Should be normalized to "refs/heads/..." as well.
             * Assume branch "feature1/master". ls-remote will return multiple results. CliGitAPIImpl takes first one. 
             */
            branch = branchExploded[branchExploded.length-1];
        }
        return branch;
    }
    
}
