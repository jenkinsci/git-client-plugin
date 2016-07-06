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

import hudson.remoting.VirtualChannel;
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

    /**
     * isBareRepository.
     *
     * @return true if this repository is a bare repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public boolean isBareRepository() throws GitException, InterruptedException, IOException {
        return isBareRepository("");
    }

    // --- legacy methods, kept for backward compatibility

    /** {@inheritDoc} */
    @Deprecated
    public void setupSubmoduleUrls(String remote, TaskListener listener) throws GitException, InterruptedException, IOException {
        // This is to make sure that we don't miss any new submodules or
        // changes in submodule origin paths...
        submoduleInit();
        submoduleSync();
        // This allows us to seamlessly use bare and non-bare superproject
        // repositories.
        fixSubmoduleUrls( remote, listener );
    }

    /** {@inheritDoc} */
    @Deprecated
    public void fetch(String repository, String refspec) throws GitException, InterruptedException, IOException {
        fetch(repository, new RefSpec(refspec));
    }

    /** {@inheritDoc} */
    @Deprecated
    public void fetch(RemoteConfig remoteRepository) throws InterruptedException, IOException {
        // Assume there is only 1 URL for simplicity
        fetch(remoteRepository.getURIs().get(0), remoteRepository.getFetchRefSpecs());
    }

    /**
     * fetch.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    public void fetch() throws GitException, InterruptedException, IOException {
        fetch(null, (RefSpec) null);
    }

    /**
     * reset.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    public void reset() throws GitException, InterruptedException, IOException {
        reset(false);
    }


    /** {@inheritDoc} */
    @Deprecated
    public void push(URIish url, String refspec) throws GitException, InterruptedException, IOException {
        push().ref(refspec).to(url).execute();
    }

    /** {@inheritDoc} */
    @Deprecated
    public void push(String remoteName, String refspec) throws GitException, InterruptedException, IOException {
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

    /** {@inheritDoc} */
    @Deprecated
    public void clone(RemoteConfig source) throws GitException, InterruptedException, IOException {
        clone(source, false);
    }

    /** {@inheritDoc} */
    @Deprecated
    public void clone(RemoteConfig rc, boolean useShallowClone) throws GitException, InterruptedException, IOException {
        // Assume only 1 URL for this repository
        final String source = rc.getURIs().get(0).toPrivateString();
        clone(source, rc.getName(), useShallowClone, null);
    }

    /** {@inheritDoc} */
    @Deprecated
    public List<ObjectId> revListBranch(String branchId) throws GitException, InterruptedException, IOException {
        return revList(branchId);
    }

    /** {@inheritDoc} */
    @Deprecated
    public List<String> showRevision(Revision r) throws GitException, InterruptedException, IOException {
        return showRevision(null, r.getSha1());
    }

    /** {@inheritDoc} */
    @Deprecated
    public List<Tag> getTagsOnCommit(final String revName) throws GitException, IOException, InterruptedException {
        return withRepository(new RepositoryCallback<List<Tag>>() {
            @Override
            public List<Tag> invoke(Repository db, VirtualChannel channel) throws IOException, InterruptedException {
                try {
                    final ObjectId commit = db.resolve(revName);
                    final List<Tag> ret = new ArrayList<Tag>();

                    for (final Map.Entry<String, Ref> tag : db.getTags().entrySet()) {
                        Ref value = tag.getValue();
                        if (value != null) {
                            final ObjectId tagId = value.getObjectId();
                            if (commit != null && commit.equals(tagId))
                                ret.add(new Tag(tag.getKey(), tagId));
                        }
                    }
                    return ret;
                } finally {
                    db.close();
                }
            }
        });
    }

    /** {@inheritDoc} */
    public final List<IndexEntry> lsTree(String treeIsh) throws GitException, InterruptedException, IOException {
        return lsTree(treeIsh,false);
    }

    /** {@inheritDoc} */
    @Override
    protected Object writeReplace() {
        return remoteProxyFor(Channel.current().export(IGitAPI.class, this));
    }

    /** {@inheritDoc} */
    public List<String> showRevision(ObjectId r) throws GitException, InterruptedException, IOException {
        return showRevision(null, r);
    }
    
    /**
     * This method takes a branch specification and normalizes it get unambiguous results.
     * This is the case when using "refs/heads/"<br>
     * <br>
     * TODO: Currently only for specs starting with "refs/heads/" the implementation is correct.
     * All others branch specs should also be normalized to "refs/heads/" in order to get unambiguous results.
     * To achieve this it is necessary to identify remote names in the branch spec and to discuss how
     * to handle clashes (e.g. "remoteName/master" for branch "master" (refs/heads/master) in remote "remoteName" and branch "remoteName/master" (refs/heads/remoteName/master)).
     * <br><br>
     * Existing behavior is intentionally being retained so that
     * current use cases are not disrupted by a behavioral change.
     * <br><br>
     * E.g.
     * <table summary="Branch Spec Normalization Examples">
     * <tr><th align="left">branch spec</th><th align="left">normalized</th></tr>
     * <tr><td><tt>master</tt></td><td><tt>master*</tt></td></tr>
     * <tr><td><tt>feature1</tt></td><td><tt>feature1*</tt></td></tr>
     * <tr><td><tt>feature1/master</tt></td><td><div style="color:red">master <tt>feature1/master</tt>*</div></td></tr>
     * <tr><td><tt>origin/master</tt></td><td><tt>master*</tt></td></tr>
     * <tr><td><tt>repo2/feature1</tt></td><td><tt>feature1*</tt></td></tr>
     * <tr><td><tt>refs/heads/feature1</tt></td><td><tt>refs/heads/feature1</tt></td></tr>
     * <tr><td valign="top">origin/namespaceA/fix15</td>
     *     <td><div style="color:red">fix15 <tt>namespaceA/fix15</tt>*</div></td><td></td></tr>
     * <tr><td><tt>refs/heads/namespaceA/fix15</tt></td><td><tt>refs/heads/namespaceA/fix15</tt></td></tr>
     * <tr><td><tt>remotes/origin/namespaceA/fix15</tt></td><td><tt>refs/heads/namespaceA/fix15</tt></td></tr>
     * </table><br>
     * *) TODO: Normalize to "refs/heads/"
     *
     * @param branchSpec a {@link java.lang.String} object.
     * @return normalized branch name
     */
    protected String extractBranchNameFromBranchSpec(String branchSpec) {
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
            /* Old behaviour - retained for compatibility.
             *
             * Takes last element, though taking last element is not
             * enough. Should be normalized to "refs/heads/..." as
             * well, but would break compatibility with some existing
             * jobs.
             */
            branch = branchExploded[branchExploded.length-1];
        }
        return branch;
    }
    
}
