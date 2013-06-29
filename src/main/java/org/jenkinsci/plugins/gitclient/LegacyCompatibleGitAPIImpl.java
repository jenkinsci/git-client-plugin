package org.jenkinsci.plugins.gitclient;

import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.Revision;
import hudson.plugins.git.Tag;
import hudson.remoting.Channel;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;

import java.io.File;
import java.io.IOException;
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
        // Assume there is only 1 URL / refspec for simplicity
        fetch(remoteRepository.getURIs().get(0).toPrivateString(), remoteRepository.getFetchRefSpecs().get(0).toString());
    }

    @Deprecated
    public void fetch() throws GitException, InterruptedException {
        fetch(null, (RefSpec) null);
    }


    public void reset() throws GitException, InterruptedException {
        reset(false);
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

    @Override
    protected Object writeReplace() {
        return remoteProxyFor(Channel.current().export(IGitAPI.class, this));
    }
}
