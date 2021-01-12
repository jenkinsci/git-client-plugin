package org.jenkinsci.plugins.gitclient;

import static java.util.Arrays.copyOfRange;
import org.apache.commons.codec.digest.DigestUtils;
import static org.apache.commons.lang.StringUtils.join;
import hudson.FilePath;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(LegacyCompatibleGitAPIImpl.class.getName());

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
    public File getObjectsFile(String reference) {
        if (reference == null || reference.isEmpty()) {
            return null;
        }
        return getObjectsFile(new File(reference));
    }

    public File getObjectsFile(File reference) {
        if (reference == null) {
            return reference;
        }

        if (!reference.exists())
            return null;

        if (!reference.isDirectory())
            return null;

        // reference pathname can either point to a normal or a base repository
        File objects = new File(reference, ".git/objects");
        if (objects == null) {
            return objects; // Some Java error, could not make object from the paths involved
        }

        if (!objects.isDirectory()) {
            // reference path is bare repo
            objects = new File(reference, "objects");
            if (objects == null) {
                return objects; // Some Java error, could not make object from the paths involved
            }
        }

        if (!objects.isDirectory())
            return null;

        // If we get here, we have a non-null File referencing a
        // "(.git/)objects" subdir under original referencePath
        return objects;
    }

    /** Handle magic strings in the reference pathname to sort out patterns
     * classified as evaluated by parametrization, as handled below
     *
     * @param reference    Pathname (maybe with magic suffix) to reference repo
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

        if (reference.endsWith("/${GIT_URL_FALLBACK}")) {
            return true;
        }

        if (reference.endsWith("/${GIT_URL_SHA256}")) {
            return true;
        }

        if (reference.endsWith("/${GIT_URL_SHA256_FALLBACK}")) {
            return true;
        }

        if (reference.endsWith("/${GIT_URL_BASENAME}")) {
            return true;
        }

        if (reference.endsWith("/${GIT_URL_BASENAME_FALLBACK}")) {
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
    public static String normalizeGitUrl(String url, Boolean checkLocalPaths) {
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

        LOGGER.log(Level.FINEST, "normalizeGitUrl('" + url + "', " + checkLocalPaths.toString() + ") => " + urlNormalized);
        return urlNormalized;
    }

    /** Find referenced URLs in this repo and its submodules, recursively.
     * Current primary use is for parameterized refrepo/${GIT_SUBMODULES}
     *
     * @return a Set of (unique) String arrays, representing:
     * [0] directory of nested submodule (relative to current workspace root)
     * The current workspace would be listed as directory "" and consumers
     * should check these entries last if they care for most-specific hits
     * with smaller-scope reference repositories.
     * [1] url as returned by getRemoteUrls() - fetch URLs, maybe several
     *                 entries per remote
     * [2] urlNormalized from normalizeGitUrl(url, true) (local pathnames
     *                 fully qualified)
     * [3] remoteName as defined in that nested submodule
     *
     * @param referenceBaseDir - the reference repository, or container thereof
     * @param needle - an URL which (or its normalized variant coming from
     *                 normalizeGitUrl(url, true)) we want to find:
     *                 if it is not null - then stop and return just hits
     *                 for it as soon as we have something.
     */
    public LinkedHashSet<String[]> getSubmodulesUrls(String referenceBaseDir, String needle) {
        // Keep track of where we've already looked in the "result" Set, to
        // avoid looking in same places (different strategies below) twice.
        // And eventually return this Set or part of it as the answer.
        LinkedHashSet<String[]> result = new LinkedHashSet<>(); // Retain order of insertion
        File f = null;
        // Helper list storage in loops below
        ArrayList<String> arrDirnames = new ArrayList<String>();
        String referenceBaseDirAbs = Paths.get(referenceBaseDir).toAbsolutePath().toString();

        // "this" during a checkout typically represents the job workspace,
        // but we want to inspect the reference repository located elsewhere
        // with the same implementation as the end-user set up (CliGit/jGit)
        GitClient referenceGit = this.newGit(referenceBaseDirAbs);

        Boolean isBare = false;
        try {
            isBare = ((hudson.plugins.git.IGitAPI)referenceGit).isBareRepository();
        } catch (InterruptedException e) {
            isBare = false; // At least try to look into submodules...
        }

        // If needle is not null, first look perhaps in the subdir(s) named
        // with base-name of the URL with and without a ".git" suffix, then
        // in SHA256 named dir that can match it; note that this use-case
        // might pan out also if "this" repo is bare and can not have "proper"
        // git submodules - but was prepared for our other options.
        if (needle != null && !needle.isEmpty()) {
            int sep = needle.lastIndexOf("/");
            String needleBasename;
            if (sep < 0) {
                needleBasename = needle;
            } else {
                needleBasename = needle.substring(sep + 1);
            }
            needleBasename = needleBasename.replaceAll(".[Gg][Ii][Tt]$", "");

            String needleNorm = normalizeGitUrl(needle, true);
            sep = needleNorm.lastIndexOf("/");
            String needleNormBasename;
            if (sep < 0) {
                needleNormBasename = needleNorm;
            } else {
                needleNormBasename = needleNorm.substring(sep + 1);
            }
            needleNormBasename = needleNormBasename.replaceAll(".git$", "");

            // Try with the basename without .git extension, and then with one.
            // First we try the caller-provided string casing, then normalized.
            arrDirnames.add(needleBasename);
            arrDirnames.add(needleBasename + ".git");
            String needleBasenameLC = needleBasename.toLowerCase();
            if (!needleBasenameLC.equals(needleBasename)) {
                // Retry with lowercased dirname
                arrDirnames.add(needleBasenameLC);
                arrDirnames.add(needleBasenameLC + ".git");
            }
            if (!needleNormBasename.equals(needleBasenameLC)) {
                arrDirnames.add(needleNormBasename);
                arrDirnames.add(needleNormBasename + ".git");
            }

            String needleSha = org.apache.commons.codec.digest.DigestUtils.sha256Hex(needleNorm);
            arrDirnames.add(needleSha);
            arrDirnames.add(needleSha + ".git");

            LOGGER.log(Level.FINE, "getSubmodulesUrls(): looking at basename-like subdirs under refrepo '" + referenceBaseDirAbs + "' per arrDirnames: " + arrDirnames.toString());

            for (String dirname : arrDirnames) {
                f = new File(referenceBaseDirAbs, dirname);
                LOGGER.log(Level.FINEST, "getSubmodulesUrls(): probing dir '" + dirname + "' => abs pathname '" + f.getAbsolutePath().toString() + "'");
                if (f.exists() && f.isDirectory()) {
                    try {
                        String fAbs = f.getAbsolutePath().toString();
                        //LegacyCompatibleGitAPIImpl?
                        LOGGER.log(Level.FINE, "getSubmodulesUrls(): looking for submodule URL needle='" + needle + "' in existing refrepo subdir '" + dirname + "' => '" + fAbs + "'");
                        GitClient g = referenceGit.subGit(dirname);
                        LOGGER.log(Level.FINE, "getSubmodulesUrls(): checking git workspace in dir '" + g.getWorkTree().absolutize().toString() + "'");
                        Map <String, String> uriNames = g.getRemoteUrls();
                        for (Map.Entry<String, String> pair : uriNames.entrySet()) {
                            String uri = pair.getKey();
                            String uriNorm = normalizeGitUrl(uri, true);
                            LOGGER.log(Level.FINE, "getSubmodulesUrls(): checking uri='" + uri + "' (uriNorm='" + uriNorm + "')");
                            if (needleNorm.equals(uriNorm) || needle.equals(uri)) {
                                result = new LinkedHashSet<>();
                                result.add(new String[]{fAbs, uri, uriNorm, pair.getValue()});
                                return result;
                            }
                            // Cache the finding to avoid the dirname later, if we
                            // get to that; but no checks are needed in this loop
                            // which by construct looks at different dirs so far.
                            result.add(new String[]{fAbs, uri, uriNorm, pair.getValue()});
                        }
                    } catch (Exception e) {
                        // ignore, go to next slide
                    }
                }
            }

            // If current repo *is* bare (can't have proper submodules) and the
            // needle is not null, follow up with:
            // * Maybe also direct child dirs that have a ".git" FS object inside?..
            // * Look at remote URLs in current dir after the guessed subdirs failed,
            //   and return then.
            // TODO: Remove "|| true" when other logic fo look for real submodules is complete
            if (isBare || true) {
                arrDirnames.clear();

                // Check subdirs that are git workspaces
                // TODO: Refactor to avoid lookups of dirs that may prove not
                // needed in the end (aim for less I/Os to find the goal)
                LinkedHashSet<String> checkedDirs = new LinkedHashSet<>();
                for (String[] resultEntry : result) {
                    checkedDirs.add(resultEntry[0]);
                }
                File[] directories = new File(referenceBaseDirAbs).listFiles(File::isDirectory);
                for (File dir : directories) {
                    f = new File(dir, ".git");
                    if (f.exists()) { // May be a file or directory... or symlink to those...
                        String dirname = dir.getPath().replaceAll("/*$", "");
                        if (!checkedDirs.contains(dirname)) {
                            arrDirnames.add(dirname);
                        }
                    }
                }

                // Finally check pattern's parent dir
                arrDirnames.add(".");

                LOGGER.log(Level.FINE, "getSubmodulesUrls(): looking at all subdirs (bare repo) under refrepo '" + referenceBaseDirAbs + "' per arrDirnames: " + arrDirnames.toString());

                for (String dirname : arrDirnames) {
                    f = new File(referenceBaseDirAbs, dirname);
                    LOGGER.log(Level.FINEST, "getSubmodulesUrls(): probing dir '" + dirname + "' => abs pathname '" + f.getAbsolutePath().toString() + "'");
                    if (f.exists() && f.isDirectory()) {
                        try {
                            String fAbs = f.getAbsolutePath().toString();
                            //LegacyCompatibleGitAPIImpl?
                            LOGGER.log(Level.FINE, "getSubmodulesUrls(): looking for submodule URL needle='" + needle + "' in existing refrepo subdir '" + dirname + "' => '" + fAbs + "'");
                            GitClient g = referenceGit.subGit(dirname);
                            LOGGER.log(Level.FINE, "getSubmodulesUrls(): checking git workspace in dir '" + g.getWorkTree().absolutize().toString() + "'");
                            Map <String, String> uriNames = g.getRemoteUrls();
                            for (Map.Entry<String, String> pair : uriNames.entrySet()) {
                                String uri = pair.getKey();
                                String uriNorm = normalizeGitUrl(uri, true);
                                LOGGER.log(Level.FINE, "getSubmodulesUrls(): checking uri='" + uri + "' (uriNorm='" + uriNorm + "')");
                                if (needleNorm.equals(uriNorm) || needle.equals(uri)) {
                                    result = new LinkedHashSet<>();
                                    result.add(new String[]{fAbs, uri, uriNorm, pair.getValue()});
                                    return result;
                                }
                                // Cache the finding to avoid the dirname later, if we
                                // get to that; but no checks are needed in this loop
                                // which by construct looks at different dirs so far.
                                result.add(new String[]{fAbs, uri, uriNorm, pair.getValue()});
                            }
                        } catch (Exception e) {
                            // ignore, go to next slide
                        }
                    }
                }

                // Nothing found, and for bare top-level repo not much to search for
                // Or maybe also fall through to let git decide?
                if (isBare) {
                    return new LinkedHashSet<>();
                }
            }
        } else {
            // ...and if there is no needle? Return info on all git remotes
            // found under this directory...
            /* if (isBare) { ... } */
            // fall through currently, let git decide if it has submodules here and now
        }

        // For each current workspace (recurse or big loop in same context?):
        // public GitClient subGit(String subdir) => would this.subGit(...)
        //   give us a copy of this applied class instance (CLI Git vs jGit)?
        // get submodule name-vs-one-url from .gitmodules if present, for a
        //   faster possible answer (only bother if needle is not null?)
        // try { getSubmodules("HEAD") ... } => List<IndexEntry> filtered for
        //  "commit" items
        // getRemoteUrls() => Map <url, remoteName>
        arrDirnames.clear();

        // TODO: Check subdirs that are git workspaces, and remove "|| true" above
        LinkedHashSet<String> checkedDirs = new LinkedHashSet<>();
        for (String[] resultEntry : result) {
            checkedDirs.add(resultEntry[0]);
        }

        // If current dir does have submodules, first dig into submodules,
        // when there is no deeper to drill, report remote URLs and step
        // back from recursion. This way we have least specific repo last,
        // if several have the replica (assuming the first hits are smaller
        // scopes).
        return result;
    }

    /** See above. With null, returns all we can find (slower) for the caller
     * to parse */
    public LinkedHashSet<String[]> getSubmodulesUrls(String referenceBaseDir) {
        return getSubmodulesUrls(referenceBaseDir, null);
    }
    /* Do we need a parameter-less variant to look under current work dir
     * aka Paths.get("").toAbsolutePath().toString() ?.. */

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
     * @param reference    Pathname (maybe with magic suffix) to reference repo
     * @param url          URL of the repository being cloned, to help choose a
     *                     suitable parameterized reference repo subdirectory.
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
            // it means the same thing to known Git platforms, except local
            // dirs on case-sensitive filesystems.
            // The actual reference repository directory may choose to have
            // original URL strings added as remotes (in case some are case
            // sensitive and different).
            String urlNormalized = normalizeGitUrl(url, true);

            // Drop the trailing keyword to know the root refrepo dirname
            String referenceBaseDir = reference.replaceAll("/\\$\\{GIT_[^\\}]*\\}$", "");

            // Note: currently unit-tests expect this markup on stderr:
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
            } else if (reference.endsWith("/${GIT_URL_FALLBACK}")) {
                referenceExpanded = reference.replaceAll("\\$\\{GIT_URL_FALLBACK\\}$", urlNormalized);
                if (getObjectsFile(referenceExpanded) == null && getObjectsFile(referenceExpanded + ".git") == null) {
                    // chop it off, use main directory
                    referenceExpanded = referenceBaseDir;
                }
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
                if (getObjectsFile(referenceExpanded) == null && getObjectsFile(referenceExpanded + ".git") == null) {
                    // chop it off, use main directory
                    referenceExpanded = referenceBaseDir;
                }
            } else if (reference.endsWith("/${GIT_URL_BASENAME}") || reference.endsWith("/${GIT_URL_BASENAME_FALLBACK}")) {
                // This may be the more portable solution with regard to filesystems
                // First try with original user-provided casing of the URL (if local
                // dirs were cloned manually)
                int sep = url.lastIndexOf("/");
                String needleBasename;
                if (sep < 0) {
                    needleBasename = url;
                } else {
                    needleBasename = url.substring(sep + 1);
                }
                needleBasename = needleBasename.replaceAll(".git$", "");

                if (reference.endsWith("/${GIT_URL_BASENAME}")) {
                    referenceExpanded = reference.replaceAll("\\$\\{GIT_URL_BASENAME\\}$",
                        needleBasename);
                } else { // if (reference.endsWith("/${GIT_URL_BASENAME_FALLBACK}")) {
                    referenceExpanded = reference.replaceAll("\\$\\{GIT_URL_BASENAME_FALLBACK\\}$",
                        needleBasename);
                    if (url.equals(urlNormalized) && getObjectsFile(referenceExpanded) == null && getObjectsFile(referenceExpanded + ".git") == null) {
                        // chop it off, use main directory (only if we do not check urlNormalized separately below)
                        referenceExpanded = referenceBaseDir;
                    }
                }

                if (!url.equals(urlNormalized) && getObjectsFile(referenceExpanded) == null && getObjectsFile(referenceExpanded + ".git") == null) {
                    // Retry with automation-ready normalized URL
                    sep = urlNormalized.lastIndexOf("/");
                    if (sep < 0) {
                        needleBasename = urlNormalized;
                    } else {
                        needleBasename = urlNormalized.substring(sep + 1);
                    }
                    needleBasename = needleBasename.replaceAll(".git$", "");

                    if (reference.endsWith("/${GIT_URL_BASENAME}")) {
                        referenceExpanded = reference.replaceAll("\\$\\{GIT_URL_BASENAME\\}$",
                            needleBasename);
                    } else { // if (reference.endsWith("/${GIT_URL_BASENAME_FALLBACK}")) {
                        referenceExpanded = reference.replaceAll("\\$\\{GIT_URL_BASENAME_FALLBACK\\}$",
                            needleBasename);
                        if (getObjectsFile(referenceExpanded) == null && getObjectsFile(referenceExpanded + ".git") == null) {
                            // chop it off, use main directory
                            referenceExpanded = referenceBaseDir;
                        }
                    }
                }
            } else if (reference.endsWith("/${GIT_SUBMODULES}") || reference.endsWith("/${GIT_SUBMODULES_FALLBACK}") ) {
                // Here we employ git submodules - so we can reliably match
                // remote URLs (easily multiple) to particular modules, and
                // yet keep separate git index directories per module with
                // smaller scopes - much quicker to check out from than one
                // huge combined repo. It would also be much more native to
                // tools and custom scriptware that can be involved.

                // Assuming the provided "reference" directory already hosts
                // submodules, we use git tools to find the one subdir which
                // has a registered remote URL equivalent (per normalization)
                // to the provided "url".

                // Note: we pass "url" here, the routine differentiates original
                // naming vs. normalization while looking for its needle in the
                // haystack.
                LinkedHashSet<String[]> subEntries = getSubmodulesUrls(referenceBaseDir, url);
                if (!subEntries.isEmpty()) {
                    // Normally we should only have one entry here, as sorted
                    // by the routine, and prefer that first option if a new
                    // reference repo would have to be made (and none exists).
                    // If several entries are present after all, iterate until
                    // first existing hit and return the first entry otherwise.
                    for (String[] subEntry : subEntries) {
                        if (getObjectsFile(subEntry[0]) != null || getObjectsFile(subEntry[0] + ".git") != null) {
                            referenceExpanded = subEntry[0];
                            break;
                        }
                    }
                    if (referenceExpanded == null) {
                        referenceExpanded = subEntries.iterator().next()[0];
                    }
                    LOGGER.log(Level.FINE, "findParameterizedReferenceRepository(): got referenceExpanded='" + referenceExpanded + "' from subEntries");
                    if (reference.endsWith("/${GIT_SUBMODULES_FALLBACK}") && getObjectsFile(referenceExpanded) == null && getObjectsFile(referenceExpanded + ".git") == null) {
                        // chop it off, use main directory
                        referenceExpanded = referenceBaseDir;
                    }
                } else {
                    LOGGER.log(Level.FINE, "findParameterizedReferenceRepository(): got no subEntries");
                    // If there is no hit, the non-fallback mode suggests a new
                    // directory name to host the submodule (same rules as SHA),
                    // and the fallback mode would return the main directory.
                    if (reference.endsWith("/${GIT_SUBMODULES}")) {
                        referenceExpanded = reference.replaceAll("\\$\\{GIT_SUBMODULES\\}$",
                            org.apache.commons.codec.digest.DigestUtils.sha256Hex(urlNormalized));
                    }
                    else { // if (reference.endsWith("/${GIT_SUBMODULES_FALLBACK}")) {
                        // chop it off, use main directory
                        referenceExpanded = referenceBaseDir;
                    }
                }
            }

            if (referenceExpanded != null) {
                reference = referenceExpanded;
                referencePath = null; // GC
                referencePath = new File(reference);
            }

            // Note: currently unit-tests expect this markup on stderr:
            System.err.println("reference after='" + reference + "'\n");
        }

        if (!referencePath.exists() && !reference.endsWith(".git")) {
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
