package org.jenkinsci.plugins.gitclient;

import static java.util.Arrays.copyOfRange;
import org.apache.commons.codec.digest.DigestUtils;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

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

    /** For referenced directory check if it is a full or bare git repo
     * and return the File object for its "objects" sub-directory.
     * If there is nothing to find, or inputs are bad, returns null.
     * The idea is that checking for null allows to rule out non-git
     * paths, while a not-null return value is instantly usable by
     * some code which plays with git under its hood.
     */
    public File getObjectPath(String reference) {
        if (reference == null || reference.isEmpty()) {
            return null;
        }
        return getObjectPath(new File(reference));
    }

    public File getObjectPath(File referencePath) {
        if (referencePath == null) {
            return referencePath;
        }

        if (!referencePath.exists())
            return null;

        if (!referencePath.isDirectory())
            return null;

        // reference path can either be a normal or a base repository
        File objectsPath = new File(referencePath, ".git/objects");
        if (objectsPath == null) {
            return objectsPath; // Some Java error, could not make object from the paths involved
        }

        if (!objectsPath.isDirectory()) {
            // reference path is bare repo
            objectsPath = new File(referencePath, "objects");
            if (objectsPath == null) {
                return objectsPath; // Some Java error, could not make object from the paths involved
            }
        }

        if (!objectsPath.isDirectory())
            return null;

        // If we get here, we have a non-null File referencing a
        // "(.git/)objects" subdir under original referencePath
        return objectsPath;
    }

    /** Handle magic strings in the reference pathname to sort out patterns
     * classified as evaluated by parametrization, as handled below
     *
     * @params
     * reference    Pathname (maybe with magic suffix) to reference repo
     */
    public Boolean isParameterizedReferenceRepository(File reference) {
        if (reference == null) {
            return false;
        }
        return isParameterizedReferenceRepository(reference.getPath());
    }

    public Boolean isParameterizedReferenceRepository(String reference) {
        if (reference == null || reference.isEmpty()) {
            return false;
        }

        if (reference.endsWith("/${GIT_URL}")) {
            return true;
        }

        if (reference.endsWith("/${GIT_URL_SHA256}")) {
            return true;
        }

        if (reference.endsWith("/${GIT_URL_FALLBACK}")) {
            return true;
        }

        if (reference.endsWith("/${GIT_URL_SHA256_FALLBACK}")) {
            return true;
        }

        if (reference.endsWith("/${GIT_SUBMODULES}")) {
            return true;
        }

        if (reference.endsWith("/${GIT_SUBMODULES_FALLBACK}")) {
            return true;
        }

        return false;
    }

    /** There are many ways to spell an URL to the same repository even if
     * using the same access protocol. This routine converts the "url" string
     * in a way that helps us confirm whether two spellings mean same thing.
     */
    public String normalizeGitUrl(String url, Boolean checkLocalPaths) {
        String urlNormalized = url.replaceAll("/*$", "").replaceAll(".git$", "").toLowerCase();
        if (!url.contains("://")) {
            if (!url.startsWith("/") &&
                !url.startsWith(".")
            ) {
                // Not an URL with schema, not an absolute or relative pathname
                if (checkLocalPaths) {
                    File urlPath = new File(url);
                    if (urlPath.exists()) {
                        urlNormalized = "file://" + Paths.get( Paths.get("").toAbsolutePath().toString() + "/" + urlNormalized ).normalize().toString();;
                    } else {
                        // Also not a subdirectory of current dir without "./" prefix...
                        urlNormalized = "ssh://" + urlNormalized;
                    }
                } else {
                    // Assume it is not a path
                    urlNormalized = "ssh://" + urlNormalized;
                }
            } else {
                // Looks like a local path
                if (url.startsWith("/")) {
                    urlNormalized = "file://" + urlNormalized;
                } else {
                    urlNormalized = "file://" + Paths.get( Paths.get("").toAbsolutePath().toString() + "/" + urlNormalized ).normalize().toString();;
                }
            }
        }

        return urlNormalized;
    }

    /** Yield the File object for the reference repository local filesystem
     * pathname. Note that the provided string may be suffixed with expandable
     * tokens which allow to store a filesystem structure of multiple small
     * reference repositories instead of a big combined repository, while
     * providing a single inheritable configuration string value. Callers
     * can check whether the original path was used or mangled into another
     * by comparing their "reference" with returned object's File.getName().
     *
     * At some point this plugin might also maintain that filesystem structure.
     *
     * @params
     * reference    Pathname (maybe with magic suffix) to reference repo
     * url          URL of the repository being cloned, to help choose a
     *              suitable parameterized reference repo subdirectory.
     */
    public File findParameterizedReferenceRepository(File reference, String url) {
        if (reference == null) {
            return reference;
        }
        return findParameterizedReferenceRepository(reference.getPath(), url);
    }

    public File findParameterizedReferenceRepository(String reference, String url) {
        if (reference == null || reference.isEmpty()) {
            return null;
        }

        File referencePath = new File(reference);
        if (!referencePath.exists() &&
            isParameterizedReferenceRepository(reference) &&
            url != null && !url.isEmpty()
        ) {
            // Note: this normalization might crush several URLs into one,
            // and as far as is known this would be the goal - people tend
            // to use or omit .git suffix, or spell with varied case, while
            // it means the same thing to known Git platforms.
            // The actual reference repository directory may choose to have
            // original URL strings added as remotes (in case some are case
            // sensitive and different).
            String urlNormalized = normalizeGitUrl(url, true);

            System.err.println("reference='" + reference + "'\n" +
                "url='" + url + "'\n" +
                "urlNormalized='" + urlNormalized + "'\n");

            String referenceExpanded = null;
            if (reference.endsWith("/${GIT_URL}")) {
                // For mass-configured jobs, like Organization Folders,
                // allow to support parameterized paths to many refrepos.
                // End-users can set up webs of symlinks to same repos
                // known by different URLs (and/or including their forks
                // also cached in same index). Luckily all URL chars are
                // valid parts of path name... in Unix... Maybe parse or
                // escape chars for URLs=>paths with Windows in mind?
                // https://docs.microsoft.com/en-us/windows/win32/fileio/naming-a-file#naming-conventions
                // Further ideas: beside "GIT_URL" other meta variables
                // can be introduced, e.g. to escape non-ascii chars for
                // portability? Support base64, SHA or MD5 hashes of URLs
                // as pathnames? Normalize first (lowercase, .git, ...)?

                // TODO: employ git submodules - there we can reliably match
                // remote URLs (easily multiple) to particular modules, and
                // yet keep separate git index directories per module with
                // smaller scopes - much quicker to check out from than one
                // huge combined repo. It would also be much more native to
                // tools and custom scriptware that can be involved.

                // TODO: Config option whether to populate absent reference
                // repos (If the expanded path does not have git repo data
                // right now, populate it in the location expanded below)
                // or update existing ones before pulling commits, and how
                // to achieve that. Note that this is done by caller with
                // their implementation of git, or in case of a build farm it
                // is more likely to be a shared path only readable to Jenkins
                // and its agents, so write-operations would be done by helper
                // scripts that log into the shared storage server to populate
                // or update reference repositories. Note that users may also
                // want to run their own scripts to "populate" reference repos
                // as symlinks to existing other repos, to support combined
                // repo setup for different URLs pointing to same upstream,
                // or storing multiple closely related forks together.

                referenceExpanded = reference.replaceAll("\\$\\{GIT_URL\\}$", urlNormalized);
            } else if (reference.endsWith("/${GIT_URL_SHA256}")) {
                // This may be the more portable solution with regard to filesystems
                referenceExpanded = reference.replaceAll("\\$\\{GIT_URL_SHA256\\}$",
                    org.apache.commons.codec.digest.DigestUtils.sha256Hex(urlNormalized));
            } else if (reference.endsWith("/${GIT_URL_SHA256_FALLBACK}")) {
                // The safest option - fall back to parent directory if
                // the expanded one does not have git repo data right now:
                // it allows to gradually convert a big combined reference
                // repository into smaller chunks without breaking builds.
                referenceExpanded = reference.replaceAll("\\$\\{GIT_URL_SHA256_FALLBACK\\}$",
                    org.apache.commons.codec.digest.DigestUtils.sha256Hex(urlNormalized));
                if (getObjectPath(referenceExpanded) == null) {
                    // chop it off, use main directory
                    referenceExpanded = reference.replaceAll("/\\$\\{GIT_URL_SHA256_FALLBACK\\}$", "");
                }
            } else if (reference.endsWith("/${GIT_URL_FALLBACK}")) {
                referenceExpanded = reference.replaceAll("\\$\\{GIT_URL_FALLBACK\\}$", urlNormalized);
                if (getObjectPath(referenceExpanded) == null) {
                    // chop it off, use main directory
                    referenceExpanded = reference.replaceAll("/\\$\\{GIT_URL_FALLBACK\\}$", "");
                }
            } else if (reference.endsWith("/${GIT_SUBMODULES}") || reference.endsWith("/${GIT_SUBMODULES_FALLBACK}") ) {
                // Assuming the provided "reference" directory already hosts
                // submodules, we use git tools to find the one subdir which
                // has a registered remote URL equivalent (per normalization)
                // to the provided "url". If there is no hit, the non-fallback
                // mode suggests a new directory name to host the submodule
                // (via same rules as for SHA256), and the fallback mode would
                // return the main directory.
            }

            if (referenceExpanded != null) {
                reference = referenceExpanded;
                referencePath = null; // GC
                referencePath = new File(reference);
            }
            System.err.println("reference after='" + reference + "'\n");
        }

        if (!referencePath.exists()) {
            // Normalize the URLs with or without .git suffix to
            // be served by same dir with the refrepo contents
            reference += ".git";
            referencePath = null; // GC
            referencePath = new File(reference);
        }

        // Note that the referenced path may exist or not exist, in the
        // latter case it is up to the caller to decide on course of action.
        // Maybe create this dir to begin a reference repo (given the options)?
        return referencePath;
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
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification = "Java 11 spotbugs error")
    public List<Tag> getTagsOnCommit(String revName) throws GitException, IOException {
        try (Repository db = getRepository()) {
            final ObjectId commit = db.resolve(revName);
            final List<Tag> ret = new ArrayList<>();

            for (final Map.Entry<String, Ref> tag : db.getTags().entrySet()) {
                Ref value = tag.getValue();
                if (value != null) {
                    final ObjectId tagId = value.getObjectId();
                    if (commit != null && commit.equals(tagId))
                        ret.add(new Tag(tag.getKey(), tagId));
                }
            }
            return ret;
        }
    }

    /** {@inheritDoc} */
    public final List<IndexEntry> lsTree(String treeIsh) throws GitException, InterruptedException {
        return lsTree(treeIsh,false);
    }

    /** {@inheritDoc} */
    @Override
    protected Object writeReplace() throws java.io.ObjectStreamException {
        Channel currentChannel = Channel.current();
        if (currentChannel == null)
            throw new java.io.WriteAbortedException("No current channel", new java.lang.NullPointerException());
        return remoteProxyFor(currentChannel.export(IGitAPI.class, this));
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
     * <table>
     * <caption>Branch Spec Normalization Examples</caption>
     * <tr><th>branch spec</th><th>normalized</th></tr>
     * <tr><td><code>master</code></td><td><code>master*</code></td></tr>
     * <tr><td><code>feature1</code></td><td><code>feature1*</code></td></tr>
     * <tr><td><code>feature1/master</code></td><td><div style="color:red">master <code>feature1/master</code>*</div></td></tr>
     * <tr><td><code>origin/master</code></td><td><code>master*</code></td></tr>
     * <tr><td><code>repo2/feature1</code></td><td><code>feature1*</code></td></tr>
     * <tr><td><code>refs/heads/feature1</code></td><td><code>refs/heads/feature1</code></td></tr>
     * <tr><td valign="top">origin/namespaceA/fix15</td>
     *     <td><div style="color:red">fix15 <code>namespaceA/fix15</code>*</div></td><td></td></tr>
     * <tr><td><code>refs/heads/namespaceA/fix15</code></td><td><code>refs/heads/namespaceA/fix15</code></td></tr>
     * <tr><td><code>remotes/origin/namespaceA/fix15</code></td><td><code>refs/heads/namespaceA/fix15</code></td></tr>
     * </table><br>
     * *) TODO: Normalize to "refs/heads/"
     *
     * @param branchSpec a {@link java.lang.String} object.
     * @return normalized branch name
     */
    protected String extractBranchNameFromBranchSpec(String branchSpec) {
        String branch;
        String[] branchExploded = branchSpec.split("/");
        if (branchSpec.startsWith("remotes/")) {
            branch = "refs/heads/" + join(copyOfRange(branchExploded, 2, branchExploded.length), "/");
        } else if (branchSpec.startsWith("refs/remotes/")) {
            branch = "refs/heads/" + join(copyOfRange(branchExploded, 3, branchExploded.length), "/");
        } else if (branchSpec.startsWith("refs/heads/")) {
            branch = branchSpec;
        } else if (branchSpec.startsWith("refs/tags/")) {
            // Tags are allowed because git plugin 2.0.1
            // DefaultBuildChooser.getCandidateRevisions() allowed them.
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
