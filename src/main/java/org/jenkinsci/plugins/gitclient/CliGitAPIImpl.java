package org.jenkinsci.plugins.gitclient;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.LocalLauncher;
import hudson.Proc;
import hudson.Util;
import hudson.console.HyperlinkNote;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitLockFailedException;
import hudson.plugins.git.GitObject;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import hudson.util.ArgumentListBuilder;
import hudson.util.Secret;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.cgit.GitCommandsExecutor;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.kohsuke.stapler.framework.io.WriterOutputStream;

/**
 * Implementation class using command line CLI ran as external command.
 * <b>
 * For internal use only, don't use directly. See {@link org.jenkinsci.plugins.gitclient.Git}
 * </b>
 */
public class CliGitAPIImpl extends LegacyCompatibleGitAPIImpl {

    /**
     * Constant which can block use of setsid in git calls for ssh credentialed operations.
     *
     * <code>USE_SETSID=Boolean.valueOf(System.getProperty(CliGitAPIImpl.class.getName() + ".useSETSID", "false"))</code>.
     *
     * Allow ssh authenticated git calls on Unix variants to be preceded
     * by setsid so that the git command is run without being associated
     * with a terminal. Some docker runtime cases, and some automated test
     * cases have shown that some versions of command line git or ssh will
     * not allow automatic answers to private key passphrase prompts
     * unless there is no controlling terminal associated with the process.
     */
    public static final boolean USE_SETSID =
            Boolean.parseBoolean(System.getProperty(CliGitAPIImpl.class.getName() + ".useSETSID", "false"));

    /**
     * Set promptForAuthentication=true if you must allow command line git
     * versions 2.3 and later to prompt the user for authentication.
     *
     * Command line git prompting for authentication should be rare, since
     * Jenkins credentials should be managed through the credentials plugin.
     *
     * Command line git 2.3 and later read the environment variable
     * GIT_TERMINAL_PROMPT. If it has the value 0, then git will not prompt the
     * user for authentication, even if a terminal is available (as when running
     * a Jenkins agent from the Windows desktop, or when running it
     * interactively from the command line, or from a Docker image). If a
     * terminal is not available (most services on Windows and Linux), then
     * command line git will not prompt for authentication, whether or not
     * GIT_TERMINAL_PROMPT is set.
     *
     * GCM_INTERACTIVE=never is the environment variable which should
     * cause the git credential manager for windows to never prompt
     * for credentials.
     *
     * Credential prompting could happen on multiple platforms, but is
     * more common on Windows computers because many Windows agents
     * run from the desktop environment.  Agents running on the
     * desktop are much less common in the Unix environments.
     */
    private static final boolean PROMPT_FOR_AUTHENTICATION = Boolean.parseBoolean(
            System.getProperty(CliGitAPIImpl.class.getName() + ".promptForAuthentication", "false"));

    /**
     * CALL_SETSID decides if command line git can use the setsid program
     * during ssh based authentication to detach git from its controlling
     * terminal.
     *
     * If the controlling terminal remains attached, then ssh passphrase based
     * private keys cannot be decrypted during authentication (at least in some
     * ssh configurations).
     *
     * Package protected so that tests can override it.
     */
    static boolean CALL_SETSID;

    /**
     * Needed file permission for OpenSSH client that is made by Windows,
     * this will remove unwanted users and inherited permissions
     * which is required when the git client is using the SSH to clone
     *
     * The ssh client that the git client ships ignores file permission on Windows
     * Which the PowerShell team at Microsoft decided to fix in their port of OpenSSH
     */
    static final EnumSet<AclEntryPermission> ACL_ENTRY_PERMISSIONS = EnumSet.of(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.DELETE,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.SYNCHRONIZE);

    static {
        CALL_SETSID = setsidExists() && USE_SETSID;
    }

    /**
     * Constant which prevents use of 'force' in git fetch with CLI git versions 2.20 and later.
     *
     * <code>USE_FORCE_FETCH=Boolean.valueOf(System.getProperty(CliGitAPIImpl.class.getName() + ".forceFetch", "true"))</code>.
     *
     * Command line git 2.20 and later have changed fetch of remote
     * tags which already exist in the repository. Command line git
     * before 2.20 silently updates an existing tag if the remote tag
     * points to a different SHA1 than the local tag.  Command line
     * git 2.20 and later do not update an existing tag if the remote
     * tag points to a different SHA1 than the local tag unless the
     * 'force' option is passed to 'git fetch'.
     *
     * Use '-Dorg.jenkinsci.plugins.gitclient.CliGitAPIImpl.forceFetch=false'
     * to prevent 'force' in 'git fetch' with CLI git 2.20 and later.
     */
    private static final boolean USE_FORCE_FETCH =
            Boolean.parseBoolean(System.getProperty(CliGitAPIImpl.class.getName() + ".forceFetch", "true"));

    private static final Logger LOGGER = Logger.getLogger(CliGitAPIImpl.class.getName());

    private static final long serialVersionUID = 1;
    static final String SPARSE_CHECKOUT_FILE_DIR = ".git/info";
    static final String SPARSE_CHECKOUT_FILE_PATH = ".git/info/sparse-checkout";
    static final String TIMEOUT_LOG_PREFIX = " # timeout=";
    private static final String INDEX_LOCK_FILE_PATH = ".git" + File.separator + "index.lock";
    transient Launcher launcher;
    TaskListener listener;
    String gitExe;
    EnvVars environment;
    private Map<String, StandardCredentials> credentials = new HashMap<>();
    private StandardCredentials defaultCredentials;
    private final String encoding;

    /* If we fail some helper tool (e.g. SELinux chcon) do not make noise
     * until actually git fails. Use a TreeMap to sort by keys (timestamp).
     */
    private Map<Instant, String> failureClues = new TreeMap<>();

    /* git config --get-regex applies the regex to match keys, and returns all matches (including substring matches).
     * Thus, a config call:
     *   git config -f .gitmodules --get-regexp "^submodule\.(.+)\.url"
     * will report two lines of output if the submodule URL includes ".url":
     *   submodule.modules/JENKINS-46504.url.path modules/JENKINS-46504.url
     *   submodule.modules/JENKINS-46504.url.url https://github.com/MarkEWaite/JENKINS-46054.url
     * The code originally used the same pattern for get-regexp and for output parsing.
     * By using the same pattern in both places, it incorrectly took some substrings
     * as the submodule remote name, instead of taking the longest match.
     * See SubmodulePatternStringTest for test cases.
     */
    private static final String SUBMODULE_REMOTE_PATTERN_CONFIG_KEY = "^submodule\\.(.+)\\.url";

    /* See comments for SUBMODULE_REMOTE_PATTERN_CONFIG_KEY to explain
     * why this regular expression string adds the trailing space
     * characters and the sequence of non-space characters as part of
     * its match.  The ending sequence of non-white-space characters
     * is the repository URL in the output of the 'git config' command.
     * Relies on repository URL not containing a whitespace character,
     * per RFC1738.
     */
    /* Package protected for testing */
    static final String SUBMODULE_REMOTE_PATTERN_STRING = SUBMODULE_REMOTE_PATTERN_CONFIG_KEY + "\\s+[^\\s]+$";

    private void warnIfWindowsTemporaryDirNameHasSpaces() {
        if (!isWindows()) {
            return;
        }
        String[] varsToCheck = {"TEMP", "TMP"};
        for (String envVar : varsToCheck) {
            String value = environment.get(envVar, "C:\\Temp");
            if (value.contains(" ")) {
                listener.getLogger()
                        .println("env " + envVar + "='" + value + "' contains an embedded space."
                                + " Some msysgit versions may fail credential related operations.");
            }
        }
    }

    // AABBCCDD where AA=major, BB=minor, CC=rev, DD=bugfix
    private long gitVersion = 0;

    private long computeVersionFromBits(int major, int minor, int rev, int bugfix) {
        return (major * 1000000L) + (minor * 10000L) + (rev * 100L) + bugfix;
    }

    private void getGitVersion() {
        if (gitVersion != 0) {
            return;
        }

        String version = "";
        try {
            version = launchCommand("--version").trim();
            listener.getLogger().println(" > git --version # '" + version + "'");
        } catch (Throwable e) {
        }

        computeGitVersion(version);
    }

    /* package */ void computeGitVersion(String version) {
        int gitMajorVersion = 0;
        int gitMinorVersion = 0;
        int gitRevVersion = 0;
        int gitBugfixVersion = 0;

        try {
            /*
             * msysgit adds one more term to the version number. So
             * instead of Major.Minor.Rev.Bugfix, it displays
             * something like Major.Minor.Rev.msysgit.BugFix. This
             * removes the inserted term from the version string
             * before parsing.
             * git 2.5.0 for windows adds a similar component with
             * the string "windows".  Remove it as well
             */

            String[] fields = version.split(" ")[2]
                    .replace("msysgit.", "")
                    .replace("windows.", "")
                    .split("\\.");

            gitMajorVersion = Integer.parseInt(fields[0]);
            gitMinorVersion = (fields.length > 1) ? Integer.parseInt(fields[1]) : 0;
            gitRevVersion = (fields.length > 2) ? Integer.parseInt(fields[2]) : 0;
            gitBugfixVersion = (fields.length > 3) ? Integer.parseInt(fields[3]) : 0;
        } catch (Throwable e) {
            /* Oh well */
        }

        gitVersion = computeVersionFromBits(gitMajorVersion, gitMinorVersion, gitRevVersion, gitBugfixVersion);
    }

    /* package */ boolean isAtLeastVersion(int major, int minor, int rev, int bugfix) {
        getGitVersion();
        long requestedVersion = computeVersionFromBits(major, minor, rev, bugfix);
        return gitVersion >= requestedVersion;
    }

    /**
     * Compare the current cli git version with the required version.
     * Finds if the current cli git version is at-least the required version.
     *
     * Returns True if the current cli git version is at least the required version.
     *
     * @param major required major version for command line git
     * @param minor required minor version for command line git
     * @param rev required revision for command line git
     * @param bugfix required patches for command line git
     * @return true if the command line git version is at least the required version
     **/
    public boolean isCliGitVerAtLeast(int major, int minor, int rev, int bugfix) {
        return isAtLeastVersion(major, minor, rev, bugfix);
    }

    /**
     * Constructor for CliGitAPIImpl.
     *
     * @param gitExe a {@link java.lang.String} object.
     * @param workspace a {@link java.io.File} object.
     * @param listener a {@link hudson.model.TaskListener} object.
     * @param environment a {@link hudson.EnvVars} object.
     */
    protected CliGitAPIImpl(String gitExe, File workspace, TaskListener listener, EnvVars environment) {
        super(workspace);
        this.listener = listener;
        this.gitExe = gitExe;
        this.environment = environment;

        if (isZos() && System.getProperty("ibm.system.encoding") != null) {
            this.encoding =
                    Charset.forName(System.getProperty("ibm.system.encoding")).toString();
        } else {
            this.encoding = Charset.defaultCharset().toString();
        }

        launcher = new LocalLauncher(IGitAPI.verbose ? listener : TaskListener.NULL);
    }

    /** {@inheritDoc} */
    @Override
    public GitClient subGit(String subdir) {
        return new CliGitAPIImpl(gitExe, new File(workspace, subdir), listener, environment);
    }

    /**
     * Initialize an empty repository for further git operations.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public void init() throws GitException, InterruptedException {
        init_().workspace(workspace.getAbsolutePath()).execute();
    }

    /**
     * Returns true if this workspace has a git repository.
     * Also returns true if this workspace contains an empty .git directory and
     * a parent directory has a git repository.
     *
     * @return true if this workspace has a git repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public boolean hasGitRepo() throws GitException, InterruptedException {
        if (hasGitRepo(".git")) {
            // Check if this is a valid git repo with --is-inside-work-tree
            try {
                launchCommand("rev-parse", "--is-inside-work-tree");
            } catch (Exception ex) {
                ex.printStackTrace(listener.error("Workspace has a .git repository, but it appears to be corrupt."));
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Returns true if this workspace has a git repository.
     * If checkParentDirectories is true, searches parent directories.
     * If checkParentDirectories is false, checks workspace directory only.
     *
     * @param checkParentDirectories if true, search upward for a git repository
     * @return true if this workspace has a git repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public boolean hasGitRepo(boolean checkParentDirectories) throws GitException, InterruptedException {
        if (checkParentDirectories) {
            return hasGitRepo();
        }
        if (hasGitRepo(".git")) {
            // Check if this is a valid git repo with --resolve-git-dir
            try {
                launchCommand("rev-parse", "--resolve-git-dir", workspace.getAbsolutePath() + File.separator + ".git");
            } catch (Exception ex) {
                ex.printStackTrace(listener.error("Workspace has a .git repository, but it appears to be corrupt."));
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Returns true if the parameter GIT_DIR is a directory which
     * contains a git repository.
     *
     * @param GIT_DIR a {@link java.lang.String} object.
     * @return true if GIT_DIR has a git repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    public boolean hasGitRepo(String GIT_DIR) throws GitException {
        try {
            File dotGit = new File(workspace, GIT_DIR);
            return dotGit.exists();
        } catch (SecurityException ex) {
            throw new GitException(
                    "Security error when trying to check for .git. Are you sure you have correct permissions?", ex);
        } catch (Exception e) {
            throw new GitException("Couldn't check for .git", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<IndexEntry> getSubmodules(String treeIsh) throws GitException, InterruptedException {
        List<IndexEntry> submodules = lsTree(treeIsh, true);

        // Remove anything that isn't a submodule
        submodules.removeIf(indexEntry -> !indexEntry.getMode().equals("160000"));
        return submodules;
    }

    // Package protected for testing
    /**
     * Constant which disables safety check of remote URL.
     *
     * <code>CHECK_REMOTE_URL=Boolean.valueOf(System.getProperty(CliGitAPIImpl.class.getName() + ".checkRemoteURL", "true"))</code>.
     *
     * Refuse unsafe git URL's, including URL's that start with '-'
     * and URL's that contain space characters.
     *
     * Use '-Dorg.jenkinsci.plugins.gitclient.CliGitAPIImpl.checkRemoteURL=false'
     * to prevent check of remote URL.
     */
    static boolean CHECK_REMOTE_URL =
            Boolean.parseBoolean(System.getProperty(CliGitAPIImpl.class.getName() + ".checkRemoteURL", "true"));

    /**
     * SECURITY-1534 found that arguments
     * added to a git URL from the user interface can allow a user
     * with job creation permissions to execute an arbitrary program
     * on the git server if the git server is configured to allow
     * custom pack programs. Reject a URL if it includes invalid
     * content.
     */
    private void addCheckedRemoteUrl(@NonNull ArgumentListBuilder args, @NonNull String url) {
        String trimmedUrl = url.trim();
        /* Don't check for invalid args if URL starts with known good cases.
         * Known good cases include:
         * '/' - Unix local file
         * 'C:' - Windows local file
         * 'file:', 'git:', 'http:', 'https:', 'ssh:', and 'git@' - known protocols
         */
        if (CHECK_REMOTE_URL
                && !trimmedUrl.startsWith("/")
                && !trimmedUrl.startsWith("\\\\")
                && !trimmedUrl.startsWith("file:")
                && !trimmedUrl.startsWith("git:")
                && !trimmedUrl.startsWith("git@")
                && !trimmedUrl.startsWith("http:")
                && !trimmedUrl.startsWith("https:")
                && !trimmedUrl.startsWith("ssh:")
                && !trimmedUrl.matches("^[A-Za-z]:.+")) {
            /* Not one of the known good cases, check if this could be a bad case
             * Bad cases include:
             * '-' - starts with 'dash' as possible argument
             * '`' - includes backquote in the string (command execution)
             * ' ' - includes a space (not guaranteed a threat, but threat risk increases)
             */
            if (trimmedUrl.startsWith("-")
                    || trimmedUrl.contains("`")
                    || trimmedUrl.contains("--upload-pack=")
                    || trimmedUrl.matches(".*\\s+.*")) {
                throw new GitException("Invalid remote URL: " + url);
            }
        }
        // Mark the end of options for git versions that support it.
        // Tells command line git that later arguments are operands, not options.
        // See POSIX 1-2017 guideline 10.
        if (isAtLeastVersion(2, 8, 0, 0)) {
            args.add("--"); // SECURITY-1534 - end of options, causes tests to fail with git 2.7.4 and older
        }
        args.add(trimmedUrl);
    }

    /**
     * fetch_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.FetchCommand} object.
     */
    @Override
    public FetchCommand fetch_() {
        return new FetchCommand() {
            private URIish url;
            private List<RefSpec> refspecs;
            private boolean prune;
            private boolean shallow;
            private Integer timeout;
            private boolean tags = true;
            private Integer depth = 1;

            @Override
            public FetchCommand from(URIish remote, List<RefSpec> refspecs) {
                this.url = remote;
                this.refspecs = refspecs;
                return this;
            }

            @Override
            public FetchCommand tags(boolean tags) {
                this.tags = tags;
                return this;
            }

            @Override
            public FetchCommand prune() {
                return prune(true);
            }

            @Override
            public FetchCommand prune(boolean prune) {
                this.prune = prune;
                return this;
            }

            @Override
            public FetchCommand shallow(boolean shallow) {
                this.shallow = shallow;
                return this;
            }

            @Override
            public FetchCommand timeout(Integer timeout) {
                this.timeout = timeout;
                return this;
            }

            @Override
            public FetchCommand depth(Integer depth) {
                this.depth = depth;
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                listener.getLogger().println("Fetching upstream changes from " + url);

                ArgumentListBuilder args = new ArgumentListBuilder();
                args.add("fetch");
                args.add(tags ? "--tags" : "--no-tags");
                if (USE_FORCE_FETCH && isAtLeastVersion(2, 20, 0, 0)) {
                    /* CLI git 2.20.0 fixed a long-standing bug that now requires --force to update existing tags */
                    args.add("--force");
                }
                if (isAtLeastVersion(1, 7, 1, 0)) {
                    args.add("--progress");
                }

                if (prune) {
                    args.add("--prune");
                }

                if (shallow) {
                    if (depth == null) {
                        depth = 1;
                    }
                    args.add("--depth=" + depth);
                }

                warnIfWindowsTemporaryDirNameHasSpaces();

                StandardCredentials cred = credentials.get(url.toPrivateString());
                if (cred == null) {
                    cred = defaultCredentials;
                }
                if (isAtLeastVersion(1, 8, 0, 0)) {
                    addCheckedRemoteUrl(args, url.toPrivateASCIIString());
                } else {
                    // CLI git 1.7.1 on CentOS 6 rejects URL encoded
                    // repo URL. This is how git client behaved before
                    // 2.8.5, with the addition of a safety check for
                    // the remote URL string contents.
                    //
                    // CLI git 1.7.1 is unsupported by the git client
                    // plugin, but we try to avoid removing
                    // capabilities that worked previously.
                    addCheckedRemoteUrl(args, url.toString());
                }

                if (refspecs != null) {
                    for (RefSpec rs : refspecs) {
                        if (rs != null) {
                            args.add(rs.toString());
                        }
                    }
                }

                /* If url looks like a remote name reference, convert to remote URL for authentication */
                /* See JENKINS-50573 for more details */
                /* "git remote add" rejects remote names with ':' (and it is a common character in remote URLs) */
                /* "git remote add" allows remote names with '@' but internal git parsing problems seem likely (and it is a common character in remote URLs) */
                /* "git remote add" allows remote names with '/' but git client plugin parsing problems will occur (and it is a common character in remote URLs) */
                /* "git remote add" allows remote names with '\' but git client plugin parsing problems will occur */
                URIish remoteUrl = url;
                if (!url.isRemote() && !StringUtils.containsAny(url.toString(), ":@/\\")) {
                    try {
                        remoteUrl = new URIish(getRemoteUrl(url.toString()));
                    } catch (URISyntaxException e) {
                        listener.getLogger().println("Unexpected remote name or URL: '" + url + "'");
                    }
                }

                launchCommandWithCredentials(args, workspace, cred, remoteUrl, timeout);
            }
        };
    }

    /** {@inheritDoc} */
    @Override
    public void fetch(URIish url, List<RefSpec> refspecs) throws GitException, InterruptedException {
        fetch_().from(url, refspecs).execute();
    }

    /** {@inheritDoc} */
    @Override
    public void fetch(String remoteName, RefSpec... refspec) throws GitException, InterruptedException {
        listener.getLogger().println("Fetching upstream changes" + (remoteName != null ? " from " + remoteName : ""));

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("fetch", "-t");
        if (USE_FORCE_FETCH && isAtLeastVersion(2, 20, 0, 0)) {
            /* CLI git 2.20.0 fixed a long-standing bug that now requires --force to update existing tags */
            args.add("--force");
        }

        if (remoteName == null) {
            remoteName = getDefaultRemote();
        }

        String url = getRemoteUrl(remoteName);
        if (url == null) {
            throw new GitException("remote." + remoteName + ".url not defined");
        }
        addCheckedRemoteUrl(args, url);
        if (refspec != null && refspec.length > 0) {
            for (RefSpec rs : refspec) {
                if (rs != null) {
                    args.add(rs.toString());
                }
            }
        }

        StandardCredentials cred = getCredentials(url);
        launchCommandWithCredentials(args, workspace, cred, url);
    }

    /** {@inheritDoc} */
    @Override
    public void fetch(String remoteName, RefSpec refspec) throws GitException, InterruptedException {
        fetch(remoteName, new RefSpec[] {refspec});
    }

    /** {@inheritDoc} */
    @Override
    public void reset(boolean hard) throws GitException, InterruptedException {
        try {
            validateRevision("HEAD");
        } catch (GitException e) {
            listener.getLogger().println("No valid HEAD. Skipping the resetting");
            return;
        }
        listener.getLogger().println("Resetting working tree");

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("reset");
        if (hard) {
            args.add("--hard");
        }

        launchCommand(args);
    }

    /**
     * clone_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    @Override
    public CloneCommand clone_() {
        return new CloneCommand() {
            private String url;
            private String origin = "origin";
            private String reference;
            private boolean shallow, shared;
            private Integer timeout;
            private boolean tags = true;
            private List<RefSpec> refspecs;
            private Integer depth = 1;

            @Override
            public CloneCommand url(String url) {
                this.url = url;
                return this;
            }

            @Override
            public CloneCommand repositoryName(String name) {
                this.origin = name;
                return this;
            }

            @Override
            public CloneCommand shared() {
                return shared(true);
            }

            @Override
            public CloneCommand shared(boolean shared) {
                this.shared = shared;
                return this;
            }

            @Override
            public CloneCommand shallow() {
                return shallow(true);
            }

            @Override
            public CloneCommand shallow(boolean shallow) {
                this.shallow = shallow;
                return this;
            }

            @Override
            public CloneCommand noCheckout() {
                // this.noCheckout = true; Since the "clone" command has been replaced with init + fetch, the
                // --no-checkout option is always satisfied
                return this;
            }

            @Override
            public CloneCommand tags(boolean tags) {
                this.tags = tags;
                return this;
            }

            @Override
            public CloneCommand reference(String reference) {
                this.reference = reference;
                return this;
            }

            @Override
            public CloneCommand timeout(Integer timeout) {
                this.timeout = timeout;
                return this;
            }

            @Override
            public CloneCommand depth(Integer depth) {
                this.depth = depth;
                return this;
            }

            @Override
            public CloneCommand refspecs(List<RefSpec> refspecs) {
                this.refspecs = new ArrayList<>(refspecs);
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {

                URIish urIish = null;
                try {
                    urIish = new URIish(url);
                } catch (URISyntaxException e) {
                    listener.getLogger().println("Invalid repository " + url);
                    throw new IllegalArgumentException("Invalid repository " + url, e);
                }

                listener.getLogger().println("Cloning repository " + url);

                try {
                    Util.deleteContentsRecursive(workspace);
                } catch (Exception e) {
                    e.printStackTrace(listener.error("Failed to clean the workspace"));
                    throw new GitException("Failed to delete workspace", e);
                }

                // we don't run a 'git clone' command but git init + git fetch
                // this allows launchCommandWithCredentials() to pass credentials via a local gitconfig

                init_().workspace(workspace.getAbsolutePath()).execute();

                if (shared) {
                    if (reference == null || reference.isEmpty()) {
                        // we use origin as reference
                        reference = url;
                    } else {
                        listener.getLogger().println("[WARNING] Both shared and reference is used, shared is ignored.");
                    }
                }

                if (reference != null && !reference.isEmpty()) {
                    File referencePath = new File(reference);
                    if (!referencePath.exists()) {
                        listener.getLogger().println("[WARNING] Reference path does not exist: " + reference);
                    } else if (!referencePath.isDirectory()) {
                        listener.getLogger().println("[WARNING] Reference path is not a directory: " + reference);
                    } else {
                        // reference path can either be a normal or a base repository
                        File objectsPath = new File(referencePath, ".git/objects");
                        if (!objectsPath.isDirectory()) {
                            // reference path is bare repo
                            objectsPath = new File(referencePath, "objects");
                        }
                        if (!objectsPath.isDirectory()) {
                            listener.getLogger()
                                    .println(
                                            "[WARNING] Reference path does not contain an objects directory (not a git repo?): "
                                                    + objectsPath);
                        } else {
                            File alternates = new File(workspace, ".git/objects/info/alternates");
                            try (PrintWriter w = new PrintWriter(alternates, Charset.defaultCharset())) {
                                String absoluteReference =
                                        objectsPath.getAbsolutePath().replace('\\', '/');
                                listener.getLogger().println("Using reference repository: " + reference);
                                // git implementations on windows also use
                                w.print(absoluteReference);
                            } catch (IOException e) {
                                listener.error("Failed to setup reference");
                            }
                        }
                    }
                }

                if (refspecs == null) {
                    refspecs = Collections.singletonList(new RefSpec("+refs/heads/*:refs/remotes/" + origin + "/*"));
                }
                fetch_().from(urIish, refspecs)
                        .shallow(shallow)
                        .depth(depth)
                        .timeout(timeout)
                        .tags(tags)
                        .execute();
                setRemoteUrl(origin, url);
                for (RefSpec refSpec : refspecs) {
                    launchCommand("config", "--add", "remote." + origin + ".fetch", refSpec.toString());
                }
            }
        };
    }

    /**
     * merge.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.MergeCommand} object.
     */
    @Override
    public MergeCommand merge() {
        return new MergeCommand() {
            private ObjectId rev;
            private String comment;
            private String strategy;
            private String fastForwardMode;
            private boolean squash;
            private boolean commit = true;

            @Override
            public MergeCommand setRevisionToMerge(ObjectId rev) {
                this.rev = rev;
                return this;
            }

            @Override
            public MergeCommand setStrategy(MergeCommand.Strategy strategy) {
                this.strategy = strategy.toString();
                return this;
            }

            @Override
            public MergeCommand setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode fastForwardMode) {
                this.fastForwardMode = fastForwardMode.toString();
                return this;
            }

            @Override
            public MergeCommand setSquash(boolean squash) {
                this.squash = squash;
                return this;
            }

            @Override
            public MergeCommand setMessage(String comment) {
                this.comment = comment;
                return this;
            }

            @Override
            public MergeCommand setCommit(boolean commit) {
                this.commit = commit;
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                ArgumentListBuilder args = new ArgumentListBuilder();
                args.add("merge");
                if (squash) {
                    args.add("--squash");
                }

                if (!commit) {
                    args.add("--no-commit");
                }

                if (comment != null && !comment.isEmpty()) {
                    args.add("-m");
                    args.add(comment);
                }

                if (strategy != null
                        && !strategy.isEmpty()
                        && !strategy.equals(MergeCommand.Strategy.DEFAULT.toString())) {
                    args.add("-s");
                    if (strategy.equals(MergeCommand.Strategy.RECURSIVE_THEIRS.toString())) {
                        args.add("recursive");
                        args.add("--strategy-option");
                        args.add("theirs");
                    } else {
                        args.add(strategy);
                    }
                }

                args.add(fastForwardMode);
                if (rev == null) {
                    throw new GitException("MergeCommand requires a revision to merge");
                }
                args.add(rev.name());

                /* See JENKINS-45228 */
                /* Git merge requires authentication in LFS merges, plugin does not authenticate the git merge command */
                String repoUrl = null;
                try {
                    String defaultRemote = getDefaultRemote();
                    if (defaultRemote != null && !defaultRemote.isEmpty()) {
                        repoUrl = getRemoteUrl(defaultRemote);
                    }
                } catch (GitException e) {
                    /* Nothing to do, just keeping repoUrl = null */
                }

                if (repoUrl != null) {
                    StandardCredentials cred = credentials.get(repoUrl);
                    if (cred == null) {
                        cred = defaultCredentials;
                    }
                    launchCommandWithCredentials(args, workspace, cred, repoUrl);
                } else {
                    /* Merge is allowed even if a remote URL is not defined. */
                    /* If there is no remote URL, there is no need to use credentials in the merge. */
                    launchCommand(args);
                }
            }
        };
    }

    /**
     * rebase.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RebaseCommand} object.
     */
    @Override
    public RebaseCommand rebase() {
        return new RebaseCommand() {
            private String upstream;

            @Override
            public RebaseCommand setUpstream(String upstream) {
                this.upstream = upstream;
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                try {
                    ArgumentListBuilder args = new ArgumentListBuilder();
                    args.add("rebase");
                    args.add(upstream);
                    launchCommand(args);
                } catch (GitException e) {
                    launchCommand("rebase", "--abort");
                    throw new GitException("Could not rebase " + upstream, e);
                }
            }
        };
    }

    /**
     * init_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.InitCommand} object.
     */
    @Override
    public InitCommand init_() {
        return new InitCommand() {

            private String workspace;
            private boolean bare;

            @Override
            public InitCommand workspace(String workspace) {
                this.workspace = workspace;
                return this;
            }

            @Override
            public InitCommand bare(boolean bare) {
                this.bare = bare;
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                /* Match JGit - create directory if it does not exist */
                /* Multi-branch pipeline assumes init() creates directory */
                File workspaceDir = new File(workspace);
                if (!workspaceDir.exists()) {
                    boolean ok = workspaceDir.mkdirs();
                    if (!ok && !workspaceDir.exists()) {
                        throw new GitException("Could not create directory '" + workspaceDir.getAbsolutePath() + "'");
                    }
                }

                ArgumentListBuilder args = new ArgumentListBuilder();
                args.add("init", workspace);

                if (bare) {
                    args.add("--bare");
                }

                warnIfWindowsTemporaryDirNameHasSpaces();

                try {
                    launchCommand(args);
                } catch (GitException e) {
                    throw new GitException("Could not init " + workspace, e);
                }
            }
        };
    }

    /**
     * Remove untracked files and directories, including files listed
     * in the ignore rules.
     *
     * @param cleanSubmodule flag to add extra -f
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public void clean(boolean cleanSubmodule) throws GitException, InterruptedException {
        reset(true);
        String cmd = "-fdx";
        if (cleanSubmodule) {
            cmd = "-ffdx";
        }

        launchCommand("clean", cmd);
    }

    /**
     * Remove untracked files and directories, including files listed
     * in the ignore rules.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public void clean() throws GitException, InterruptedException {
        this.clean(false);
    }

    /** {@inheritDoc} */
    @Override
    public ObjectId revParse(String revName) throws GitException, InterruptedException {

        String arg = sanitize(revName + "^{commit}");
        String result = launchCommand("rev-parse", arg);
        String line = StringUtils.trimToNull(result);
        if (line == null) {
            throw new GitException("rev-parse no content returned for " + revName);
        }
        return ObjectId.fromString(line);
    }

    /**
     * On Windows command prompt, '^' is an escape character (https://en.wikipedia.org/wiki/Escape_character#Windows_Command_Prompt)
     * This isn't a problem if 'git' we are executing is git.exe, because '^' is a special character only for the command processor,
     * but if 'git' we are executing is git.cmd (which is the case of msysgit), then the arguments we pass in here ends up getting
     * processed by the command processor, and so 'xyz^{commit}' becomes 'xyz{commit}' and fails.
     * <p>
     * We work around this problem by surrounding this with double-quote on Windows.
     * Unlike POSIX, where the arguments of a process is modeled as String[], Win32 API models the
     * arguments of a process as a single string (see CreateProcess). When we surround one argument with a quote,
     * java.lang.ProcessImpl on Windows preserve as-is and generate a single string like the following to pass to CreateProcess:
     * <pre>
     *     git rev-parse "tag^{commit}"
     * </pre>
     * If we invoke git.exe, MSVCRT startup code in git.exe will handle escape and executes it as we expect.
     * If we invoke git.cmd, cmd.exe will not eats this ^ that's in double-quote. So it works on both cases.
     * <p>
     * Note that this is a borderline-buggy behaviour arguably. If I were implementing ProcessImpl for Windows
     * in JDK, My passing a string with double-quotes around it to be expanded to the following:
     * <pre>
     *    git rev-parse "\"tag^{commit}\""
     * </pre>
     * So this work around that we are doing for Windows relies on the assumption that Java runtime will not
     * change this behaviour.
     * <p>
     * Also note that on Unix we cannot do this. Similarly, other ways of quoting (like using '^^' instead of '^'
     * that you do on interactive command prompt) do not work either, because MSVCRT startup won't handle
     * those in the same way cmd.exe does.
     *
     * See JENKINS-13007 where this blew up on Windows users.
     * See https://github.com/msysgit/msysgit/issues/36 where I filed this as a bug to msysgit.
     **/
    private String sanitize(String arg) {
        if (isWindows()) {
            arg = '"' + arg + '"';
        }
        return arg;
    }

    /**
     * validateRevision.
     *
     * @param revName a {@link java.lang.String} object.
     * @return a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public ObjectId validateRevision(String revName) throws GitException, InterruptedException {
        String result = launchCommand("rev-parse", "--verify", revName);
        String line = StringUtils.trimToNull(result);
        if (line == null) {
            throw new GitException("null result from rev-parse(" + revName + ")");
        }
        return ObjectId.fromString(line);
    }

    /** {@inheritDoc} */
    @Override
    public String describe(String commitIsh) throws GitException, InterruptedException {
        String result = launchCommand("describe", "--tags", commitIsh);
        String line = firstLine(result);
        if (line == null) {
            throw new GitException("null first line from describe(" + commitIsh + ")");
        }
        return line.trim();
    }

    /** {@inheritDoc} */
    @Override
    public void prune(RemoteConfig repository) throws GitException, InterruptedException {
        String repoName = repository.getName();
        String repoUrl = getRemoteUrl(repoName);
        if (repoUrl != null && !repoUrl.isEmpty()) {
            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add("remote", "prune", repoName);

            StandardCredentials cred = credentials.get(repoUrl);
            if (cred == null) {
                cred = defaultCredentials;
            }

            try {
                launchCommandWithCredentials(args, workspace, cred, new URIish(repoUrl));
            } catch (URISyntaxException ex) {
                throw new GitException("Invalid URL " + repoUrl, ex);
            }
        }
    }

    @SuppressFBWarnings(
            value = "RV_DONT_JUST_NULL_CHECK_READLINE",
            justification = "Only needs first line, exception if multiple detected")
    private @CheckForNull String firstLine(String result) {
        BufferedReader reader = new BufferedReader(new StringReader(result));
        String line;
        try {
            line = reader.readLine();
            if (line == null) {
                return null;
            }
            if (reader.readLine() != null) {
                throw new GitException("Result has multiple lines");
            }
        } catch (IOException e) {
            throw new GitException("Error parsing result", e);
        }

        return line;
    }

    /**
     * changelog.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    @Override
    public ChangelogCommand changelog() {
        return new ChangelogCommand() {

            /** Equivalent to the git-log raw format but using ISO 8601 date format - also prevent to depend on git CLI future changes */
            public static final String RAW =
                    "commit %H%ntree %T%nparent %P%nauthor %aN <%aE> %ai%ncommitter %cN <%cE> %ci%n%n%w(0,4,4)%B";

            private final List<String> revs = new ArrayList<>();

            private Integer n = null;
            private Writer out = null;

            @Override
            public ChangelogCommand excludes(String rev) {
                revs.add(sanitize('^' + rev));
                return this;
            }

            @Override
            public ChangelogCommand excludes(ObjectId rev) {
                return excludes(rev.name());
            }

            @Override
            public ChangelogCommand includes(String rev) {
                revs.add(rev);
                return this;
            }

            @Override
            public ChangelogCommand includes(ObjectId rev) {
                return includes(rev.name());
            }

            @Override
            public ChangelogCommand to(Writer w) {
                this.out = w;
                return this;
            }

            @Override
            public ChangelogCommand max(int n) {
                this.n = n;
                return this;
            }

            @Override
            public void abort() {
                /* No cleanup needed to abort the CliGitAPIImpl ChangelogCommand */
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                ArgumentListBuilder args = new ArgumentListBuilder(gitExe, "whatchanged", "--no-abbrev", "-M");
                if (isAtLeastVersion(1, 8, 3, 0)) {
                    args.add("--format=" + RAW);
                } else {
                    /* Ancient git versions don't support the required format string, use 'raw' and hope it works */
                    args.add("--format=raw");
                }
                if (n != null) {
                    args.add("-n").add(n);
                }
                for (String rev : this.revs) {
                    args.add(rev);
                }

                if (out == null) {
                    throw new IllegalStateException();
                }

                // "git whatchanged" std output gives us byte stream of data
                // Commit messages in that byte stream are UTF-8 encoded.
                // We want to decode bytestream to strings using UTF-8 encoding.
                try (WriterOutputStream w = new WriterOutputStream(out, StandardCharsets.UTF_8)) {
                    if (launcher.launch()
                                    .cmds(args)
                                    .envs(environment)
                                    .stdout(w)
                                    .stderr(listener.getLogger())
                                    .pwd(workspace)
                                    .join()
                            != 0) {
                        throw new GitException("Error: " + args + " in " + workspace);
                    }
                } catch (IOException e) {
                    throw new GitException("Error: " + args + " in " + workspace, e);
                }
            }
        };
    }

    /** {@inheritDoc} */
    @Override
    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException, InterruptedException {
        return showRevision(from, to, true);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> showRevision(ObjectId from, ObjectId to, Boolean useRawOutput)
            throws GitException, InterruptedException {
        ArgumentListBuilder args =
                new ArgumentListBuilder("log", "--full-history", "--no-abbrev", "--format=raw", "-M", "-m");
        if (useRawOutput) {
            args.add("--raw");
        }

        if (from != null) {
            args.add(from.name() + ".." + to.name());
        } else {
            args.add("-1", to.name());
        }

        StringWriter writer = new StringWriter();
        writer.write(launchCommand(args));
        return new ArrayList<>(Arrays.asList(writer.toString().split("\\n")));
    }

    /**
     * submoduleInit.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public void submoduleInit() throws GitException, InterruptedException {
        launchCommand("submodule", "init");
    }

    /** {@inheritDoc} */
    @Override
    public void addSubmodule(String remoteURL, String subdir) throws GitException, InterruptedException {
        launchCommand("submodule", "add", remoteURL, subdir);
    }

    /**
     * Sync submodule URLs
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public void submoduleSync() throws GitException, InterruptedException {
        // Check if git submodule has sync support.
        // Only available in git 1.6.1 and above
        launchCommand("submodule", "sync");
    }

    /**
     * Update submodules.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand} object.
     */
    @Override
    public SubmoduleUpdateCommand submoduleUpdate() {
        return new SubmoduleUpdateCommand() {
            private boolean recursive = false;
            private boolean remoteTracking = false;
            private boolean parentCredentials = false;
            private boolean shallow = false;
            private String ref = null;
            private Map<String, String> submodBranch = new HashMap<>();
            private Integer timeout;
            private Integer depth = 1;
            private int threads = 1;

            @Override
            public SubmoduleUpdateCommand recursive(boolean recursive) {
                this.recursive = recursive;
                return this;
            }

            @Override
            public SubmoduleUpdateCommand remoteTracking(boolean remoteTracking) {
                this.remoteTracking = remoteTracking;
                return this;
            }

            @Override
            public SubmoduleUpdateCommand parentCredentials(boolean parentCredentials) {
                this.parentCredentials = parentCredentials;
                return this;
            }

            @Override
            public SubmoduleUpdateCommand ref(String ref) {
                this.ref = ref;
                return this;
            }

            @Override
            public SubmoduleUpdateCommand useBranch(String submodule, String branchname) {
                this.submodBranch.put(submodule, branchname);
                return this;
            }

            @Override
            public SubmoduleUpdateCommand timeout(Integer timeout) {
                this.timeout = timeout;
                return this;
            }

            @Override
            public SubmoduleUpdateCommand shallow(boolean shallow) {
                this.shallow = shallow;
                return this;
            }

            @Override
            public SubmoduleUpdateCommand depth(Integer depth) {
                this.depth = depth;
                return this;
            }

            @Override
            public SubmoduleUpdateCommand threads(int threads) {
                this.threads = threads;
                return this;
            }

            /**
             * @throws GitException if executing the Git command fails
             * @throws InterruptedException if called methods throw same exception
             */
            @Override
            public void execute() throws GitException, InterruptedException {
                // Initialize the submodules to ensure that the git config
                // contains the URLs from .gitmodules.
                submoduleInit();

                ArgumentListBuilder args = new ArgumentListBuilder();
                args.add("submodule", "update");
                if (recursive) {
                    args.add("--init", "--recursive");
                }
                if (remoteTracking && isAtLeastVersion(1, 8, 2, 0)) {
                    args.add("--remote");

                    for (Map.Entry<String, String> entry : submodBranch.entrySet()) {
                        launchCommand(
                                "config",
                                "-f",
                                ".gitmodules",
                                "submodule." + entry.getKey() + ".branch",
                                entry.getValue());
                    }
                }
                if ((ref != null) && !ref.isEmpty()) {
                    File referencePath = new File(ref);
                    if (!referencePath.exists()) {
                        listener.getLogger().println("[WARNING] Reference path does not exist: " + ref);
                    } else if (!referencePath.isDirectory()) {
                        listener.getLogger().println("[WARNING] Reference path is not a directory: " + ref);
                    } else {
                        args.add("--reference", ref);
                    }
                }
                if (shallow) {
                    if (depth == null) {
                        depth = 1;
                    }
                    if (isAtLeastVersion(1, 8, 4, 0)) {
                        args.add("--depth=" + depth);
                    }
                }

                // We need to call submodule update for each configured
                // submodule. Note that we can't reliably depend on the
                // getSubmodules() since it is possible "HEAD" doesn't exist,
                // and we don't really want to recursively find all possible
                // submodules, just the ones for this super project. Thus,
                // loop through the config output and parse it for configured
                // modules.
                String cfgOutput = null;
                try {
                    // We might fail if we have no modules, so catch this
                    // exception and just return.
                    cfgOutput = launchCommand(
                            "config", "-f", ".gitmodules", "--get-regexp", SUBMODULE_REMOTE_PATTERN_CONFIG_KEY);
                } catch (GitException e) {
                    listener.error("No submodules found.");
                    return;
                }

                // Use a matcher to find each configured submodule name, and
                // then run the submodule update command with the provided
                // path.
                Pattern pattern = Pattern.compile(SUBMODULE_REMOTE_PATTERN_STRING, Pattern.MULTILINE);
                Matcher matcher = pattern.matcher(cfgOutput);

                List<Callable<String>> commands = new ArrayList<>();

                while (matcher.find()) {
                    ArgumentListBuilder perModuleArgs = args.clone();
                    String sModuleName = matcher.group(1);

                    // Find the URL for this submodule
                    URIish urIish = null;
                    try {
                        urIish = new URIish(getSubmoduleUrl(sModuleName));
                    } catch (URISyntaxException e) {
                        listener.error("Invalid repository for " + sModuleName);
                        throw new GitException("Invalid repository for " + sModuleName);
                    }

                    // Find credentials for this URL
                    StandardCredentials cred = credentials.get(urIish.toPrivateString());
                    if (parentCredentials) {
                        String parentUrl = getRemoteUrl(getDefaultRemote());
                        URIish parentUri = null;
                        try {
                            parentUri = new URIish(parentUrl);
                        } catch (URISyntaxException e) {
                            listener.error("Invalid URI for " + parentUrl);
                            throw new GitException("Invalid URI for " + parentUrl);
                        }
                        cred = credentials.get(parentUri.toPrivateString());
                    }
                    if (cred == null) {
                        cred = defaultCredentials;
                    }

                    // Find the path for this submodule
                    String sModulePath = getSubmodulePath(sModuleName);

                    perModuleArgs.add(sModulePath);
                    StandardCredentials finalCred = cred;
                    URIish finalUrIish = urIish;

                    commands.add(() ->
                            launchCommandWithCredentials(perModuleArgs, workspace, finalCred, finalUrIish, timeout));
                }

                new GitCommandsExecutor(threads, listener).invokeAll(commands);
            }
        };
    }

    /**
     * Reset submodules
     *
     * @param recursive if true, will recursively reset submodules (requires git&gt;=1.6.5)
     * @param hard if true, the --hard argument will be passed to submodule reset
     * @throws hudson.plugins.git.GitException if executing the git command fails
     * @throws java.lang.InterruptedException if git command interrupted
     */
    public void submoduleReset(boolean recursive, boolean hard) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("submodule", "foreach");
        if (recursive) {
            args.add("--recursive");
        }
        args.add("git reset" + (hard ? " --hard" : ""));

        launchCommand(args);
    }

    /**
     * {@inheritDoc}
     *
     * Cleans submodules
     */
    @Override
    public void submoduleClean(boolean recursive) throws GitException, InterruptedException {
        submoduleReset(true, true);
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("submodule", "foreach");
        if (recursive) {
            args.add("--recursive");
        }
        args.add("git clean -fdx");

        launchCommand(args);
    }

    /**
     * {@inheritDoc}
     *
     * Get submodule URL
     */
    @Override
    public @CheckForNull String getSubmoduleUrl(String name) throws GitException, InterruptedException {
        String result = launchCommand("config", "--get", "submodule." + name + ".url");
        return StringUtils.trim(firstLine(result));
    }

    /**
     * {@inheritDoc}
     *
     * Set submodule URL
     */
    @Override
    public void setSubmoduleUrl(String name, String url) throws GitException, InterruptedException {
        launchCommand("config", "submodule." + name + ".url", url);
    }

    /**
     * Get submodule path.
     *
     * @param name submodule name whose path is returned
     * @return path to submodule
     * @throws GitException on git error
     * @throws InterruptedException if interrupted
     */
    public @CheckForNull String getSubmodulePath(String name) throws GitException, InterruptedException {
        String result = launchCommand("config", "-f", ".gitmodules", "--get", "submodule." + name + ".path");
        return StringUtils.trim(firstLine(result));
    }

    /** {@inheritDoc} */
    @Override
    public @CheckForNull String getRemoteUrl(String name) throws GitException, InterruptedException {
        String result = launchCommand("config", "--get", "remote." + name + ".url");
        return StringUtils.trim(firstLine(result));
    }

    /** {@inheritDoc} */
    @Override
    public void setRemoteUrl(String name, String url) throws GitException, InterruptedException {
        launchCommand("config", "remote." + name + ".url", url);
    }

    /** {@inheritDoc} */
    @Override
    public void addRemoteUrl(String name, String url) throws GitException, InterruptedException {
        launchCommand("config", "--add", "remote." + name + ".url", url);
    }

    /** {@inheritDoc} */
    @Override
    public String getRemoteUrl(String name, String GIT_DIR) throws GitException, InterruptedException {
        final String remoteNameUrl = "remote." + name + ".url";
        String result;
        if (StringUtils.isBlank(GIT_DIR)) {
            /* Match JGitAPIImpl */
            result = launchCommand("config", "--get", remoteNameUrl);
        } else {
            final String dirArg = "--git-dir=" + GIT_DIR;
            result = launchCommand(dirArg, "config", "--get", remoteNameUrl);
        }
        String line = firstLine(result);
        if (line == null) {
            throw new GitException("No output from git config check for " + GIT_DIR);
        }
        return line.trim();
    }

    /** {@inheritDoc} */
    @Override
    public void setRemoteUrl(String name, String url, String GIT_DIR) throws GitException, InterruptedException {
        launchCommand("--git-dir=" + GIT_DIR, "config", "remote." + name + ".url", url);
    }

    /** {@inheritDoc} */
    @Override
    public String getDefaultRemote(String _default_) throws GitException, InterruptedException {
        BufferedReader rdr = new BufferedReader(new StringReader(launchCommand("remote")));

        List<String> remotes = new ArrayList<>();

        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                remotes.add(line);
            }
        } catch (IOException e) {
            throw new GitException("Error parsing remotes", e);
        }

        if (remotes.contains(_default_)) {
            return _default_;
        } else if (remotes.size() >= 1) {
            return remotes.get(0);
        } else {
            throw new GitException("No remotes found!");
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

    /** {@inheritDoc} */
    @Override
    public boolean isBareRepository(String GIT_DIR) throws GitException, InterruptedException {
        String ret;
        if ("".equals(GIT_DIR)) {
            ret = launchCommand("rev-parse", "--is-bare-repository");
        } else {
            String gitDir = "--git-dir=" + GIT_DIR;
            ret = launchCommand(gitDir, "rev-parse", "--is-bare-repository");
        }
        String line = StringUtils.trimToNull(ret);
        if (line == null) {
            throw new GitException("No output from bare repository check for " + GIT_DIR);
        }

        return !"false".equals(line);
    }

    /**
     * Returns true if this repository is configured as a shallow clone.
     * @return true if this repository is configured as a shallow clone
     */
    public boolean isShallowRepository() {
        return new File(workspace, pathJoin(".git", "shallow")).exists();
    }

    private String pathJoin(String a, String b) {
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
    @Override
    public void fixSubmoduleUrls(String remote, TaskListener listener) throws GitException, InterruptedException {
        boolean is_bare = true;

        URI origin;
        try {
            String url = getRemoteUrl(remote);
            if (url == null) {
                throw new GitException("remote." + remote + ".url not defined in workspace");
            }

            // ensure that any /.git ending is removed
            String gitEnd = pathJoin("", ".git");
            if (url.endsWith(gitEnd)) {
                url = url.substring(0, url.length() - gitEnd.length());
                // change the default detection value to NON-bare
                is_bare = false;
            }

            origin = new URI(url);
        } catch (URISyntaxException e) {
            // Sometimes the URI is of a form that we can't parse; like
            //   user@git.somehost.com:repository
            // In these cases, origin is null and it's best to just exit early.
            return;
        } catch (Exception e) {
            throw new GitException("Could not determine remote." + remote + ".url", e);
        }

        if (origin.getScheme() == null
                || ("file".equalsIgnoreCase(origin.getScheme())
                        && (origin.getHost() == null || "".equals(origin.getHost())))) {
            // The uri is a local path, so we will test to see if it is a bare
            // repository...
            List<String> paths = new ArrayList<>();
            paths.add(origin.getPath());
            paths.add(pathJoin(origin.getPath(), ".git"));

            for (String path : paths) {
                try {
                    is_bare = isBareRepository(path);
                    break; // we can break already if we don't have an exception
                } catch (GitException e) {
                }
            }
        }

        if (!is_bare) {
            try {
                List<IndexEntry> submodules = getSubmodules("HEAD");

                for (IndexEntry submodule : submodules) {
                    // First fix the URL to the submodule inside the super-project
                    String sUrl = pathJoin(origin.getPath(), submodule.getFile());
                    setSubmoduleUrl(submodule.getFile(), sUrl);

                    // Second, if the submodule already has been cloned, fix its own
                    // url...
                    String subGitDir = pathJoin(submodule.getFile(), ".git");

                    /* it is possible that the submodule does not exist yet
                     * since we wait until after checkout to do 'submodule
                     * udpate' */
                    if (hasGitRepo(subGitDir) && !"".equals(getRemoteUrl("origin", subGitDir))) {
                        setRemoteUrl("origin", sUrl, subGitDir);
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

    /**
     * {@inheritDoc}
     *
     * Set up submodule URLs so that they correspond to the remote pertaining to
     * the revision that has been checked out.
     */
    @Override
    public void setupSubmoduleUrls(Revision rev, TaskListener listener) throws GitException, InterruptedException {
        String remote = null;

        // try to locate the remote repository from where this commit came from
        // (by using the heuristics that the branch name, if available, contains the remote name)
        // if we can figure out the remote, the other setupSubmoduleUrls method
        // look at its URL, and if it's a non-bare repository, we attempt to retrieve modules
        // from this checked out copy.
        //
        // the idea is that you have something like tree-structured repositories: at the root you have corporate central
        // repositories that you
        // ultimately push to, which all .gitmodules point to, then you have intermediate team local repository,
        // which is assumed to be a non-bare repository (say, a checked out copy on a shared server accessed via SSH)
        //
        // the abovementioned behaviour of the Git plugin makes it pick up submodules from this team local repository,
        // not the corporate central.
        //
        // (Kohsuke: I have a bit of hesitation/doubt about such a behaviour change triggered by seemingly indirect
        // evidence of whether the upstream is bare or not (not to mention the fact that you can't reliably
        // figure out if the repository is bare or not just from the URL), but that's what apparently has been
        // implemented
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

                if (slash != -1) {
                    remote = getDefaultRemote(b.substring(0, slash));
                }
            }

            if (remote != null) {
                break;
            }
        }

        if (remote == null) {
            remote = getDefaultRemote();
        }

        if (remote != null) {
            setupSubmoduleUrls(remote, listener);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void tag(String tagName, String comment) throws GitException, InterruptedException {
        tagName = tagName.replace(' ', '_');
        try {
            launchCommand("tag", "-a", "-f", "-m", comment, tagName);
        } catch (GitException e) {
            throw new GitException("Could not apply tag " + tagName, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void appendNote(String note, String namespace) throws GitException, InterruptedException {
        createNote(note, namespace, "append");
    }

    /** {@inheritDoc} */
    @Override
    public void addNote(String note, String namespace) throws GitException, InterruptedException {
        createNote(note, namespace, "add");
    }

    private Path createTempFileInSystemDir(String prefix, String suffix) throws IOException {
        if (isWindows()) {
            return Files.createTempFile(prefix, suffix);
        }
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        FileAttribute<Set<PosixFilePermission>> fileAttribute = PosixFilePermissions.asFileAttribute(ownerOnly);
        return Files.createTempFile(prefix, suffix, fileAttribute);
    }

    /**
     * Create temporary file that is aware of the specific limitations
     * of command line git.
     *
     * For example, no temporary file name (Windows or Unix) may
     * include a percent sign in its path because ssh uses the percent
     * sign character as the start of token indicator for token
     * expansion.
     *
     * As another example, windows temporary files may not contain a
     * space, an open parenthesis, or a close parenthesis anywhere in
     * their path, otherwise they break ssh argument passing through
     * the GIT_SSH or SSH_ASKPASS environment variable.
     *
     * Package protected for testing.  Not to be used outside this class
     *
     * @param prefix file name prefix for the generated temporary file (will be preceeded by "jenkins-gitclient-")
     * @param suffix file name suffix for the generated temporary file
     * @return temporary file
     * @throws IOException on error
     */
    Path createTempFile(String prefix, String suffix) throws IOException {
        String common_prefix = "jenkins-gitclient-";
        if (prefix == null) {
            prefix = common_prefix;
        } else {
            prefix = common_prefix + prefix;
        }

        if (workspace == null) {
            return createTempFileInSystemDir(prefix, suffix);
        }
        File workspaceTmp = new File(workspace.getAbsolutePath() + "@tmp");
        if (!workspaceTmp.isDirectory() && !workspaceTmp.mkdirs()) {
            if (!workspaceTmp.isDirectory()) {
                return createTempFileInSystemDir(prefix, suffix);
            }
        }
        Path tmpPath = Paths.get(workspaceTmp.getAbsolutePath());
        if (workspaceTmp.getAbsolutePath().contains("%")) {
            // Avoid ssh token expansion on all platforms
            return createTempFileInSystemDir(prefix, suffix);
        }
        if (isWindows()) {
            /* Windows git fails its call to GIT_SSH if its absolute
             * path contains a space or parenthesis or pipe or question mark or asterisk.
             * Use system temp dir instead of workspace temp dir.
             */
            if (workspaceTmp.getAbsolutePath().matches(".*[ ()|?*].*")) {
                return createTempFileInSystemDir(prefix, suffix);
            }
            return Files.createTempFile(tmpPath, prefix, suffix);
        } else if (workspaceTmp.getAbsolutePath().contains("%")) {
            /* Avoid Linux expansion of % in ssh arguments */
            return createTempFileInSystemDir(prefix, suffix);
        }
        // Unix specific
        if (workspaceTmp.getAbsolutePath().contains("`")) {
            // Avoid backquote shell expansion
            return createTempFileInSystemDir(prefix, suffix);
        }
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        FileAttribute<Set<PosixFilePermission>> fileAttribute = PosixFilePermissions.asFileAttribute(ownerOnly);
        return Files.createTempFile(tmpPath, prefix, suffix, fileAttribute);
    }

    private void deleteTempFile(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "temp file " + tempFile + " not deleted", e);
            }
        }
    }

    private void createNote(String note, String namespace, String command) throws GitException, InterruptedException {
        Path msg = null;
        try {
            msg = createTempFile("git-note", ".txt");
            try (Writer w = Files.newBufferedWriter(msg, StandardCharsets.UTF_8)) {
                w.write(note);
            }
            launchCommand(
                    "notes",
                    "--ref=" + namespace,
                    command,
                    "-F",
                    msg.toAbsolutePath().toString());
        } catch (IOException | GitException e) {
            throw new GitException("Could not apply note " + note, e);
        } finally {
            deleteTempFile(msg);
        }
    }

    /**
     * Launch command using the workspace as working directory
     *
     * @param args arguments to the command
     * @return command output
     * @throws hudson.plugins.git.GitException if launched command fails
     * @throws java.lang.InterruptedException if interrupted
     */
    public String launchCommand(ArgumentListBuilder args) throws GitException, InterruptedException {
        return launchCommandIn(args, workspace);
    }

    /**
     * Launch command using the workspace as working directory
     *
     * @param args command argumnents
     * @return command output
     * @throws hudson.plugins.git.GitException on failure
     * @throws java.lang.InterruptedException if interrupted
     */
    public String launchCommand(String... args) throws GitException, InterruptedException {
        return launchCommand(new ArgumentListBuilder(args));
    }

    private String launchCommandWithCredentials(
            ArgumentListBuilder args, File workDir, StandardCredentials credentials, @NonNull String url)
            throws GitException, InterruptedException {
        try {
            return launchCommandWithCredentials(args, workDir, credentials, new URIish(url));
        } catch (URISyntaxException e) {
            throw new GitException("Invalid URL " + url, e);
        }
    }

    private String launchCommandWithCredentials(
            ArgumentListBuilder args, File workDir, StandardCredentials credentials, @NonNull URIish url)
            throws GitException, InterruptedException {
        return launchCommandWithCredentials(args, workDir, credentials, url, TIMEOUT);
    }

    private String launchCommandWithCredentials(
            ArgumentListBuilder args,
            File workDir,
            StandardCredentials credentials,
            @NonNull URIish url,
            Integer timeout)
            throws GitException, InterruptedException {

        Path key = null;
        Path ssh = null;
        Path askpass = null;
        Path usernameFile = null;
        Path passwordFile = null;
        Path passphrase = null;
        Path knownHostsTemp = null;
        EnvVars env = environment;
        if (!PROMPT_FOR_AUTHENTICATION && isAtLeastVersion(2, 3, 0, 0)) {
            env = new EnvVars(env);
            env.put("GIT_TERMINAL_PROMPT", "false"); // Don't prompt for auth from command line git
            if (isWindows()) {
                env.put("GCM_INTERACTIVE", "false"); // Don't prompt for auth from git credentials manager for windows
            }
        }
        try {
            if (credentials instanceof SSHUserPrivateKey) {
                SSHUserPrivateKey sshUser = (SSHUserPrivateKey) credentials;
                listener.getLogger().println("using GIT_SSH to set credentials " + sshUser.getDescription());

                key = createSshKeyFile(sshUser);
                // Prefer url username if set, OpenSSH 7.7 argument precedence change
                // See JENKINS-50573 for details
                String userName = url.getUser();
                if (userName == null) {
                    userName = sshUser.getUsername();
                }
                passphrase = createPassphraseFile(sshUser);
                knownHostsTemp = createTempFile("known_hosts", "");
                if (launcher.isUnix()) {
                    ssh = createUnixGitSSH(key, userName, knownHostsTemp);
                    askpass = createUnixSshAskpass(sshUser, passphrase);
                } else {
                    ssh = createWindowsGitSSH(key, userName, knownHostsTemp);
                    askpass = createWindowsSshAskpass(sshUser, passphrase);
                }

                env = new EnvVars(env);
                env.put("GIT_SSH", ssh.toAbsolutePath().toString());
                env.put("GIT_SSH_VARIANT", "ssh");
                env.put("SSH_ASKPASS", askpass.toAbsolutePath().toString());

                // supply a dummy value for DISPLAY if not already present
                // or else ssh will not invoke SSH_ASKPASS
                if (!env.containsKey("DISPLAY")) {
                    env.put("DISPLAY", ":");
                }

            } else if (credentials instanceof StandardUsernamePasswordCredentials) {
                StandardUsernamePasswordCredentials userPass = (StandardUsernamePasswordCredentials) credentials;
                listener.getLogger().println("using GIT_ASKPASS to set credentials " + userPass.getDescription());

                usernameFile = createUsernameFile(userPass);
                passwordFile = createPasswordFile(userPass);
                if (launcher.isUnix()) {
                    askpass = createUnixStandardAskpass(userPass, usernameFile, passwordFile);
                } else {
                    askpass = createWindowsStandardAskpass(userPass, usernameFile, passwordFile);
                }

                env = new EnvVars(env);
                env.put("GIT_ASKPASS", askpass.toAbsolutePath().toString());
                env.put("SSH_ASKPASS", askpass.toAbsolutePath().toString());
            }

            if ("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme())) {
                if (proxy != null) {
                    boolean shouldProxy = true;
                    for (Pattern p : proxy.getNoProxyHostPatterns()) {
                        if (p.matcher(url.getHost()).matches()) {
                            shouldProxy = false;
                            break;
                        }
                    }
                    if (shouldProxy) {
                        env = new EnvVars(env);
                        listener.getLogger().println("Setting http proxy: " + proxy.name + ":" + proxy.port);
                        String userInfo = null;
                        if (StringUtils.isNotEmpty(proxy.getUserName())) {
                            userInfo = proxy.getUserName();
                            if (StringUtils.isNotEmpty(proxy.getPassword())) {
                                userInfo += ":" + proxy.getSecretPassword();
                            }
                        }
                        try {
                            URI http_proxy = new URI("http", userInfo, proxy.name, proxy.port, null, null, null);
                            env.put("http_proxy", http_proxy.toString());
                            env.put("https_proxy", http_proxy.toString());
                        } catch (URISyntaxException ex) {
                            throw new GitException("Failed to create http proxy uri", ex);
                        }
                    }
                }
            }

            return launchCommandIn(args, workDir, env, timeout);
        } catch (IOException e) {
            throw new GitException("Failed to setup credentials", e);
        } finally {
            deleteTempFile(key);
            deleteTempFile(ssh);
            deleteTempFile(askpass);
            deleteTempFile(passphrase);
            deleteTempFile(usernameFile);
            deleteTempFile(passwordFile);
            deleteTempFile(knownHostsTemp);
        }
    }

    @SuppressFBWarnings(
            value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
            justification =
                    "Path operations below intentionally use absolute '/usr/bin/chcon' and '/sys/fs/selinux/enforce' and '/proc/self/attr/current' at this time (as delivered in relevant popular Linux distros)")
    private Boolean fixSELinuxLabel(Path key, String label) {
        // returning false means chcon was tried and failed,
        // maybe caller needs to retry with other logic
        // true means all ok, including nothing needs to be done
        if (!launcher.isUnix()) {
            return true;
        }

        // JENKINS-64913: SELinux Enforced mode forbids SSH client to read
        // "untrusted" key files.
        // Check that tools exist and then if SELinux subsystem is activated
        // Otherwise calling the tools just pollutes build log with errors
        if (Files.isExecutable(Paths.get("/usr/bin/chcon"))) {
            // SELinux may actually forbid us to read system paths, so
            // there are a couple of ways to try checking if it is enabled
            // (whether this run needs to worry about security labels) and
            // we genuinely do not care if we failed to probe those points

            // Keep track which "clues" indicate that SELinux currently is
            // a force that should be reckoned with, on this host now:
            Boolean clue_proc = false;
            Boolean clue_sysfs = false;
            // Boolean clue_ls = false;

            try {
                // A process should always have rights to inspect itself, but
                // on some systems even this read returns "Invalid argument"
                if (Files.isRegularFile(Paths.get("/proc/self/attr/current"))) {
                    String s;
                    try (BufferedReader br =
                            Files.newBufferedReader(Paths.get("/proc/self/attr/current"), StandardCharsets.UTF_8)) {
                        s = br.readLine();
                    }
                    if ("unconfined".equals(s)) {
                        return true;
                    }
                    if ("kernel".equals(s)) {
                        return true;
                    }
                    if (s.contains(":")) {
                        // Note that SELinux may be enabled but not enforcing,
                        // if we can check for that - we bail below
                        clue_proc = true;
                    }
                }
            } catch (SecurityException | IOException e) {
            }

            try {
                if (!Files.isDirectory(Paths.get("/sys/fs/selinux"))) {
                    // Assuming that lack of rights to read this is an
                    // exception caught below, not a false return here?
                    return true;
                }

                if (Files.isRegularFile(Paths.get("/sys/fs/selinux/enforce"))) {
                    String s;
                    try (BufferedReader br =
                            Files.newBufferedReader(Paths.get("/sys/fs/selinux/enforce"), StandardCharsets.UTF_8)) {
                        s = br.readLine();
                    }
                    if ("0".equals(s)) {
                        // Subsystem exists, but told to not get into way at the moment
                        return true;
                    }
                    if ("1".equals(s)) {
                        clue_sysfs = true;
                    }
                }
            } catch (SecurityException | IOException e) {
            }

            // If we are here, there were no clear clues about SELinux *not*
            // being a possible problem for the current run. We might want
            // to check `ls -Z` output for success (flag known) and for the
            // label data in it: using a filesystem without labels (applied
            // or supported) would get complaints from 'chcon' trying to fix
            // just a component of the label below. Unfortunately, different
            // toolkits (GNU/busybox/...) and OSes vary in outputs of that.

            // Here we assume a SELinux capable system if the tool exists,
            // and no clear indications were seen that the security subsystem
            // is currently disarmed, so label the key file for SSH to use.
            // (Can complain if it is unlabeled.)
            // TOTHINK: Should this be further riddled with sanity checks
            // (uname, PATH leading to "chcon", etc)?

            if (clue_proc) {
                listener.getLogger().println("[INFO] Currently running in a labeled security context");
            }

            if (clue_sysfs) {
                listener.getLogger().println("[INFO] Currently SELinux is 'enforcing' on the host");
            }

            if (!clue_sysfs && !clue_proc) { // && !clue_ls
                listener.getLogger()
                        .println("[INFO] SELinux is present on the host "
                                + "and we could not confirm that it does not apply actively: "
                                + "will try to relabel temporary files now; this may complain "
                                + "if context labeling not applicable after all");
            }

            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add("/usr/bin/chcon");
            args.add("--type=" + label);
            args.add(key.toAbsolutePath().toString());
            Launcher.ProcStarter p = launcher.launch().cmds(args.toCommandArray());
            int status = -1;
            String stdout = "";
            String stderr = "";
            String command = StringUtils.join(args.toCommandArray(), " ");

            try {
                // JENKINS-13356: capture stdout and stderr separately
                ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
                ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();

                p.stdout(stdoutStream).stderr(stderrStream);
                listener.getLogger().println(" > " + command);
                // Should be much faster than 1 min :)
                status = p.start().joinWithTimeout(1, TimeUnit.MINUTES, listener);

                stdout = stdoutStream.toString(encoding);
                stderr = stderrStream.toString(encoding);
            } catch (Throwable e) {
                /* Simple erroneous non-zero exit code does not get here.
                 * True exceptions are better handled (reported) ASAP, without
                 * caching into failureClues like the noisy log further below.
                 */
                listener.getLogger()
                        .println("Error performing chcon helper command for SELinux: " + command + " :\n" + e);
                if (status <= 0) {
                    status = 126;
                } // cause the message and false return below
            }
            if (status > 0) {
                failureClues.put(
                        Instant.now(),
                        "[WARNING] Failed (" + status + ") performing chcon helper command for SELinux:\n >>> "
                                + command + "\n" + (stdout.equals("") ? "" : ("=== STDOUT:\n" + stdout + "\n====\n"))
                                + (stderr.equals("") ? "" : ("=== STDERR:\n" + stderr + "\n====\n"))
                                + "IMPACT: if SELinux is enabled, access to temporary key file may be denied for git+ssh later");
                return false;
            }
        }
        return true;
    }

    private void reportFailureClues() {
        if (!failureClues.isEmpty()) {
            listener.getLogger()
                    .println("ERROR: Git command failed, and previous operations logged the following error details:");
            for (Map.Entry<Instant, String> entry : failureClues.entrySet()) {
                listener.getLogger().println("[" + entry.getKey().toString() + "]" + entry.getValue() + "\n");
            }
            failureClues = new TreeMap(); // Flush to collect new errors and not report twice
        }
    }

    private Path createSshKeyFile(SSHUserPrivateKey sshUser) throws IOException {
        Path key = createTempFile("ssh", ".key");
        try (BufferedWriter w = Files.newBufferedWriter(key, Charset.forName(encoding))) {
            List<String> privateKeys = sshUser.getPrivateKeys();
            for (String s : privateKeys) {
                w.write(s);
                w.newLine();
            }
        }
        if (launcher.isUnix()) {
            Files.setPosixFilePermissions(key, Collections.singleton(PosixFilePermission.OWNER_READ));
            fixSELinuxLabel(key, "ssh_home_t");
        } else {
            fixSshKeyOnWindows(key);
        }

        return key;
    }

    /* package protected for testability */
    void fixSshKeyOnWindows(Path key) throws GitException {
        if (launcher.isUnix()) {
            return;
        }

        AclFileAttributeView fileAttributeView = Files.getFileAttributeView(key, AclFileAttributeView.class);
        if (fileAttributeView == null) {
            return;
        }

        try {
            UserPrincipal userPrincipal = fileAttributeView.getOwner();
            AclEntry aclEntry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(userPrincipal)
                    .setPermissions(ACL_ENTRY_PERMISSIONS)
                    .build();
            fileAttributeView.setAcl(Collections.singletonList(aclEntry));
        } catch (IOException | UnsupportedOperationException e) {
            throw new GitException("Error updating file permission for \"" + key.toAbsolutePath() + "\"", e);
        }
    }

    /* Arguments that will be inserted into every command line git
     * call immediately after the "git" command.  Intended to be used
     * for specific testing situations internal to the plugin.
     *
     * NOT INTENDED FOR USE OUTSIDE THE PLUGIN.
     */
    @NonNull
    private List<String> extraGitCommandArguments = Collections.emptyList();

    /* Define arguments that will be inserted into every command line git
     * call immediately after the "git" command.  Intended to be used
     * for specific testing situations internal to the plugin.
     *
     * NOT INTENDED FOR USE OUTSIDE THE PLUGIN.
     */
    private void setExtraGitCommandArguments(@NonNull List<String> args) {
        extraGitCommandArguments = new ArrayList<>(args);
    }

    /* package protected for use in tests.
     *
     * Allow local git clones to use the file:// protocol by setting
     * protocol.file.allow=always on the git command line.
     *
     * Command line git 2.38.1 and patches to earlier versions
     * disallow local git submodule cloning with the file:// protocol.
     * The change resolves a security issue but that security issue is
     * not a threat to these tests.
     *
     * NOT INTENDED FOR USE OUTSIDE THE PLUGIN.
     */
    void allowFileProtocol() {
        setExtraGitCommandArguments(Arrays.asList("-c", "protocol.file.allow=always"));
    }

    /* Escape all double quotes in filename, then surround filename in double quotes.
     * Only useful to prepare filename for reference from a DOS batch file.
     */
    private String windowsArgEncodeFileName(String filename) {
        if (filename.contains("\"")) {
            filename = filename.replace("\"", "^\"");
        }
        return "\"" + filename + "\"";
    }

    private Path createWindowsSshAskpass(SSHUserPrivateKey sshUser, @NonNull Path passphrase) throws IOException {
        Path ssh = Files.createTempFile("pass", ".bat");
        try (BufferedWriter w = Files.newBufferedWriter(ssh, Charset.forName(encoding))) {
            // avoid echoing command as part of the password
            w.write("@echo off");
            w.newLine();
            w.write("type "
                    + windowsArgEncodeFileName(passphrase.toAbsolutePath().toString()));
            w.newLine();
        }
        ssh.toFile().setExecutable(true, true);
        return ssh;
    }

    /* Escape all single quotes in filename, then surround filename in single quotes.
     * Only useful to prepare filename for reference from a shell script.
     */
    private String unixArgEncodeFileName(String filename) {
        if (filename.contains("'")) {
            filename = filename.replace("'", "'\\''");
        }
        return "'" + filename + "'";
    }

    private Path createUnixSshAskpass(SSHUserPrivateKey sshUser, @NonNull Path passphrase) throws IOException {
        Path ssh = createTempFile("pass", ".sh");
        try (BufferedWriter w = Files.newBufferedWriter(ssh, Charset.forName(encoding))) {
            w.write("#!/bin/sh");
            w.newLine();
            w.write("cat " + unixArgEncodeFileName(passphrase.toAbsolutePath().toString()));
            w.newLine();
        }
        return createNonBusyExecutable(ssh);
    }

    private Path createWindowsStandardAskpass(
            StandardUsernamePasswordCredentials creds, Path usernameFile, Path passwordFile) throws IOException {
        Path askpass = createTempFile("pass", ".bat");
        try (BufferedWriter w = Files.newBufferedWriter(askpass, Charset.forName(encoding))) {
            w.write("@set arg=%~1");
            w.newLine();
            w.write("@if (%arg:~0,8%)==(Username) type "
                    + windowsArgEncodeFileName(usernameFile.toAbsolutePath().toString()));
            w.newLine();
            w.write("@if (%arg:~0,8%)==(Password) type "
                    + windowsArgEncodeFileName(passwordFile.toAbsolutePath().toString()));
            w.newLine();
        }
        askpass.toFile().setExecutable(true, true);
        return askpass;
    }

    private Path createUnixStandardAskpass(
            StandardUsernamePasswordCredentials creds, Path usernameFile, Path passwordFile) throws IOException {
        Path askpass = createTempFile("pass", ".sh");
        try (BufferedWriter w = Files.newBufferedWriter(askpass, Charset.forName(encoding))) {
            w.write("#!/bin/sh");
            w.newLine();
            w.write("case \"$1\" in");
            w.newLine();
            w.write("Username*) cat "
                    + unixArgEncodeFileName(usernameFile.toAbsolutePath().toString()) + " ;;");
            w.newLine();
            w.write("Password*) cat "
                    + unixArgEncodeFileName(passwordFile.toAbsolutePath().toString()) + " ;;");
            w.newLine();
            w.write("esac");
            w.newLine();
        }
        return createNonBusyExecutable(askpass);
    }

    private Path createPassphraseFile(SSHUserPrivateKey sshUser) throws IOException {
        Charset charset = computeCredentialFileCharset("passphrase", StandardCharsets.UTF_8);
        Path passphraseFile = createTempFile("phrase", ".txt");
        try (BufferedWriter w = Files.newBufferedWriter(passphraseFile, charset)) {
            w.write(Secret.toString(sshUser.getPassphrase()));
            w.newLine();
        }
        return passphraseFile;
    }

    private Path createUsernameFile(StandardUsernamePasswordCredentials userPass) throws IOException {
        Charset charset = computeCredentialFileCharset("name", StandardCharsets.UTF_8);
        Path usernameFile = createTempFile("username", ".txt");
        try (BufferedWriter w = Files.newBufferedWriter(usernameFile, charset)) {
            w.write(userPass.getUsername());
            w.newLine();
        }
        return usernameFile;
    }

    private Path createPasswordFile(StandardUsernamePasswordCredentials userPass) throws IOException {
        Charset charset = computeCredentialFileCharset("password", StandardCharsets.UTF_8);
        Path passwordFile = createTempFile("password", ".txt");
        try (BufferedWriter w = Files.newBufferedWriter(passwordFile, charset)) {
            w.write(Secret.toString(userPass.getPassword()));
            w.newLine();
        }
        return passwordFile;
    }

    private Charset computeCredentialFileCharset(String context, Charset defaultValue) {
        String property = CliGitAPIImpl.class.getName() + ".user." + context + ".file.encoding";
        if (isZos() && System.getProperty(property) != null) {
            Charset charset = Charset.forName(System.getProperty(property));
            listener.getLogger().println("Using " + context + " charset '" + charset + "'");
            return charset;
        }
        return defaultValue;
    }

    private String getPathToExe(String userGitExe) {
        userGitExe = userGitExe.toLowerCase(Locale.ENGLISH); // Avoid the Turkish 'i' conversion

        String cmd;
        String exe;
        if (userGitExe.endsWith(".exe")) {
            cmd = userGitExe.replace(".exe", ".cmd");
            exe = userGitExe;
        } else if (userGitExe.endsWith(".cmd")) {
            cmd = userGitExe;
            exe = userGitExe.replace(".cmd", ".exe");
        } else {
            cmd = userGitExe + ".cmd";
            exe = userGitExe + ".exe";
        }

        String[] pathDirs = System.getenv("PATH").split(File.pathSeparator);

        for (String pathDir : pathDirs) {
            File exeFile = new File(pathDir, exe);
            if (exeFile.exists()) {
                return exeFile.getAbsolutePath();
            }
            File cmdFile = new File(pathDir, cmd);
            if (cmdFile.exists()) {
                return cmdFile.getAbsolutePath();
            }
        }

        File userGitFile = new File(userGitExe);
        if (userGitFile.exists()) {
            return userGitFile.getAbsolutePath();
        }

        return null;
    }

    private File getFileFromEnv(String envVar, String suffix) {
        String envValue = System.getenv(envVar);
        if (envValue == null) {
            return null;
        }
        return new File(envValue + suffix);
    }

    private File getSSHExeFromGitExeParentDir(String userGitExe) {
        String parentPath = new File(userGitExe).getParent();
        if (parentPath == null) {
            return null;
        }
        return new File(parentPath + "\\ssh.exe");
    }
    /**
     * Returns an executable file of ssh installed in Windows
     *
     * @return File The ssh executable file {@link java.io.File}
     **/
    public File getSSHExecutable() {
        // First check the GIT_SSH environment variable
        File sshexe = getFileFromEnv("GIT_SSH", "");
        if (sshexe != null && sshexe.exists()) {
            return sshexe;
        }

        // Check Program Files
        sshexe = getFileFromEnv("ProgramFiles", "\\Git\\bin\\ssh.exe");
        if (sshexe != null && sshexe.exists()) {
            return sshexe;
        }
        sshexe = getFileFromEnv("ProgramFiles", "\\Git\\usr\\bin\\ssh.exe");
        if (sshexe != null && sshexe.exists()) {
            return sshexe;
        }

        // Check Program Files(x86) for 64 bit computer
        sshexe = getFileFromEnv("ProgramFiles(x86)", "\\Git\\bin\\ssh.exe");
        if (sshexe != null && sshexe.exists()) {
            return sshexe;
        }
        sshexe = getFileFromEnv("ProgramFiles(x86)", "\\Git\\usr\\bin\\ssh.exe");
        if (sshexe != null && sshexe.exists()) {
            return sshexe;
        }

        // Search for an ssh.exe near the git executable.
        sshexe = getSSHExeFromGitExeParentDir(gitExe);
        if (sshexe != null && sshexe.exists()) {
            return sshexe;
        }

        // Search for git on the PATH, then look near it
        String gitPath = getPathToExe(gitExe);
        if (gitPath != null) {
            sshexe = getSSHExeFromGitExeParentDir(
                    gitPath.replace("/bin/", "/usr/bin/").replace("\\bin\\", "\\usr\\bin\\"));
            if (sshexe != null && sshexe.exists()) {
                return sshexe;
            }
            // In case we are using msysgit from the cmd directory
            // instead of the bin directory, replace cmd with bin in
            // the path while trying to find ssh.exe.
            sshexe = getSSHExeFromGitExeParentDir(
                    gitPath.replace("/cmd/", "/bin/").replace("\\cmd\\", "\\bin\\"));
            if (sshexe != null && sshexe.exists()) {
                return sshexe;
            }
            sshexe = getSSHExeFromGitExeParentDir(
                    gitPath.replace("/cmd/", "/usr/bin/").replace("\\cmd\\", "\\usr\\bin\\"));
            if (sshexe != null && sshexe.exists()) {
                return sshexe;
            }
            sshexe = getSSHExeFromGitExeParentDir(
                    gitPath.replace("/mingw64/", "/").replace("\\mingw64\\", "\\"));
            if (sshexe != null && sshexe.exists()) {
                return sshexe;
            }
            sshexe = getSSHExeFromGitExeParentDir(
                    gitPath.replace("/mingw64/bin/", "/usr/bin/").replace("\\mingw64\\bin\\", "\\usr\\bin\\"));
            if (sshexe != null && sshexe.exists()) {
                return sshexe;
            }
        }

        throw new RuntimeException(
                "ssh executable not found. The git plugin only supports official git client https://git-scm.com/download/win");
    }

    private Path createWindowsGitSSH(Path key, String user, Path knownHosts) throws IOException {
        Path ssh = createTempFile("ssh", ".bat");

        File sshexe = getSSHExecutable();

        try (BufferedWriter w = Files.newBufferedWriter(ssh, Charset.forName(encoding))) {
            w.write("@echo off");
            w.newLine();
            w.write("\"" + sshexe.getAbsolutePath() + "\" -i \"" + key.toAbsolutePath() + "\" -l \"" + user + "\" "
                    + getHostKeyFactory().forCliGit(listener).getVerifyHostKeyOption(knownHosts) + " %* ");
            w.newLine();
        }
        ssh.toFile().setExecutable(true, true);
        return ssh;
    }

    private Path createUnixGitSSH(Path key, String user, Path knownHosts) throws IOException {
        Path ssh = createTempFile("ssh", ".sh");
        try (BufferedWriter w = Files.newBufferedWriter(ssh, Charset.forName(encoding))) {
            w.write("#!/bin/sh");
            w.newLine();
            // ${SSH_ASKPASS} might be ignored if ${DISPLAY} is not set
            w.write("if [ -z \"${DISPLAY}\" ]; then");
            w.newLine();
            w.write("  DISPLAY=:123.456");
            w.newLine();
            w.write("  export DISPLAY");
            w.newLine();
            w.write("fi");
            w.newLine();
            w.write("ssh -i \"" + key.toAbsolutePath() + "\" -l \"" + user + "\" "
                    + getHostKeyFactory().forCliGit(listener).getVerifyHostKeyOption(knownHosts) + " \"$@\"");
            w.newLine();
        }
        return createNonBusyExecutable(ssh);
    }

    private Path createNonBusyExecutable(Path p) throws IOException {
        p.toFile().setExecutable(true, true);
        Path p_copy = Paths.get(p.toString() + "-copy");

        // JENKINS-48258 git client plugin occasionally fails with "text file busy" error
        // The following creates a copy of the generated file and deletes the original
        // In case of a failure return the original and delete the copy
        // The "cp" command prevents having an open write file handle in the JVM process.
        // Otherwise an unrelated thread of the JVM may fork a new process and inherits the open
        // write file handle, which then leads to the "Text file busy" issue.
        String fromLocation = p.toString();
        String toLocation = p_copy.toString();
        // Copying ssh file
        // fixSELinuxLabel(ssh, "ssh_exec_t");
        boolean isCopied = false;
        try {
            new ProcessBuilder("cp", fromLocation, toLocation).start().waitFor();
            isCopied = true;
            p_copy.toFile().setExecutable(true, true);
            // fixSELinuxLabel(ssh_copy, "ssh_exec_t");
            // Deleting original file
            deleteTempFile(p);
        } catch (InterruptedException ie) {
            // Delete the copied file in case of failure
            if (isCopied) {
                deleteTempFile(p_copy);
            }
            // Previous operation failed. Return original file
            return p;
        }
        return p_copy;
    }

    private String launchCommandIn(ArgumentListBuilder args, File workDir) throws GitException, InterruptedException {
        return launchCommandIn(args, workDir, environment);
    }

    private String launchCommandIn(ArgumentListBuilder args, File workDir, EnvVars env)
            throws GitException, InterruptedException {
        return launchCommandIn(args, workDir, environment, TIMEOUT);
    }

    @SuppressFBWarnings(
            value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE",
            justification = "earlier readStderr()/readStdout() call prevents null return")
    private String readProcessIntoString(Proc process, String encoding, boolean useStderr) throws IOException {
        if (useStderr) {
            /* process.getStderr reference is the findbugs warning to be suppressed */
            return IOUtils.toString(process.getStderr(), encoding);
        }
        /* process.getStdout reference is the findbugs warning to be suppressed */
        return IOUtils.toString(process.getStdout(), encoding);
    }

    private String launchCommandIn(ArgumentListBuilder args, File workDir, EnvVars env, Integer timeout)
            throws GitException, InterruptedException {

        EnvVars freshEnv = new EnvVars(env);
        // If we don't have credentials, but the requested URL requires them,
        // it is possible for Git to hang forever waiting for interactive
        // credential input. Prevent this by setting GIT_ASKPASS to "echo"
        // if we haven't already set it.
        if (!env.containsKey("GIT_ASKPASS")) {
            freshEnv.put("GIT_ASKPASS", "echo");
        }
        /* Prepend extra git command line arguments if any */
        if (!extraGitCommandArguments.isEmpty()) {
            args = args.prepend(extraGitCommandArguments.toArray(new String[0]));
        }
        String command = gitExe + " " + StringUtils.join(args.toCommandArray(), " ");
        try {
            args.prepend(gitExe);
            if (CALL_SETSID && launcher.isUnix() && env.containsKey("GIT_SSH") && env.containsKey("DISPLAY")) {
                /* Detach from controlling terminal for git calls with ssh authentication */
                /* GIT_SSH won't call the passphrase prompt script unless detached from controlling terminal */
                args.prepend("setsid");
            }
            int usedTimeout = timeout == null ? TIMEOUT : timeout;
            listener.getLogger().println(" > " + command + TIMEOUT_LOG_PREFIX + usedTimeout);

            Launcher.ProcStarter p =
                    launcher.launch().cmds(args.toCommandArray()).envs(freshEnv);

            if (workDir != null) {
                p.pwd(workDir);
            }

            int status;
            String stdout;
            String stderr;

            if (isZos()) {
                // Another behavior on z/OS required due to the race condition happening during transcoding of charset
                // in
                // EBCDIC code page if CopyThread is used on IBM z/OS Java. For unclear reason, if we rely on Proc class
                // consumption
                // of stdout and stderr with StreamCopyThread, then first several chars of a stream aren't get
                // transcoded.
                // Also, there is a need to pass a EBCDIC codepage conversion charset into input stream.
                p.readStdout().readStderr();
                Proc process = p.start();

                status = process.joinWithTimeout(usedTimeout, TimeUnit.MINUTES, listener);

                stdout = readProcessIntoString(process, encoding, false);
                stderr = readProcessIntoString(process, encoding, true);
            } else {
                // JENKINS-13356: capture stdout and stderr separately
                ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
                ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();

                p.stdout(stdoutStream).stderr(stderrStream);
                status = p.start().joinWithTimeout(usedTimeout, TimeUnit.MINUTES, listener);

                stdout = stdoutStream.toString(encoding);
                stderr = stderrStream.toString(encoding);
            }

            if (status != 0) {
                throw new GitException("Command \"" + command + "\" returned status code " + status + ":\nstdout: "
                        + stdout + "\nstderr: " + stderr);
            }

            return stdout;
        } catch (GitException | InterruptedException e) {
            if (e.getMessage() != null && e.getMessage().contains("unsupported option \"accept-new\"")) {
                listener.getLogger()
                        .println(
                                HyperlinkNote.encodeTo(
                                        "https://plugins.jenkins.io/git-client/#plugin-content-ssh-host-key-verification",
                                        "If you are using OpenSSH < 7.6 please choose another strategy to verify ssh host key in 'Manage Jenkins' -> 'Security' -> 'Git Host Key Verification Configuration'"));
            }
            throw e;
        } catch (Throwable e) {
            reportFailureClues();
            throw new GitException("Error performing git command: " + command, e);
        }
    }

    /**
     * push.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     */
    @Override
    public PushCommand push() {
        return new PushCommand() {
            private URIish remote;
            private String refspec;
            private boolean force;
            private boolean tags;
            private Integer timeout;

            @Override
            public PushCommand to(URIish remote) {
                this.remote = remote;
                return this;
            }

            @Override
            public PushCommand ref(String refspec) {
                this.refspec = refspec;
                return this;
            }

            @Override
            public PushCommand force() {
                return force(true);
            }

            @Override
            public PushCommand force(boolean force) {
                this.force = force;
                return this;
            }

            @Override
            public PushCommand tags(boolean tags) {
                this.tags = tags;
                return this;
            }

            @Override
            public PushCommand timeout(Integer timeout) {
                this.timeout = timeout;
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                ArgumentListBuilder args = new ArgumentListBuilder();
                if (remote == null) {
                    throw new GitException("PushCommand requires a 'remote'");
                }
                args.add("push", remote.toPrivateASCIIString());

                if (refspec != null) {
                    args.add(refspec);
                }

                if (force) {
                    args.add("-f");
                }

                if (tags) {
                    args.add("--tags");
                }

                StandardCredentials cred = credentials.get(remote.toPrivateString());
                if (cred == null) {
                    cred = defaultCredentials;
                }
                launchCommandWithCredentials(args, workspace, cred, remote, timeout);
                // Ignore output for now as there's many different formats
                // That are possible.
            }
        };
    }

    /**
     * Parse branch name and SHA1 from "fos" argument string.
     *
     * Argument content must match "git branch -v --no-abbrev".
     *
     * One branch per line, two leading characters ignored on each
     * line, the branch name (not allowed to contain spaces), one or
     * more spaces, and the 40 character SHA1 of the commit that is
     * the head of that branch. Text after the SHA1 is ignored.
     *
     * @param fos output of "git branch -v --no-abbrev"
     * @return a {@link java.util.Set} object.
     */
    /*package*/ Set<Branch> parseBranches(String fos) {
        // JENKINS-34309 if the commit message contains line breaks,
        // "git branch -v --no-abbrev" output will include CR (Carriage Return) characters.
        // Replace all CR characters to avoid interpreting them as line endings
        fos = fos.replaceAll("\\r", "");

        Set<Branch> branches = new HashSet<>();
        BufferedReader rdr = new BufferedReader(new StringReader(fos));
        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                if (line.length() < 44 || !line.contains(" ")) {
                    // Line must contain 2 leading characters, branch
                    // name (at least 1 character), a space, and 40
                    // character SHA1.
                    continue;
                }
                // Ignore leading 2 characters (marker for current branch)
                // Ignore line if second field is not SHA1 length (40 characters)
                // Split fields into branch name, SHA1, and rest of line
                // Fields are separated by one or more spaces
                String[] branchVerboseOutput = line.substring(2).split(" +", 3);
                if (branchVerboseOutput.length > 1 && branchVerboseOutput[1].length() == 40) {
                    branches.add(new Branch(branchVerboseOutput[0], ObjectId.fromString(branchVerboseOutput[1])));
                }
            }
        } catch (IOException e) {
            throw new GitException("Error parsing branches", e);
        }

        return branches;
    }

    /**
     * Returns the set of branches defined in this repository,
     * including local branches and remote branches. Remote branches
     * are prefixed by "remotes/".
     *
     * @return a {@link java.util.Set} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public Set<Branch> getBranches() throws GitException, InterruptedException {
        return parseBranches(launchCommand("branch", "-a", "-v", "--no-abbrev"));
    }

    /**
     * Returns the remote branches defined in this repository.
     *
     * @return {@link java.util.Set} of remote branches in this repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails
     * @throws java.lang.InterruptedException if interrupted
     */
    @Override
    public Set<Branch> getRemoteBranches() throws GitException, InterruptedException {
        try (Repository db = getRepository()) {
            Map<String, Ref> refs = db.getAllRefs();
            Set<Branch> branches = new HashSet<>();

            for (Ref candidate : refs.values()) {
                if (candidate.getName().startsWith(Constants.R_REMOTES)) {
                    Branch buildBranch = new Branch(candidate);
                    if (!GitClient.quietRemoteBranches) {
                        listener.getLogger().println("Seen branch in repository " + buildBranch.getName());
                    }
                    branches.add(buildBranch);
                }
            }

            if (branches.size() == 1) {
                listener.getLogger().println("Seen 1 remote branch");
            } else {
                listener.getLogger().println(MessageFormat.format("Seen {0} remote branches", branches.size()));
            }

            return branches;
        }
    }

    /* For testability - interrupt the next checkout() */
    private boolean interruptNextCheckout = false;
    private String interruptMessage = "";

    /* Allow test of interrupted lock removal after checkout */
    /* package */ void interruptNextCheckoutWithMessage(String msg) {
        interruptNextCheckout = true;
        interruptMessage = msg;
    }

    /**
     * checkout.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CheckoutCommand} object.
     */
    @Override
    public CheckoutCommand checkout() {
        return new CheckoutCommand() {

            private String ref;
            private String branch;
            private boolean deleteBranch;
            private List<String> sparseCheckoutPaths = Collections.emptyList();
            private Integer timeout;
            private String lfsRemote;
            private StandardCredentials lfsCredentials;

            @Override
            public CheckoutCommand ref(String ref) {
                this.ref = ref;
                return this;
            }

            @Override
            public CheckoutCommand branch(String branch) {
                this.branch = branch;
                return this;
            }

            @Override
            public CheckoutCommand deleteBranchIfExist(boolean deleteBranch) {
                this.deleteBranch = deleteBranch;
                return this;
            }

            @Override
            public CheckoutCommand sparseCheckoutPaths(List<String> sparseCheckoutPaths) {
                this.sparseCheckoutPaths = sparseCheckoutPaths == null ? Collections.emptyList() : sparseCheckoutPaths;
                return this;
            }

            @Override
            public CheckoutCommand timeout(Integer timeout) {
                this.timeout = timeout;
                return this;
            }

            @Override
            public CheckoutCommand lfsRemote(String lfsRemote) {
                this.lfsRemote = lfsRemote;
                return this;
            }

            @Override
            public CheckoutCommand lfsCredentials(StandardCredentials lfsCredentials) {
                this.lfsCredentials = lfsCredentials;
                return this;
            }

            /* Allow test of index.lock cleanup when checkout is interrupted */
            private void interruptThisCheckout() throws InterruptedException {
                final File indexFile = new File(workspace.getPath() + File.separator + INDEX_LOCK_FILE_PATH);
                boolean created = false;
                try {
                    created = indexFile.createNewFile();
                } catch (IOException ex) {
                    throw new InterruptedException(ex.getMessage());
                }
                throw new InterruptedException(
                        created ? interruptMessage : (interruptMessage + " " + INDEX_LOCK_FILE_PATH + " not created"));
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                /* File.lastModified() limited by file system time, several
                 * popular Linux file systems only have 1 second granularity.
                 * None of the common file systems (Windows or Linux) have
                 * millisecond granularity.
                 */
                final long startTimeSeconds = (System.currentTimeMillis() / 1000) * 1000;
                try {

                    /* Testing only - simulate command line git leaving a lock file */
                    if (interruptNextCheckout) {
                        interruptNextCheckout = false;
                        interruptThisCheckout();
                    }

                    // Will activate or deactivate sparse checkout depending on the given paths
                    sparseCheckout(sparseCheckoutPaths);

                    EnvVars checkoutEnv = environment;
                    if (lfsRemote != null) {
                        // Disable the git-lfs smudge filter because it is much slower on
                        // certain OSes than doing a single "git lfs pull" after checkout.
                        checkoutEnv = new EnvVars(checkoutEnv);
                        checkoutEnv.put("GIT_LFS_SKIP_SMUDGE", "1");
                    }

                    if (branch != null && deleteBranch) {
                        // First, checkout to detached HEAD, so we can delete the branch.
                        ArgumentListBuilder args = new ArgumentListBuilder();
                        args.add("checkout", "-f", ref);
                        launchCommandIn(args, workspace, checkoutEnv, timeout);

                        // Second, check to see if the branch actually exists, and then delete it if it does.
                        for (Branch b : getBranches()) {
                            if (b.getName().equals(branch)) {
                                deleteBranch(branch);
                            }
                        }
                    }
                    ArgumentListBuilder args = new ArgumentListBuilder();
                    args.add("checkout");
                    if (branch != null) {
                        args.add("-b");
                        args.add(branch);
                    } else {
                        args.add("-f");
                    }
                    args.add(ref);

                    StandardCredentials cred = null;
                    String checkoutUrl = null;
                    if (isAtLeastVersion(2, 27, 0, 0)) {
                        String result = firstLine(launchCommand(
                                "config", "--get", "--default", "''", "remote.origin.partialclonefilter"));
                        if (result != null && !result.isBlank()) {
                            checkoutUrl = launchCommand("config", "--get", "--default", "''", "remote.origin.url")
                                    .trim(); // TODO: how to get the url correctly (and compatible with the
                            // unit tests)?
                            // checkoutUrl = getRemoteUrl("origin"); // fails with unit tests
                            if (checkoutUrl.isBlank()) {
                                checkoutUrl = null;
                            } else {
                                cred = getCredentials(checkoutUrl);
                            }
                        }
                    }

                    if (checkoutUrl != null) {
                        try {
                            // credentials are needed for instance for blobless clone and are simply not used in
                            // "standard" cases
                            launchCommandWithCredentials(args, workspace, cred, new URIish(checkoutUrl), timeout);
                        } catch (URISyntaxException e) {
                            throw new GitException("Invalid URL " + checkoutUrl, e);
                        }
                    } else {
                        launchCommandIn(args, workspace, checkoutEnv, timeout);
                    }

                    if (lfsRemote != null) {
                        final String url = getRemoteUrl(lfsRemote);
                        StandardCredentials lfsCred = lfsCredentials;
                        if (lfsCred == null) {
                            lfsCred = getCredentials(url);
                        }
                        ArgumentListBuilder lfsArgs = new ArgumentListBuilder();
                        lfsArgs.add("lfs");
                        lfsArgs.add("pull");
                        lfsArgs.add(lfsRemote);
                        try {
                            launchCommandWithCredentials(lfsArgs, workspace, lfsCred, new URIish(url), timeout);
                        } catch (URISyntaxException e) {
                            throw new GitException("Invalid URL " + url, e);
                        }
                    }
                } catch (GitException e) {
                    if (Pattern.compile("index\\.lock").matcher(e.getMessage()).find()) {
                        throw new GitLockFailedException("Could not lock repository. Please try again", e);
                    } else {
                        if (branch != null) {
                            throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
                        } else {
                            throw new GitException("Could not checkout " + ref, e);
                        }
                    }
                } catch (InterruptedException e) {
                    final File indexFile = new File(workspace.getPath() + File.separator + INDEX_LOCK_FILE_PATH);
                    if (indexFile.exists() && indexFile.lastModified() >= startTimeSeconds) {
                        // If lock file is created before checkout command
                        // started, it is not created by this checkout command
                        // and we should leave it in place
                        try {
                            FileUtils.forceDelete(indexFile);
                        } catch (IOException ioe) {
                            throw new GitException("Could not remove index lock file on interrupting thread", ioe);
                        }
                    }
                    throw e;
                }
            }

            private void sparseCheckout(@NonNull List<String> paths) throws GitException, InterruptedException {

                boolean coreSparseCheckoutConfigEnable;
                try {
                    coreSparseCheckoutConfigEnable =
                            launchCommand("config", "core.sparsecheckout").contains("true");
                } catch (GitException ge) {
                    coreSparseCheckoutConfigEnable = false;
                }

                boolean deactivatingSparseCheckout = false;
                if (paths.isEmpty() && !coreSparseCheckoutConfigEnable) { // Nothing to do
                    return;
                } else if (paths.isEmpty() && coreSparseCheckoutConfigEnable) { // deactivating sparse checkout needed
                    deactivatingSparseCheckout = true;
                    paths = Collections.singletonList("/*");
                } else if (!coreSparseCheckoutConfigEnable) { // activating sparse checkout
                    launchCommand("config", "core.sparsecheckout", "true");
                }

                File sparseCheckoutDir = new File(workspace, SPARSE_CHECKOUT_FILE_DIR);
                if (!sparseCheckoutDir.exists() && !sparseCheckoutDir.mkdir()) {
                    throw new GitException(
                            "Impossible to create sparse checkout dir " + sparseCheckoutDir.getAbsolutePath());
                }

                File sparseCheckoutFile = new File(workspace, SPARSE_CHECKOUT_FILE_PATH);

                try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                        Files.newOutputStream(sparseCheckoutFile.toPath()), StandardCharsets.UTF_8))) {
                    for (String path : paths) {
                        writer.println(environment.expand(path));
                    }
                } catch (IOException e) {
                    throw new GitException(
                            "Could not write sparse checkout file " + sparseCheckoutFile.getAbsolutePath(), e);
                }

                if (lfsRemote != null) {
                    // Currently git-lfs doesn't support commas in "fetchinclude" and "fetchexclude".
                    // (see https://github.com/git-lfs/git-lfs/issues/2264)
                    // Therefore selective fetching is disabled in this case.
                    if (paths.stream().anyMatch(path -> path.contains(","))) {
                        setLfsFetchOption("lfs.fetchinclude", "");
                        setLfsFetchOption("lfs.fetchexclude", "");
                    } else {
                        String lfsIncludePaths = paths.stream()
                                .filter(path -> !path.startsWith("!"))
                                .collect(Collectors.joining(","));

                        String lfsExcludePaths = paths.stream()
                                .filter(path -> path.startsWith("!"))
                                .map(path -> path.substring(1))
                                .collect(Collectors.joining(","));

                        setLfsFetchOption("lfs.fetchinclude", lfsIncludePaths);
                        setLfsFetchOption("lfs.fetchexclude", lfsExcludePaths);
                    }
                }

                try {
                    launchCommand("read-tree", "-mu", "HEAD");
                } catch (GitException e) {
                    // normal return code if sparse checkout path did never exist on the current checkout branch
                    if (!e.getMessage().contains("returned status code 128:")) {
                        throw e;
                    }
                }

                if (deactivatingSparseCheckout) {
                    launchCommand("config", "core.sparsecheckout", "false");
                }
            }

            private void setLfsFetchOption(String key, String value) throws GitException, InterruptedException {
                if (value.isEmpty() || value.equals("/*")) {
                    try {
                        launchCommand("config", "--unset", key);
                    } catch (GitException e) {
                        // normal return code if the option was not set before
                        if (!e.getMessage().contains("returned status code 5:")) {
                            throw e;
                        }
                    }
                } else {
                    launchCommand("config", key, value);
                }
            }
        };
    }

    /** {@inheritDoc} */
    @Override
    public boolean tagExists(String tagName) throws GitException, InterruptedException {
        return launchCommand("tag", "-l", tagName).trim().equals(tagName);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteBranch(String name) throws GitException, InterruptedException {
        try {
            launchCommand("branch", "-D", name);
        } catch (GitException e) {
            throw new GitException("Could not delete branch " + name, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deleteTag(String tagName) throws GitException, InterruptedException {
        tagName = tagName.replace(' ', '_');
        try {
            launchCommand("tag", "-d", tagName);
        } catch (GitException e) {
            throw new GitException("Could not delete tag " + tagName, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<IndexEntry> lsTree(String treeIsh, boolean recursive) throws GitException, InterruptedException {
        List<IndexEntry> entries = new ArrayList<>();
        String result = launchCommand("ls-tree", recursive ? "-r" : null, treeIsh);

        BufferedReader rdr = new BufferedReader(new StringReader(result));
        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                String[] entry = line.split("\\s+");
                entries.add(new IndexEntry(entry[0], entry[1], entry[2], entry[3]));
            }
        } catch (IOException e) {
            throw new GitException("Error parsing ls tree", e);
        }

        return entries;
    }

    /**
     * revList_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     */
    @Override
    public RevListCommand revList_() {
        return new RevListCommand() {
            private boolean all;
            private boolean nowalk;
            private boolean firstParent;
            private String refspec;
            private List<ObjectId> out;

            @Override
            public RevListCommand all() {
                return all(true);
            }

            @Override
            public RevListCommand all(boolean all) {
                this.all = all;
                return this;
            }

            @Override
            public RevListCommand nowalk(boolean nowalk) {
                // --no-walk wasn't introduced until v1.5.3
                if (isAtLeastVersion(1, 5, 3, 0)) {
                    this.nowalk = nowalk;
                }
                return this;
            }

            @Override
            public RevListCommand firstParent() {
                return firstParent(true);
            }

            @Override
            public RevListCommand firstParent(boolean firstParent) {
                this.firstParent = firstParent;
                return this;
            }

            @Override
            public RevListCommand to(List<ObjectId> revs) {
                this.out = revs;
                return this;
            }

            @Override
            public RevListCommand reference(String reference) {
                this.refspec = reference;
                return this;
            }

            @Override
            public void execute() throws GitException, InterruptedException {
                ArgumentListBuilder args = new ArgumentListBuilder("rev-list");

                if (firstParent) {
                    args.add("--first-parent");
                }

                if (all) {
                    args.add("--all");
                }

                if (nowalk) {
                    args.add("--no-walk");
                }

                if (refspec != null) {
                    args.add(refspec);
                }

                String result = launchCommand(args);
                BufferedReader rdr = new BufferedReader(new StringReader(result));
                String line;

                if (out == null) {
                    throw new GitException("RevListCommand requires a value for 'to'");
                }
                try {
                    while ((line = rdr.readLine()) != null) {
                        // Add the SHA1
                        out.add(ObjectId.fromString(line));
                    }
                } catch (IOException e) {
                    throw new GitException("Error parsing rev list", e);
                }
            }
        };
    }

    /**
     * revListAll.
     *
     * @return a {@link java.util.List} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public List<ObjectId> revListAll() throws GitException, InterruptedException {
        List<ObjectId> oidList = new ArrayList<>();
        RevListCommand revListCommand = revList_();
        revListCommand.all(true);
        revListCommand.to(oidList);
        revListCommand.execute();
        return oidList;
    }

    /** {@inheritDoc} */
    @Override
    public List<ObjectId> revList(String ref) throws GitException, InterruptedException {
        List<ObjectId> oidList = new ArrayList<>();
        RevListCommand revListCommand = revList_();
        revListCommand.reference(ref);
        revListCommand.to(oidList);
        revListCommand.execute();
        return oidList;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isCommitInRepo(ObjectId commit) throws InterruptedException {
        if (commit == null) {
            return false;
        }
        try {
            // Use revList_() directly in order to pass .nowalk(true) which
            // allows us to bypass the unnecessary revision walk when we
            // only care to determine if the commit exists.
            List<ObjectId> oidList = new ArrayList<>();
            RevListCommand revListCommand = revList_();
            revListCommand.reference(commit.name());
            revListCommand.to(oidList);
            revListCommand.nowalk(true);
            revListCommand.execute();

            return oidList.size() != 0;
        } catch (GitException e) {
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void add(String filePattern) throws GitException, InterruptedException {
        try {
            launchCommand("add", filePattern);
        } catch (GitException e) {
            throw new GitException("Cannot add " + filePattern, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void branch(String name) throws GitException, InterruptedException {
        try {
            launchCommand("branch", name);
        } catch (GitException e) {
            throw new GitException("Cannot create branch " + name, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void commit(String message) throws GitException, InterruptedException {
        Path f = null;
        try {
            f = createTempFile("gitcommit", ".txt");
            try (BufferedWriter w = Files.newBufferedWriter(f, Charset.defaultCharset())) {
                w.write(message);
            }
            launchCommand("commit", "-F", f.toAbsolutePath().toString());

        } catch (GitException | IOException e) {
            throw new GitException("Cannot commit " + message, e);
        } finally {
            deleteTempFile(f);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void addCredentials(String url, StandardCredentials credentials) {
        this.credentials.put(url, credentials);
    }

    /**
     * clearCredentials.
     */
    @Override
    public void clearCredentials() {
        this.credentials.clear();
    }

    /** {@inheritDoc} */
    @Override
    public void addDefaultCredentials(StandardCredentials credentials) {
        this.defaultCredentials = credentials;
    }

    /** {@inheritDoc} */
    @Override
    public void setAuthor(String name, String email) throws GitException {
        env("GIT_AUTHOR_NAME", name);
        env("GIT_AUTHOR_EMAIL", email);
    }

    /** {@inheritDoc} */
    @Override
    public void setCommitter(String name, String email) throws GitException {
        env("GIT_COMMITTER_NAME", name);
        env("GIT_COMMITTER_EMAIL", email);
    }

    private void env(String name, String value) {
        if (value == null) {
            environment.remove(name);
        } else {
            environment.put(name, value);
        }
    }

    /**
     * Returns the {@link org.eclipse.jgit.lib.Repository} used by this git instance.
     *
     * @return a {@link org.eclipse.jgit.lib.Repository} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    @SuppressFBWarnings(value = "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE", justification = "JGit interaction with spotbugs")
    @NonNull
    @Override
    public Repository getRepository() throws GitException {
        try {
            return new RepositoryBuilder().setWorkTree(workspace).build();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /**
     * getWorkTree.
     *
     * @return a {@link hudson.FilePath} object.
     */
    @Override
    public FilePath getWorkTree() {
        return new FilePath(workspace);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getRemoteTagNames(String tagPattern) throws GitException {
        try {
            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add("ls-remote", "--tags");
            String remoteUrl = getRemoteUrl("origin");
            if (remoteUrl != null) {
                addCheckedRemoteUrl(args, remoteUrl);
            }
            if (tagPattern != null) {
                args.add(tagPattern);
            }
            String result = launchCommandIn(args, workspace);
            Set<String> tags = new HashSet<>();
            BufferedReader rdr = new BufferedReader(new StringReader(result));
            String tag;
            while ((tag = rdr.readLine()) != null) {
                // Add the tag name without the SHA1
                tags.add(tag.replaceFirst(".*refs/tags/", ""));
            }
            return tags;
        } catch (GitException | IOException | InterruptedException e) {
            throw new GitException("Error retrieving remote tag names", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getTagNames(String tagPattern) throws GitException {
        try {
            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add("tag", "-l", tagPattern);

            String result = launchCommandIn(args, workspace);

            Set<String> tags = new HashSet<>();
            BufferedReader rdr = new BufferedReader(new StringReader(result));
            String tag;
            while ((tag = rdr.readLine()) != null) {
                // Add the SHA1
                tags.add(tag);
            }
            return tags;
        } catch (GitException | IOException | InterruptedException e) {
            throw new GitException("Error retrieving tag names", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String getTagMessage(String tagName) throws GitException, InterruptedException {
        // 10000 lines of tag message "ought to be enough for anybody"
        String out = launchCommand("tag", "-l", tagName, "-n10000");
        // Strip the leading four spaces which git prefixes multi-line messages with
        return out.substring(tagName.length()).replaceAll("(?m)(^    )", "").trim();
    }

    /** {@inheritDoc} */
    @Override
    public void ref(String refName) throws GitException, InterruptedException {
        refName = refName.replace(' ', '_');
        try {
            launchCommand("update-ref", refName, "HEAD");
        } catch (GitException e) {
            throw new GitException("Could not apply ref " + refName, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean refExists(String refName) throws GitException, InterruptedException {
        refName = refName.replace(' ', '_');
        try {
            launchCommand("show-ref", refName);
            return true; // If show-ref returned zero, ref exists.
        } catch (GitException e) {
            return false; // If show-ref returned non-zero, ref doesn't exist.
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deleteRef(String refName) throws GitException, InterruptedException {
        refName = refName.replace(' ', '_');
        try {
            launchCommand("update-ref", "-d", refName);
        } catch (GitException e) {
            throw new GitException("Could not delete ref " + refName, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getRefNames(String refPrefix) throws GitException, InterruptedException {
        if (refPrefix.isEmpty()) {
            refPrefix = "refs/";
        } else {
            refPrefix = refPrefix.replace(' ', '_');
        }
        try {
            String result = launchCommand("for-each-ref", "--format=%(refname)", refPrefix);
            Set<String> refs = new HashSet<>();
            BufferedReader rdr = new BufferedReader(new StringReader(result));
            String ref;
            while ((ref = rdr.readLine()) != null) {
                refs.add(ref);
            }
            return refs;
        } catch (GitException | IOException e) {
            throw new GitException("Error retrieving refs with prefix " + refPrefix, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, ObjectId> getHeadRev(String url) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder("ls-remote");
        args.add("-h");
        addCheckedRemoteUrl(args, url);

        StandardCredentials cred = getCredentials(url);

        String result = launchCommandWithCredentials(args, null, cred, url);

        Map<String, ObjectId> heads = new HashMap<>();
        String[] lines = result.split("\n");
        for (String line : lines) {
            if (line.length() >= 41) {
                heads.put(line.substring(41), ObjectId.fromString(line.substring(0, 40)));
            } else {
                listener.getLogger().println("Unexpected ls-remote output line '" + line + "'");
            }
        }
        return heads;
    }

    /** {@inheritDoc} */
    @Override
    public ObjectId getHeadRev(String url, String branchSpec) throws GitException, InterruptedException {
        final String branchName = extractBranchNameFromBranchSpec(branchSpec);
        ArgumentListBuilder args = new ArgumentListBuilder("ls-remote");
        if (!branchName.startsWith("refs/tags/")) {
            args.add("-h");
        }

        StandardCredentials cred = getCredentials(url);

        addCheckedRemoteUrl(args, url);

        if (branchName.startsWith("refs/tags/")) {
            args.add(branchName + "^{}"); // JENKINS-23299 - tag SHA1 needs to be converted to commit SHA1
        } else {
            args.add(branchName);
        }
        String result = launchCommandWithCredentials(args, null, cred, url);
        return result.length() >= 40 ? ObjectId.fromString(result.substring(0, 40)) : null;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, ObjectId> getRemoteReferences(String url, String pattern, boolean headsOnly, boolean tagsOnly)
            throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder("ls-remote");
        if (headsOnly) {
            args.add("-h");
        }
        if (tagsOnly) {
            args.add("-t");
        }
        addCheckedRemoteUrl(args, url);
        if (pattern != null) {
            args.add(pattern);
        }

        StandardCredentials cred = getCredentials(url);

        String result = launchCommandWithCredentials(args, null, cred, url);

        Map<String, ObjectId> references = new HashMap<>();
        String[] lines = result.split("\n");
        for (String line : lines) {
            if (line.length() < 41) {
                continue; // throw new GitException("unexpected ls-remote output " + line);
            }
            String refName = line.substring(41);
            ObjectId refObjectId = ObjectId.fromString(line.substring(0, 40));
            if (refName.startsWith("refs/tags") && refName.endsWith("^{}")) {
                // get peeled object id for annotated tag
                String tagName = refName.replace("^{}", "");
                // Replace with the peeled object id if the entry with tagName exists
                references.put(tagName, refObjectId);
            } else {
                if (!references.containsKey(refName)) {
                    references.put(refName, refObjectId);
                }
            }
        }
        return references;
    }

    @Override
    public Map<String, String> getRemoteSymbolicReferences(String url, String pattern)
            throws GitException, InterruptedException {
        Map<String, String> references = new HashMap<>();
        if (isAtLeastVersion(2, 8, 0, 0)) {
            // --symref is only understood by ls-remote starting from git 2.8.0
            // https://github.com/git/git/blob/afd6726309/Documentation/RelNotes/2.8.0.txt#L72-L73
            ArgumentListBuilder args = new ArgumentListBuilder("ls-remote");
            args.add("--symref");
            addCheckedRemoteUrl(args, url);
            if (pattern != null) {
                args.add(pattern);
            }

            StandardCredentials cred = getCredentials(url);

            String result = launchCommandWithCredentials(args, null, cred, url);

            String[] lines = result.split("\n");
            Pattern symRefPattern = Pattern.compile("^ref:\\s+([^ ]+)\\s+([^ ]+)$");
            for (String line : lines) {
                Matcher matcher = symRefPattern.matcher(line);
                if (matcher.matches()) {
                    references.put(matcher.group(2), matcher.group(1));
                }
            }
        }
        return references;
    }

    //
    //
    // Legacy Implementation of IGitAPI
    //
    //

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public void merge(String refSpec) throws GitException, InterruptedException {
        try {
            launchCommand("merge", refSpec);
        } catch (GitException e) {
            throw new GitException("Could not merge " + refSpec, e);
        }
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public void push(RemoteConfig repository, String refspec) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        URIish uri = repository.getURIs().get(0);
        String url = uri.toPrivateString();
        StandardCredentials cred = getCredentials(url);

        args.add("push");
        addCheckedRemoteUrl(args, url);

        if (refspec != null) {
            args.add(refspec);
        }

        launchCommandWithCredentials(args, workspace, cred, uri);
        // Ignore output for now as there's many different formats
        // That are possible.

    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public List<Branch> getBranchesContaining(String revspec) throws GitException, InterruptedException {
        // For backward compatibility we do query remote branches here
        return getBranchesContaining(revspec, true);
    }

    /** {@inheritDoc} */
    @Override
    public List<Branch> getBranchesContaining(String revspec, boolean allBranches)
            throws GitException, InterruptedException {
        final String commandOutput;
        if (allBranches) {
            commandOutput = launchCommand("branch", "-a", "-v", "--no-abbrev", "--contains", revspec);
        } else {
            commandOutput = launchCommand("branch", "-v", "--no-abbrev", "--contains", revspec);
        }
        return new ArrayList<>(parseBranches(commandOutput));
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public ObjectId mergeBase(ObjectId id1, ObjectId id2) throws InterruptedException {
        try {
            String result;
            try {
                result = launchCommand("merge-base", id1.name(), id2.name());
            } catch (GitException ge) {
                return null;
            }

            BufferedReader rdr = new BufferedReader(new StringReader(result));
            final String line = rdr.readLine();

            if (line != null) {
                // Add the SHA1
                return ObjectId.fromString(line);
            }
        } catch (IOException | GitException e) {
            throw new GitException("Error parsing merge base", e);
        }

        return null;
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public String getAllLogEntries(String branch) throws InterruptedException {
        // BROKEN: --all and branch are conflicting.
        return launchCommand("log", "--all", "--pretty=format:'%H#%ct'", branch);
    }

    /**
     * preventive Time-out for git command execution.
     * <p>
     * We run git as an external process so can't guarantee it won't hang for whatever reason. Even though the plugin does its
     * best to avoid git interactively asking for credentials, there are many of other cases where git may hang.
     */
    @Whitelisted
    public static final int TIMEOUT = Integer.getInteger(Git.class.getName() + ".timeOut", 10);

    /** inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue */
    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }

    private boolean isZos() {
        return File.pathSeparatorChar == ':' && System.getProperty("os.name").equals("z/OS");
    }

    /* Return true if setsid program exists */
    private static boolean setsidExists() {
        if (File.pathSeparatorChar == ';') {
            return false;
        }
        String[] prefixes = {"/usr/local/bin/", "/usr/bin/", "/bin/", "/usr/local/sbin/", "/usr/sbin/", "/sbin/"};
        for (String prefix : prefixes) {
            File setsidFile = new File(prefix + "setsid");
            if (setsidFile.exists()) {
                return true;
            }
        }
        return false;
    }

    private StandardCredentials getCredentials(String url) {
        StandardCredentials cred = credentials.get(url);
        if (cred == null) {
            cred = defaultCredentials;
        }
        return cred;
    }

    /** {@inheritDoc} */
    @Override
    public Set<GitObject> getTags() throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder("show-ref", "--tags", "-d");
        String result;
        try {
            result = launchCommandIn(args, workspace);
        } catch (GitException ge) {
            /* If no tags, then git show-ref --tags -d returns non-zero */
            result = "";
        }

        /*
        Output shows SHA1 and tag with (optional) marker for annotated tags
        7ac27f7a051e1017da9f7c45ade8f091dbe6f99d refs/tags/git-3.6.4
        7b5856ef2b4d35530a06d6482d0f4e972769d89b refs/tags/git-3.6.4^{}
         */
        String[] output = result.split("[\\n\\r]+");
        if (output.length == 0 || (output.length == 1 && output[0].isEmpty())) {
            return Collections.emptySet();
        }
        Pattern pattern = Pattern.compile("(\\p{XDigit}{40})\\s+refs/tags/([^^]+)(\\^\\{\\})?");
        Map<String, ObjectId> tagMap = new HashMap<>();
        for (String line : output) {
            Matcher matcher = pattern.matcher(line);
            if (!matcher.find()) {
                // Log the surprise and skip the line
                String message = MessageFormat.format("git show-ref --tags -d output not matched in line: {0}", line);
                listener.getLogger().println(message);
                continue;
            }
            String sha1String = matcher.group(1);
            String tagName = matcher.group(2);
            String trailingText = matcher.group(3);
            final boolean isPeeledRef = trailingText != null && trailingText.equals("^{}");
            // Line ends with '^{}'
            /* Prefer peeled ref if available (for tag commit), otherwise take first tag reference seen */
            if (isPeeledRef || !tagMap.containsKey(tagName)) {
                tagMap.put(tagName, ObjectId.fromString(sha1String));
            }
        }
        Set<GitObject> tags = new HashSet<>(tagMap.size());
        for (Map.Entry<String, ObjectId> entry : tagMap.entrySet()) {
            tags.add(new GitObject(entry.getKey(), entry.getValue()));
        }
        return tags;
    }

    /** {@inheritDoc} */
    @Override
    public boolean maintenance(String task) throws InterruptedException {
        boolean isExecuted = true;
        try {
            listener.getLogger().println("Git maintenance " + task + " started on " + workspace.getName());
            long startTime = System.currentTimeMillis();
            if (isAtLeastVersion(2, 30, 0, 0)) {
                // For prefetch, the command will throw an error for private repo if it has no access.
                launchCommand("maintenance", "run", "--task=" + task);
            } else {
                switch (task) {
                    case "gc":
                        launchCommand("gc", "--auto");
                        break;
                    case "commit-graph":
                        if (isAtLeastVersion(2, 19, 0, 0)) {
                            launchCommand("commit-graph", "write");
                        } else {
                            listener.getLogger().println("Error executing commit-graph maintenance task");
                        }
                        break;
                    case "incremental-repack":
                        if (isAtLeastVersion(2, 25, 0, 0)) {
                            launchCommand("multi-pack-index", "expire");
                            launchCommand("multi-pack-index", "repack");
                        } else {
                            listener.getLogger().println("Error executing incremental-repack maintenance task");
                        }
                        break;
                    default:
                        String message = "Invalid legacy git maintenance task " + task + ".";
                        listener.getLogger().println(message);
                        throw new GitException(message);
                }
            }
            long endTime = System.currentTimeMillis();
            listener.getLogger()
                    .println("Git maintenance task " + task + " finished on " + workspace.getName() + " in "
                            + (endTime - startTime) + "ms.");
        } catch (GitException e) {
            isExecuted = false;
            listener.getLogger().println("Error executing " + task + " maintenance task");
            listener.getLogger().println("Mainteance task " + task + " error message: " + e.getMessage());
        }
        return isExecuted;
    }

    /** {@inheritDoc} */
    @Override
    public void config(ConfigLevel configLevel, String key, String value) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder("config");
        if (configLevel != null) {
            args.add("--" + configLevel.toString().toLowerCase(Locale.getDefault()));
        } else {
            args.add("--" + ConfigLevel.LOCAL.toString().toLowerCase(Locale.getDefault()));
        }
        if (value != null) {
            args.add(key, value);
        } else {
            args.add("--unset", key);
        }
        launchCommand(args);
    }
}
