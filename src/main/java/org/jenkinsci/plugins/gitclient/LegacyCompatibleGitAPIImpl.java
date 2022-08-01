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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jenkinsci.plugins.gitclient.verifier.HostKeyVerifierFactory;

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
    private HostKeyVerifierFactory hostKeyFactory;

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
    @Deprecated
    protected LegacyCompatibleGitAPIImpl(File workspace) {
        this.workspace = workspace;
    }

    protected LegacyCompatibleGitAPIImpl(File workspace, HostKeyVerifierFactory hostKeyFactory) {
        this.workspace = workspace;
        this.hostKeyFactory = hostKeyFactory;
    }

    public HostKeyVerifierFactory getHostKeyFactory() {
        return hostKeyFactory;
    }

    public void setHostKeyFactory(HostKeyVerifierFactory verifier) {
        this.hostKeyFactory = verifier;
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
     * (Note that for submodules and other cases with externalized Git
     * metadata, the "objects" directory may be NOT under "reference").
     * If there is nothing to find, or inputs are bad, returns null.
     * The idea is that checking for null allows to rule out non-git
     * paths, while a not-null return value is instantly usable by
     * some code which plays with git under its hood.
     */
    public static File getObjectsFile(String reference) {
        if (reference == null || reference.isEmpty()) {
            return null;
        }
        return getObjectsFile(new File(reference));
    }

    public static File getObjectsFile(File reference) {
        // reference pathname can either point to a "normal" workspace
        // checkout or a bare repository

        if (reference == null) {
            return reference;
        }

        if (!reference.exists())
            return null;

        if (!reference.isDirectory())
            return null;

        File fGit = new File(reference, ".git"); // workspace - file, dir or symlink to those
        File objects = null;

        if (fGit.exists()) {
            if (fGit.isDirectory()) {
                objects = new File(fGit, "objects"); // this might not exist or not be a dir - checked below
/*
                if (objects == null) { // spotbugs dislikes this, since "new File()" should not return null
                    return objects; // Some Java error, could not make object from the paths involved
                }
*/
                LOGGER.log(Level.FINEST, "getObjectsFile(): found an fGit '" +
                    fGit.getAbsolutePath().toString() + "' which is a directory");
            } else {
                // If ".git" FS object exists and is a not-empty file (and
                // is not a dir), then its content may point to some other
                // filesystem location for the Git-internal data.
                // For example, a checked-out submodule workspace can point
                // to the index and other metadata stored in its "parent"
                // repository's directory:
                //   "gitdir: ../.git/modules/childRepoName"
                LOGGER.log(Level.FINEST, "getObjectsFile(): found an fGit '" +
                    fGit.getAbsolutePath().toString() + "' which is NOT a directory");
                BufferedReader reader = null;
                try {
                    String line;
                    reader = new BufferedReader(new InputStreamReader(new FileInputStream(fGit), "UTF-8"));
                    while ((line = reader.readLine()) != null)
                    {
                        String[] parts = line.split(":", 2);
                        if (parts.length >= 2)
                        {
                            String key = parts[0].trim();
                            String value = parts[1].trim();
                            if (key.equals("gitdir")) {
                                objects = new File(reference, value);
                                LOGGER.log(Level.FINE, "getObjectsFile(): while looking for 'gitdir:' in '" +
                                    fGit.getAbsolutePath().toString() + "', found reference to objects " +
                                    "which should be at: '" + objects.getAbsolutePath().toString() + "'");
                                // Note: we don't use getCanonicalPath() here to avoid further filesystem
                                // access and possible exceptions (the getAbsolutePath() is about string
                                // processing), but callers might benefit from canonicising and ensuring
                                // unique pathnames (for equality checks etc.) with relative components
                                // and symlinks resolved.
                                // On another hand, keeping the absolute paths, possibly, relative to a
                                // parent directory as prefix, allows callers to match/compare such parent
                                // prefixes for the contexts the callers would define for themselves.
                                break;
                            }
                            LOGGER.log(Level.FINEST, "getObjectsFile(): while looking for 'gitdir:' in '" +
                                fGit.getAbsolutePath().toString() + "', ignoring line: " + line);
                        }
                    }
                    if (objects == null) {
                        LOGGER.log(Level.WARNING, "getObjectsFile(): failed to parse '" + fGit.getAbsolutePath().toString() + "': did not contain a 'gitdir:' entry");
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "getObjectsFile(): failed to parse '" + fGit.getAbsolutePath().toString() + "': " + e.toString());
                    objects = null;
                }
                try {
                    if (reader != null) {
                        reader.close();
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "getObjectsFile(): failed to close file after parsing '" + fGit.getAbsolutePath().toString() + "': " + e.toString());
                }
            }
        } else {
            LOGGER.log(Level.FINEST, "getObjectsFile(): did not find any checked-out '" +
                fGit.getAbsolutePath().toString() + "'");
        }

        if (objects == null || !objects.isDirectory()) {
            // either reference path is bare repo (no ".git" inside),
            // or we have failed interpreting ".git" contents above
            objects = new File(reference, "objects"); // bare
/*
            if (objects == null) {
                return objects; // Some Java error, could not make object from the paths involved
            }
*/
            // This clause below is redundant for production, but useful for troubleshooting
            if (objects.exists()) {
                if (objects.isDirectory()) {
                    LOGGER.log(Level.FINEST, "getObjectsFile(): found a bare '" +
                        objects.getAbsolutePath().toString() + "' which is a directory");
                } else {
                    LOGGER.log(Level.FINEST, "getObjectsFile(): found a bare '" +
                        objects.getAbsolutePath().toString() + "' which is NOT a directory");
                }
            } else {
                LOGGER.log(Level.FINEST, "getObjectsFile(): did not find any bare '" +
                    objects.getAbsolutePath().toString() + "'");
            }
        }

        if (!objects.exists())
            return null;

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
    public static Boolean isParameterizedReferenceRepository(File reference) {
        if (reference == null) {
            return false;
        }
        return isParameterizedReferenceRepository(reference.getPath());
    }

    public static Boolean isParameterizedReferenceRepository(String reference) {
        if (reference == null || reference.isEmpty()) {
            return false;
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
    @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "Path operations below intentionally use absolute '/' in some cases"
        )
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
                        try {
                            // Check if the string in urlNormalized is a valid
                            // relative path (subdir) in current working directory
                            urlNormalized = "file://" + Paths.get( Paths.get("").toAbsolutePath().toString() + "/" + urlNormalized ).normalize().toString();
                        } catch (java.nio.file.InvalidPathException ipe1) {
                            // e.g. Illegal char <:> at index 30: C:\jenkins\git-client-plugin/c:\jenkins\git-client-plugin\target\clone
                            try {
                                // Re-check in another manner
                                urlNormalized = "file://" + Paths.get( Paths.get("", urlNormalized).toAbsolutePath().toString() ).normalize().toString();
                            } catch (java.nio.file.InvalidPathException ipe2) {
                                // Finally, fall back to checking the originally
                                // fully-qualified path
                                urlNormalized = "file://" + Paths.get( Paths.get("/", urlNormalized).toAbsolutePath().toString() ).normalize().toString();
                            }
                        }
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

    /** Find referenced URLs in this repo and its submodules (or other
     * subdirs with git repos), recursively. Current primary use is for
     * parameterized refrepo/${GIT_SUBMODULES} handling.
     *
     * @return an AbstractMap.SimpleEntry, containing a Boolean to denote
     * an exact match (or lack thereof) for the needle (if searched for),
     * and a Set of (unique) String arrays, representing:
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
     * If the returned SimpleEntry has the Boolean flag as False but also
     * a Set which is not empty, and a search for "needle" was requested,
     * then that Set lists some not-exact matches for existing sub-dirs
     * with repositories that seem likely to be close hits (e.g. remotes
     * there *probably* point to other URLs of same repo as the needle,
     * or its forks - so these directories are more likely than others to
     * contain the reference commits needed for the faster git checkouts).
     *
     * For a search with needle==null, the Boolean flag would be False too,
     * and the Set would just detail all found sub-repositories.
     *
     * @param referenceBaseDir - the reference repository, or container thereof
     * @param needle - an URL which (or its normalized variant coming from
     *                 normalizeGitUrl(url, true)) we want to find:
     *                 if it is not null - then stop and return just hits
     *                 for it as soon as we have something.
     * @param checkRemotesInReferenceBaseDir - if true (reasonable default for
     *                 external callers), the referenceBaseDir would be added
     *                 to the list of dirs for listing known remotes in search
     *                 for a needle match or for the big listing. Set to false
     *                 when recursing, since this directory was checked already
     *                 as part of parent directory inspection.
     */
    public SimpleEntry<Boolean, LinkedHashSet<String[]>> getSubmodulesUrls(String referenceBaseDir, String needle, Boolean checkRemotesInReferenceBaseDir) {
        // Keep track of where we've already looked in the "result" Set, to
        // avoid looking in same places (different strategies below) twice.
        // And eventually return this Set or part of it as the answer.
        LinkedHashSet<String[]> result = new LinkedHashSet<>(); // Retain order of insertion
        File f = null;
        // Helper list storage in loops below
        ArrayList<String> arrDirnames = new ArrayList<String>();

        // Note: an absolute path is not necessarily the canonical one
        // We want to hit same dirs only once, so canonicize paths below
        String referenceBaseDirAbs;
        try {
            referenceBaseDirAbs = new File(referenceBaseDir).getAbsoluteFile().getCanonicalPath().toString();
        } catch (IOException e) {
            // Access error while dereferencing some parent?..
            referenceBaseDirAbs = new File(referenceBaseDir).getAbsoluteFile().toString();
            LOGGER.log(Level.SEVERE, "getSubmodulesUrls(): failed to canonicize '" +
                referenceBaseDir + "' => '" + referenceBaseDirAbs + "': " + e.toString());
            //return new SimpleEntry<>(false, result);
        }

        // "this" during a checkout typically represents the job workspace,
        // but we want to inspect the reference repository located elsewhere
        // with the same implementation as the end-user set up (CliGit/jGit)
        GitClient referenceGit = this.newGit(referenceBaseDirAbs);

        Boolean isBare = false;
        try {
            isBare = ((hudson.plugins.git.IGitAPI)referenceGit).isBareRepository();
        } catch (InterruptedException | GitException e) {
            // Proposed base directory whose subdirs contain refrepos might
            // itself be not a repo. Shouldn't be per reference scripts, but...
            if (e.toString().contains("GIT_DISCOVERY_ACROSS_FILESYSTEM")) {
                // Note the message may be localized, envvar name should not be:
                // stderr: fatal: not a git repository (or any parent up to mount point /some/path)
                // Stopping at filesystem boundary (GIT_DISCOVERY_ACROSS_FILESYSTEM not set).
                // As far as the logic below is currently concerned, we do not
                // look for submodules directly in a bare repo.
                isBare = true;
            } else {
                // Some other error
                isBare = false; // At least try to look into submodules...
                isBare = false;
            }

            LOGGER.log(Level.SEVERE, "getSubmodulesUrls(): failed to determine " +
                "isBareRepository() in '" + referenceBaseDirAbs + "'; " +
                "assuming '" + isBare + "': " + e.toString());
        }

        // Simplify checks below by stating a useless needle is null
        if (needle != null && needle.isEmpty()) {
            needle = null;
        }
        // This is only used and populated if needle is not null
        String needleNorm = null;

        // This is only used and populated if needle is not null, and
        // can be used in the end to filter not-exact match suggestions
        String needleBasename = null;
        String needleBasenameLC = null;
        String needleNormBasename = null;
        String needleSha = null;

        // If needle is not null, first look perhaps in the subdir(s) named
        // with base-name of the URL with and without a ".git" suffix, then
        // in SHA256 named dir that can match it; note that this use-case
        // might pan out also if "this" repo is bare and can not have "proper"
        // git submodules - but was prepared for our other options.
        if (needle != null) {
            int sep = needle.lastIndexOf("/");
            if (sep < 0) {
                needleBasename = needle;
            } else {
                needleBasename = needle.substring(sep + 1);
            }
            needleBasename = needleBasename.replaceAll(".[Gg][Ii][Tt]$", "");

            needleNorm = normalizeGitUrl(needle, true);
            sep = needleNorm.lastIndexOf("/");
            if (sep < 0) {
                needleNormBasename = needleNorm;
            } else {
                needleNormBasename = needleNorm.substring(sep + 1);
            }
            needleNormBasename = needleNormBasename.replaceAll(".git$", "");

            // Try with the basename without .git extension, and then with one.
            // First we try the caller-provided string casing, then normalized.
            // Note that only after this first smaller pass which we hope to
            // succeed quickly, we engage in heavier (by I/O and computation)
            // investigation of submodules, and then similar loop against any
            // remaining direct subdirs that contain a ".git" (or "objects")
            // FS object.
            arrDirnames.add(referenceBaseDirAbs + "/" + needleBasename);
            arrDirnames.add(referenceBaseDirAbs + "/" + needleBasename + ".git");
            needleBasenameLC = needleBasename.toLowerCase();
            if (!needleBasenameLC.equals(needleBasename)) {
                // Retry with lowercased dirname
                arrDirnames.add(referenceBaseDirAbs + "/" + needleBasenameLC);
                arrDirnames.add(referenceBaseDirAbs + "/" + needleBasenameLC + ".git");
            }
            if (!needleNormBasename.equals(needleBasenameLC)) {
                arrDirnames.add(referenceBaseDirAbs + "/" + needleNormBasename);
                arrDirnames.add(referenceBaseDirAbs + "/" + needleNormBasename + ".git");
            }

            needleSha = org.apache.commons.codec.digest.DigestUtils.sha256Hex(needleNorm);
            arrDirnames.add(referenceBaseDirAbs + "/" + needleSha);
            arrDirnames.add(referenceBaseDirAbs + "/" + needleSha + ".git");

            LOGGER.log(Level.FINE, "getSubmodulesUrls(): looking at basename-like subdirs under base refrepo '" + referenceBaseDirAbs + "', per arrDirnames: " + arrDirnames.toString());

            for (String dirname : arrDirnames) {
                f = new File(dirname);
                LOGGER.log(Level.FINEST, "getSubmodulesUrls(): probing dir at abs pathname '" + dirname + "' if it exists");
                if (getObjectsFile(f) != null) {
                    try {
                        LOGGER.log(Level.FINE, "getSubmodulesUrls(): looking for submodule URL needle='" + needle + "' in existing refrepo subdir '" + dirname + "'");
                        GitClient g = referenceGit.subGit(dirname);
                        LOGGER.log(Level.FINE, "getSubmodulesUrls(): checking git workspace in dir '" + g.getWorkTree().absolutize().toString() + "'");
                        Map <String, String> uriNames = g.getRemoteUrls();
                        LOGGER.log(Level.FINEST, "getSubmodulesUrls(): sub-git getRemoteUrls() returned this Map uriNames: " + uriNames.toString());
                        for (Map.Entry<String, String> pair : uriNames.entrySet()) {
                            String remoteName = pair.getValue(); // whatever the git workspace config calls it
                            String uri = pair.getKey();
                            String uriNorm = normalizeGitUrl(uri, true);
                            LOGGER.log(Level.FINE, "getSubmodulesUrls(): checking uri='" + uri + "' (uriNorm='" + uriNorm + "') vs needle");
                            if (needleNorm.equals(uriNorm) || needle.equals(uri)) {
                                result = new LinkedHashSet<>();
                                result.add(new String[]{dirname, uri, uriNorm, remoteName});
                                return new SimpleEntry<>(true, result);
                            }
                            // Cache the finding to avoid the dirname later, if we
                            // get to that; but no checks are needed in this loop
                            // which by construct looks at different dirs so far.
                            result.add(new String[]{dirname, uri, uriNorm, remoteName});
                        }
                    } catch (Throwable t) {
                        // ignore, go to next slide
                        LOGGER.log(Level.FINE, "getSubmodulesUrls(): probing dir '" + dirname + "' resulted in an exception or error (will go to next item):\n" + t.toString());
                    }
                }
            }
        } // if needle, look in basename-like and SHA dirs first

        // Needle or not, the rest of directory walk to collect data is the
        // same, so follow a list of whom we want to visit in likely-quickest
        // hit order. Note that if needle is null, we walk over everything
        // that makes sense to visit, to return info on all git remotes found
        // in or under this directory; however if it is not-null, we still
        // try to have minimal overhead to complete as soon as we match it.
        // TODO: Refactor to avoid lookups of dirs that may prove not needed
        // in the end (aim for less I/Os to find the goal)... or is one dir
        // listing a small price to pay for maintaining one unified logic?

        // If current dir does have submodules, first dig into submodules,
        // when there is no deeper to drill, report remote URLs and step
        // back from recursion. This way we have least specific repo last,
        // if several have the replica (assuming the first hits are smaller
        // scopes).

        // Track where we have looked already; note that values in result[]
        // (if any from needle-search above) are absolute pathnames
        LinkedHashSet<String> checkedDirs = new LinkedHashSet<>();
        for (String[] resultEntry : result) {
            checkedDirs.add(resultEntry[0]);
        }

/*
// TBD: Needs a way to list submodules in given workspace and convert
// that into (relative) subdirs, possibly buried some levels deep, for
// cases where the submodule is defined in parent with the needle URL.
// Maybe merge with current "if isBare" below, to optionally seed
// same arrDirnames with different values and check remotes listed
// in those repos.
        // If current repo *is NOT* bare - check its submodules
        // (the .gitmodules => submodule.MODNAME.{url,path} mapping)
        // but this essentially does not look into any subdirectory.
        // But we can add at higher priority submodule path(s) whose
        // basename of the URL matches the needleBasename. And then
        // other submodule paths to inspect before arbitrary subdirs.
        if (!isBare) {
            try {
                // For each current workspace (recurse or big loop in same context?):
                // public GitClient subGit(String subdir) => would this.subGit(...)
                //   give us a copy of this applied class instance (CLI Git vs jGit)?
                // get submodule name-vs-one-url from .gitmodules if present, for a
                //   faster possible answer (only bother if needle is not null?)
                // try { getSubmodules("HEAD") ... } => List<IndexEntry> filtered for
                //  "commit" items
                // * if we are recursed into a "leaf" project and inspect ITS
                //   submodules, look at all git tips or even commits, to find
                //   and inspect all unique (by hash) .gitmodule objects, since
                //   over time or in different branches a "leaf" project could
                //   reference different subs?
                // getRemoteUrls() => Map <url, remoteName>
//                arrDirnames.clear();

                // TODO: Check subdirs that are git workspaces, and remove "|| true" above
//                LinkedHashSet<String> checkedDirs = new LinkedHashSet<>();
//                for (String[] resultEntry : result) {
//                    checkedDirs.add(resultEntry[0]);
//                }


                LOGGER.log(Level.FINE, "getSubmodulesUrls(): looking for submodule URL needle='" + needle + "' in submodules of refrepo, if any");
                Map <String, String> uriNames = referenceGit.getRemoteUrls();
                for (Map.Entry<String, String> pair : uriNames.entrySet()) {
                    String uri = pair.getKey();
                    String uriNorm = normalizeGitUrl(uri, true);
                    LOGGER.log(Level.FINE, "getSubmodulesUrls(): checking uri='" + uri + "' (uriNorm='" + uriNorm + "')");
                    LOGGER.log(Level.FINEST, "getSubmodulesUrls(): sub-git getRemoteUrls() returned this Map: " + uriNames.toString());
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
*/

        // If current repo *is* bare (can't have proper submodules), or if the
        // end-users just cloned or linked some more repos into this container,
        // follow up with direct child dirs that have a ".git" (or "objects")
        // FS object inside:

        // Check subdirs that are git workspaces; note that values in checkedDirs
        // are absolute pathnames. If we did look for the needle, array already
        // starts with some "prioritized" pathnames which we should not directly
        // inspect again... but should recurse into first anyhow.
        File[] directories = new File(referenceBaseDirAbs).listFiles(File::isDirectory);
        if (directories != null) {
            // listFiles() "...returns null if this abstract pathname
            // does not denote a directory, or if an I/O error occurs"
            for (File dir : directories) {
                if (getObjectsFile(dir) != null) {
                    String dirname = dir.getPath().replaceAll("/*$", "");
                    if (!checkedDirs.contains(dirname)) {
                        arrDirnames.add(dirname);
                    }
                }
            }
        }

        // Finally check pattern's parent dir
        // * Look at remote URLs in current dir after the guessed subdirs failed,
        //   and return then.
        if (checkRemotesInReferenceBaseDir) {
            if (getObjectsFile(referenceBaseDirAbs) != null) {
                arrDirnames.add(referenceBaseDirAbs);
            }
        }

        LOGGER.log(Level.FINE, "getSubmodulesUrls(): looking at " +
            ((isBare ? "" : "submodules first, then ")) +
            "all subdirs that have a .git, under refrepo '" + referenceBaseDirAbs +
            "' per absolute arrDirnames: " + arrDirnames.toString());

        for (String dirname : arrDirnames) {
            // Note that here dirnames deal in absolutes
            f = new File(dirname);
            LOGGER.log(Level.FINEST, "getSubmodulesUrls(): probing dir '" + dirname + "' if it exists");
            if (f.exists() && f.isDirectory()) {
                // No checks for ".git" or "objects" this time, already checked above
                // by getObjectsFile(). Probably should not check exists/dir either,
                // but better be on the safe side :)
                if (!checkedDirs.contains(dirname)) {
                    try {
                        LOGGER.log(Level.FINE, "getSubmodulesUrls(): looking " + ((needle == null) ? "" : "for submodule URL needle='" + needle + "' ") + "in existing refrepo dir '" + dirname + "'");
                        GitClient g = this.newGit(dirname);
                        LOGGER.log(Level.FINE, "getSubmodulesUrls(): checking git workspace in dir '" + g.getWorkTree().absolutize().toString() + "'");
                        Map <String, String> uriNames = g.getRemoteUrls();
                        LOGGER.log(Level.FINEST, "getSubmodulesUrls(): sub-git getRemoteUrls() returned this Map uriNames: " + uriNames.toString());
                        for (Map.Entry<String, String> pair : uriNames.entrySet()) {
                            String remoteName = pair.getValue(); // whatever the git workspace config calls it
                            String uri = pair.getKey();
                            String uriNorm = normalizeGitUrl(uri, true);
                            LOGGER.log(Level.FINE, "getSubmodulesUrls(): checking uri='" + uri + "' (uriNorm='" + uriNorm + "') vs needle");
                            if (needle != null && needleNorm != null && (needleNorm.equals(uriNorm) || needle.equals(uri)) ) {
                                result = new LinkedHashSet<>();
                                result.add(new String[]{dirname, uri, uriNorm, remoteName});
                                return new SimpleEntry<>(true, result);
                            }
                            // Cache the finding to return eventually, for each remote:
                            // * absolute dirname of a Git workspace
                            // * original remote URI from that workspace's config
                            // * normalized remote URI
                            // * name of the remote from that workspace's config ("origin" etc)
                            result.add(new String[]{dirname, uri, uriNorm, remoteName});
                        }
                    } catch (Throwable t) {
                        // ignore, go to next slide
                        LOGGER.log(Level.FINE, "getSubmodulesUrls(): probing dir '" + dirname + "' resulted in an exception or error (will go to next item):\n" + t.toString());
                    }
                }

                // Here is a good spot to recurse this routine into a
                // subdir that is already a known git workspace, to
                // add its data to list and/or return a found needle.
                LOGGER.log(Level.FINE, "getSubmodulesUrls(): recursing into dir '" + dirname + "'...");
                SimpleEntry <Boolean, LinkedHashSet<String[]>> subEntriesRet = getSubmodulesUrls(dirname, needle, false);
                Boolean subEntriesExactMatched = subEntriesRet.getKey();
                LinkedHashSet<String[]> subEntries = subEntriesRet.getValue();
                LOGGER.log(Level.FINE, "getSubmodulesUrls(): returned from recursing into dir '" + dirname + "' with " + subEntries.size() + " found mappings");
                if (!subEntries.isEmpty()) {
                    if (needle != null && subEntriesExactMatched) {
                        // We found nothing... until now! Bubble it up!
                        LOGGER.log(Level.FINE, "getSubmodulesUrls(): got an exact needle match from recursing into dir '" + dirname + "': " + subEntries.iterator().next()[0]);
                        return subEntriesRet;
                    }
                    // ...else collect results to inspect and/or propagate later
                    result.addAll(subEntries);
                }
            }
        }

        // Nothing found, if we had a needle - so report there are no hits
        // If we did not have a needle, we did not search for it - return
        // below whatever result we have, then.
        if (needle != null) {
            if (result.size() == 0) {
                // Completely nothing git-like found here, return quickly
                return new SimpleEntry<>(false, result);
            }

            // Handle suggestions (not-exact matches) if something from
            // results looks like it is related to the needle.
            LinkedHashSet<String[]> resultFiltered = new LinkedHashSet<>();

/*
            if (!checkRemotesInReferenceBaseDir) {
                // Overload the flag's meaning to only parse results once,
                // in the parent dir?
                return new SimpleEntry<>(false, resultFiltered);
            }
*/

            // Separate lists by suggestion priority:
            // 1) URI basename similarity
            // 2) Directory basename similarity
            LinkedHashSet<String[]> resultFiltered1 = new LinkedHashSet<>();
            LinkedHashSet<String[]> resultFiltered2 = new LinkedHashSet<>();

            LinkedHashSet<String> suggestedDirs = new LinkedHashSet<>();
            for (String[] resultEntry : result) {
                checkedDirs.add(resultEntry[0]);
            }

            for (String[] subEntry : result) {
                // Iterating to filter suggestions in order of original
                // directory-walk prioritization under current reference
                String dirName =    subEntry[0];
                String uri =        subEntry[1];
                String uriNorm =    subEntry[2];
                String remoteName = subEntry[3];
                Integer sep;
                String uriNormBasename;
                String dirBasename;

                // Match basename of needle vs. a remote tracked by an
                // existing git directory (automation-ready normalized URL)
                sep = uriNorm.lastIndexOf("/");
                if (sep < 0) {
                    uriNormBasename = uriNorm;
                } else {
                    uriNormBasename = uriNorm.substring(sep + 1);
                }
                uriNormBasename = uriNormBasename.replaceAll(".git$", "");

                if (uriNormBasename.equals(needleNormBasename)) {
                    resultFiltered1.add(subEntry);
                }

                if (!suggestedDirs.contains(dirName)) {
                    // Here just match basename of needle vs. an existing
                    // sub-git directory base name
                    suggestedDirs.add(dirName);

                    sep = dirName.lastIndexOf("/");
                    if (sep < 0) {
                        dirBasename = dirName;
                    } else {
                        dirBasename = dirName.substring(sep + 1);
                    }
                    dirBasename = dirBasename.replaceAll(".git$", "");

                    if (dirBasename.equals(needleNormBasename)) {
                        resultFiltered2.add(subEntry);
                    }
                }
            }

            // Concatenate suggestions in order of priority.
            // Hopefully the Set should deduplicate entries
            // if something matched twice :)
            resultFiltered.addAll(resultFiltered1); // URLs
            resultFiltered.addAll(resultFiltered2); // Dirnames

            // Note: flag is false since matches (if any) are
            // not exactly for the Git URL requested by caller
            return new SimpleEntry<>(false, resultFiltered);
        }

        // Did not look for anything in particular
        return new SimpleEntry<>(false, result);
    }

    /** See above. With null needle, returns all data we can find under the
     * referenceBaseDir tree (can take a while) for the caller to parse */
    public SimpleEntry<Boolean, LinkedHashSet<String[]>> getSubmodulesUrls(String referenceBaseDir) {
        return getSubmodulesUrls(referenceBaseDir, null, true);
    }
    /* Do we need a completely parameter-less variant to look under current
     * work dir aka Paths.get("").toAbsolutePath().toString(), or under "this"
     * GitClient workspace ?.. */

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
        // For mass-configured jobs, like Organization Folders, which inherit
        // a refrepo setting (String) into generated MultiBranch Pipelines and
        // leaf jobs made for each repo branch and PR, the code below allows
        // us to support parameterized paths, with one string leading to many
        // reference repositories fanned out under a common location.
        // This also works for legacy jobs using a Git SCM.

        // TODO: Consider a config option whether to populate absent reference
        // repos (If the expanded path does not have git repo data right now,
        // should we populate it into the location expanded by logic below),
        // or update existing ones before pulling commits, and how to achieve
        // that. Currently this is something that comments elsewhere in the
        // git-client-plugin and/or articles on reference repository setup
        // considered to be explicitly out of scope of the plugin.
        // Note that this pre-population (or update) is done by caller with
        // their implementation of git and site-specific connectivity and
        // storage desigh, e.g. in case of a build farm it is more likely
        // to be a shared path from a common storage server only readable to
        // Jenkins and its agents, so write-operations would be done by helper
        // scripts that log into the shared storage server to populate or
        // update the reference repositories. Note that users may also
        // want to run their own scripts to "populate" reference repos
        // as symlinks to existing other repos, to support combined
        // repo setup for different URLs pointing to same upstream,
        // or storing multiple closely related forks together.
        // This feature was developed along with a shell script to manage
        // reference repositories, both in original combined-monolith layout,
        // and in the subdirectory fanout compatible with plugin code below:
        // https://github.com/jimklimov/git-scripts/blob/master/register-git-cache.sh

        // Note: Initially we expect the reference to be a realistic dirname
        // with a special suffix to substitute after the logic below, so the
        // referencePath for that verbatim funny string should not exist now:
        if (!referencePath.exists() &&
            isParameterizedReferenceRepository(reference) &&
            url != null && !url.isEmpty()
        ) {
            // Drop the trailing keyword to know the root refrepo dirname
            String referenceBaseDir = reference.replaceAll("/\\$\\{GIT_[^\\}]*\\}$", "");

            File referenceBaseDirFile = new File(referenceBaseDir);
            if (!referenceBaseDirFile.exists()) {
                LOGGER.log(Level.WARNING, "Base Git reference directory " + referenceBaseDir + " does not exist");
                return null;
            }
            if (!referenceBaseDirFile.isDirectory()) {
                LOGGER.log(Level.WARNING, "Base Git reference directory " + referenceBaseDir + " is not a directory");
                return null;
            }

            // Note: this normalization might crush several URLs into one,
            // and as far as is known this would be the goal - people tend
            // to use or omit .git suffix, or spell with varied case, while
            // it means the same thing to known Git platforms, except local
            // dirs on case-sensitive filesystems.
            // The actual reference repository directory may choose to have
            // original URL strings added as remotes (in case some are case
            // sensitive and different).
            String urlNormalized = normalizeGitUrl(url, true);

            // Note: currently unit-tests expect this markup on stderr:
            System.err.println("reference='" + reference + "'\n" +
                "url='" + url + "'\n" +
                "urlNormalized='" + urlNormalized + "'\n");

            // Let users know why there are many "git config --list" lines in their build log:
            LOGGER.log(Level.INFO, "Trying to resolve parameterized Git reference repository '" +
                reference + "' into a specific (sub-)directory to use for URL '" + url + "' ...");

            String referenceExpanded = null;
            if (reference.endsWith("/${GIT_URL_SHA256}")) {
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
                // Beside git-submodule parsing (that only points to one URL
                // at a time) his also covers a search for subdirectories
                // that host a git repository whose remotes match the URL,
                // to handle co-hosting of several remotes (different URLs
                // to same repository, e.g. SSH and HTTPS; mirrors; forks).

                // Assuming the provided "reference" directory already hosts
                // submodules, we use git tools to find the one subdir which
                // has a registered remote URL equivalent (per normalization)
                // to the provided "url".

                // Note: we pass the unmodified "url" value here, the routine
                // differentiates original spelling vs. normalization while
                // looking for its needle in the haystack.
                SimpleEntry <Boolean, LinkedHashSet<String[]>> subEntriesRet = getSubmodulesUrls(referenceBaseDir, url, true);
                Boolean subEntriesExactMatched = subEntriesRet.getKey();
                LinkedHashSet<String[]> subEntries = subEntriesRet.getValue();
                if (!subEntries.isEmpty()) {
                    // Normally we should only have one entry here, as sorted
                    // by the routine, and prefer that first option if a new
                    // reference repo would have to be made (and none exists).
                    // If several entries are present after all, iterate until
                    // first existing hit and return the first entry otherwise.
                    if (!subEntriesExactMatched) { // else look at first entry below
                        for (String[] subEntry : subEntries) {
                            if (getObjectsFile(subEntry[0]) != null || getObjectsFile(subEntry[0] + ".git") != null) {
                                referenceExpanded = subEntry[0];
                                break;
                            }
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
                    // directory name to host the submodule (same rules as for
                    // the refrepo forks' co-hosting friendly basename search),
                    // and the fallback mode would return the main directory.
                    int sep = url.lastIndexOf("/");
                    String needleBasename;
                    if (sep < 0) {
                        needleBasename = url;
                    } else {
                        needleBasename = url.substring(sep + 1);
                    }
                    needleBasename = needleBasename.replaceAll(".git$", "");

                    if (reference.endsWith("/${GIT_SUBMODULES}")) {
                        referenceExpanded = reference.replaceAll("\\$\\{GIT_SUBMODULES\\}$",
                            needleBasename);
                    }
                    else { // if (reference.endsWith("/${GIT_SUBMODULES_FALLBACK}")) {
                        referenceExpanded = reference.replaceAll("\\$\\{GIT_SUBMODULES\\}$",
                            needleBasename);
                        if (reference.endsWith("/${GIT_SUBMODULES_FALLBACK}") && getObjectsFile(referenceExpanded) == null && getObjectsFile(referenceExpanded + ".git") == null) {
                            // chop it off, use main directory
                            referenceExpanded = referenceBaseDir;
                        }
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

            LOGGER.log(Level.INFO, "After resolving the parameterized Git reference repository, " +
                "decided to use '" + reference + "' directory for URL '" + url + "'");
        } // if referencePath is the replaceable token and not existing directory

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
     * to handle clashes (e.g. "remoteName/main" for branch "main" (refs/heads/main) in remote "remoteName" and branch "remoteName/main" (refs/heads/remoteName/main)).
     * <br><br>
     * Existing behavior is intentionally being retained so that
     * current use cases are not disrupted by a behavioral change.
     * <br><br>
     * E.g.
     * <table>
     * <caption>Branch Spec Normalization Examples</caption>
     * <tr><th>branch spec</th><th>normalized</th></tr>
     * <tr><td><code>main</code></td><td><code>main*</code></td></tr>
     * <tr><td><code>feature1</code></td><td><code>feature1*</code></td></tr>
     * <tr><td><code>feature1/main</code></td><td><div style="color:red">main <code>feature1/main</code>*</div></td></tr>
     * <tr><td><code>origin/main</code></td><td><code>main*</code></td></tr>
     * <tr><td><code>repo2/feature1</code></td><td><code>feature1*</code></td></tr>
     * <tr><td><code>refs/heads/feature1</code></td><td><code>refs/heads/feature1</code></td></tr>
     * <tr><td>origin/namespaceA/fix15</td>
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
