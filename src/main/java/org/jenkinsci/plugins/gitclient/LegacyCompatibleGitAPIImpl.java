package org.jenkinsci.plugins.gitclient;

import static java.util.Arrays.copyOfRange;
import static org.apache.commons.lang.StringUtils.join;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
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
import java.net.URI;
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
    public boolean isBareRepository() throws GitException, InterruptedException {
        return isBareRepository("");
    }

    // --- legacy methods, kept for backward compatibility
    protected final File workspace;

    /**
     * Constructor for LegacyCompatibleGitAPIImpl.
     *
     * @param workspace a {@link java.io.File} object.
     */
    protected LegacyCompatibleGitAPIImpl(File workspace) {
        this.workspace = workspace;
    }

    /** {@inheritDoc} */
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

    /**
     * Get the default remote.
     *
     * @throws hudson.plugins.git.GitException if executing the git command fails
     * @throws java.lang.InterruptedException if interrupted
     * @return default git remote for this repository (often "origin")
     */
    public String getDefaultRemote() throws GitException, InterruptedException {
        return getDefaultRemote("origin");
    }

    /**
     * {@inheritDoc}
     *
     * Set up submodule URLs so that they correspond to the remote pertaining to
     * the revision that has been checked out.
     */
    public void setupSubmoduleUrls( Revision rev, TaskListener listener ) throws GitException, InterruptedException {
        String remote = null;

        // try to locate the remote repository from where this commit came from
        // (by using the heuristics that the branch name, if available, contains the remote name)
        // if we can figure out the remote, the other setupSubmoduleUrls method
        // look at its URL, and if it's a non-bare repository, we attempt to retrieve modules
        // from this checked out copy.
        //
        // the idea is that you have something like tree-structured repositories: at the root you have corporate central repositories that you
        // ultimately push to, which all .gitmodules point to, then you have intermediate team local repository,
        // which is assumed to be a non-bare repository (say, a checked out copy on a shared server accessed via SSH)
        //
        // the abovementioned behaviour of the Git plugin makes it pick up submodules from this team local repository,
        // not the corporate central.
        //
        // (Kohsuke: I have a bit of hesitation/doubt about such a behaviour change triggered by seemingly indirect
        // evidence of whether the upstream is bare or not (not to mention the fact that you can't reliably
        // figure out if the repository is bare or not just from the URL), but that's what apparently has been implemented
        // and we care about the backward compatibility.)
        //
        // note that "figuring out which remote repository the commit came from" isn't a well-defined
        // question, and this is really a heuristics. The user might be telling us to build a specific SHA1.
        // or maybe someone pushed directly to the workspace and so it may not correspond to any remote branch.
        // so if we fail to figure this out, we back out and avoid being too clever. See JENKINS-10060 as an example
        // of where our trying to be too clever here is breaking stuff for people.
        for (Branch br : rev.getBranches()) {
            String b = br.getName();
            if (b != null) {
                int slash = b.indexOf('/');

                if ( slash != -1 )
                    remote = getDefaultRemote( b.substring(0,slash) );
            }

            if (remote!=null)   break;
        }

        if (remote==null)
            remote = getDefaultRemote();

        if (remote!=null)
            setupSubmoduleUrls( remote, listener );
    }

    private String pathJoin( String a, String b ) {
        return new File(a, b).toString();
    }

    /**
     * {@inheritDoc}
     *
     * Fixes urls for submodule as stored in .git/config and
     * $SUBMODULE/.git/config for when the remote repo is NOT a bare repository.
     * It is only really possible to detect whether a repository is bare if we
     * have local access to the repository.  If the repository is remote, we
     * therefore must default to believing that it is either bare or NON-bare.
     * The defaults are according to the ending of the super-project
     * remote.origin.url:
     *  - Ends with "/.git":  default is NON-bare
     *  -         otherwise:  default is bare
     *  .
     */
    public void fixSubmoduleUrls( String remote,
                                  TaskListener listener ) throws GitException, InterruptedException {
        boolean is_bare = true;

        URI origin;
        try {
            String url = getRemoteUrl(remote);
            if (url == null)
                throw new GitException("remote." + remote + ".url not defined in workspace");

            // ensure that any /.git ending is removed
            String gitEnd = pathJoin("", ".git");
            if ( url.endsWith( gitEnd ) ) {
                url = url.substring(0, url.length() - gitEnd.length() );
                // change the default detection value to NON-bare
                is_bare = false;
            }

            origin = new URI( url );
        } catch (URISyntaxException e) {
            // Sometimes the URI is of a form that we can't parse; like
            //   user@git.somehost.com:repository
            // In these cases, origin is null and it's best to just exit early.
            return;
        } catch (Exception e) {
            throw new GitException("Could not determine remote." + remote + ".url", e);
        }

        if ( origin.getScheme() == null ||
             ( "file".equalsIgnoreCase( origin.getScheme() ) &&
               ( origin.getHost() == null || "".equals( origin.getHost() ) )
             )
        ) {
            // The uri is a local path, so we will test to see if it is a bare
            // repository...
            List<String> paths = new ArrayList<String>();
            paths.add( origin.getPath() );
            paths.add( pathJoin( origin.getPath(), ".git" ) );

            for ( String path : paths ) {
                try {
                    is_bare = isBareRepository(path);
                    break;// we can break already if we don't have an exception
                } catch (GitException e) { }
            }
        }

        if ( ! is_bare ) {
            try {
                List<IndexEntry> submodules = getSubmodules("HEAD");

                for (IndexEntry submodule : submodules) {
                    // First fix the URL to the submodule inside the super-project
                    String sUrl = pathJoin( origin.getPath(), submodule.getFile() );
                    setSubmoduleUrl( submodule.getFile(), sUrl );

                    // Second, if the submodule already has been cloned, fix its own
                    // url...
                    String subGitDir = pathJoin( submodule.getFile(), ".git" );

                    /* it is possible that the submodule does not exist yet
                     * since we wait until after checkout to do 'submodule
                     * udpate' */
                    if ( hasGitRepo( subGitDir ) ) {
                        if (! "".equals( getRemoteUrl("origin", subGitDir) )) {
                            setRemoteUrl("origin", sUrl, subGitDir);
                        }
                    }
                }
            } catch (GitException e) {
                // this can fail for example HEAD doesn't exist yet
            }
        } else {
            // we've made a reasonable attempt to detect whether the origin is
            // non-bare, so we'll just assume it is bare from here on out and
            // thus the URLs are correct as given by (which is default behavior)
            //    git config --get submodule.NAME.url
        }
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Deprecated
    public void fetch(String repository, String refspec) throws GitException, InterruptedException {
        fetch(repository, new RefSpec(refspec));
    }

    /** {@inheritDoc} */
    @Deprecated
    public void fetch(RemoteConfig remoteRepository) throws InterruptedException {
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
    public void fetch() throws GitException, InterruptedException {
        fetch(null, (RefSpec) null);
    }

    /**
     * reset.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Deprecated
    public void reset() throws GitException, InterruptedException {
        reset(false);
    }


    /** {@inheritDoc} */
    @Deprecated
    public void push(URIish url, String refspec) throws GitException, InterruptedException {
        push().ref(refspec).to(url).execute();
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Deprecated
    public void clone(RemoteConfig source) throws GitException, InterruptedException {
        clone(source, false);
    }

    /** {@inheritDoc} */
    @Deprecated
    public void clone(RemoteConfig rc, boolean useShallowClone) throws GitException, InterruptedException {
        // Assume only 1 URL for this repository
        final String source = rc.getURIs().get(0).toPrivateString();
        clone(source, rc.getName(), useShallowClone, null);
    }

    /** {@inheritDoc} */
    @Deprecated
    public List<ObjectId> revListBranch(String branchId) throws GitException, InterruptedException {
        return revList(branchId);
    }

    /** {@inheritDoc} */
    @Deprecated
    public List<String> showRevision(Revision r) throws GitException, InterruptedException {
        return showRevision(null, r.getSha1());
    }

    /** {@inheritDoc} */
    @Deprecated
    public List<Tag> getTagsOnCommit(String revName) throws GitException, IOException {
        final Repository db = getRepository();
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

    /** {@inheritDoc} */
    public final List<IndexEntry> lsTree(String treeIsh) throws GitException, InterruptedException {
        return lsTree(treeIsh,false);
    }

    /** {@inheritDoc} */
    @Override
    protected Object writeReplace() {
        return remoteProxyFor(Channel.current().export(IGitAPI.class, this));
    }

    /**
     * hasGitModules.
     *
     * @return true if this repositor has one or more submodules
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
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

    /** {@inheritDoc} */
    public List<String> showRevision(ObjectId r) throws GitException, InterruptedException {
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
