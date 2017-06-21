
package org.jenkinsci.plugins.gitclient;


import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Launcher.LocalLauncher;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitLockFailedException;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import hudson.util.ArgumentListBuilder;
import hudson.util.Secret;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.stapler.framework.io.WriterOutputStream;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.Matcher;


/**
 * Implementation class using command line CLI ran as external command.
 * <b>
 * For internal use only, don't use directly. See {@link org.jenkinsci.plugins.gitclient.Git}
 * </b>
 */
public class CliGitAPIImpl extends LegacyCompatibleGitAPIImpl {

    private static final boolean acceptSelfSignedCertificates;

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
    public static final boolean USE_SETSID = Boolean.valueOf(System.getProperty(CliGitAPIImpl.class.getName() + ".useSETSID", "false"));

    /**
     * CALL_SETSID decides if command line git can use the setsid program
     * during ssh based authentication to detach git from its controlling
     * terminal.
     *
     * If the controlling terminal remains attached, then ssh passphrase based
     * private keys cannot be decrypted during authentication (at least in some
     * ssh configurations).
     */
    private static final boolean CALL_SETSID;
    static {
        acceptSelfSignedCertificates = Boolean.getBoolean(GitClient.class.getName() + ".untrustedSSL");
        CALL_SETSID = setsidExists() && USE_SETSID;
    }

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

    private void warnIfWindowsTemporaryDirNameHasSpaces() {
        if (!isWindows()) {
            return;
        }
        String[] varsToCheck = {"TEMP", "TMP"};
        for (String envVar : varsToCheck) {
            String value = environment.get(envVar, "C:\\Temp");
            if (value.contains(" ")) {
                listener.getLogger().println("env " + envVar + "='" + value + "' contains an embedded space."
                        + " Some msysgit versions may fail credential related operations.");
            }
        }
    }

    // AABBCCDD where AA=major, BB=minor, CC=rev, DD=bugfix
    private long gitVersion = 0;
    private long computeVersionFromBits(int major, int minor, int rev, int bugfix) {
        return (major*1000000L) + (minor*10000L) + (rev*100L) + bugfix;
    }
    private void getGitVersion() {
        if (gitVersion != 0) {
            return;
        }

        String version = "";
        try {
            version = launchCommand("--version").trim();
        } catch (Throwable e) {
        }

        computeGitVersion(version);
    }

    /* package */ void computeGitVersion(String version) {
        int gitMajorVersion  = 0;
        int gitMinorVersion  = 0;
        int gitRevVersion    = 0;
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

            String[] fields = version.split(" ")[2].replace("msysgit.", "").replace("windows.", "").split("\\.");

            gitMajorVersion  = Integer.parseInt(fields[0]);
            gitMinorVersion  = (fields.length > 1) ? Integer.parseInt(fields[1]) : 0;
            gitRevVersion    = (fields.length > 2) ? Integer.parseInt(fields[2]) : 0;
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
     * Constructor for CliGitAPIImpl.
     *
     * @param gitExe a {@link java.lang.String} object.
     * @param workspace a {@link java.io.File} object.
     * @param listener a {@link hudson.model.TaskListener} object.
     * @param environment a {@link hudson.EnvVars} object.
     */
    protected CliGitAPIImpl(String gitExe, File workspace,
                         TaskListener listener, EnvVars environment) {
        super(workspace);
        this.listener = listener;
        this.gitExe = gitExe;
        this.environment = environment;

        launcher = new LocalLauncher(IGitAPI.verbose?listener:TaskListener.NULL);
    }

    /** {@inheritDoc} */
    public GitClient subGit(String subdir) {
        return new CliGitAPIImpl(gitExe, new File(workspace, subdir), listener, environment);
    }

    /**
     * Initialize an empty repository for further git operations.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public void init() throws GitException, InterruptedException {
        init_().workspace(workspace.getAbsolutePath()).execute();
    }

    /**
     * hasGitRepo.
     *
     * @return true if this workspace has a git repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
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
     * Returns true if the parameter GIT_DIR is a directory which
     * contains a git repository.
     *
     * @param GIT_DIR a {@link java.lang.String} object.
     * @return true if GIT_DIR has a git repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    public boolean hasGitRepo( String GIT_DIR ) throws GitException {
        try {
            File dotGit = new File(workspace, GIT_DIR);
            return dotGit.exists();
        } catch (SecurityException ex) {
            throw new GitException("Security error when trying to check for .git. Are you sure you have correct permissions?",
                                   ex);
        } catch (Exception e) {
            throw new GitException("Couldn't check for .git", e);
        }
    }

    /** {@inheritDoc} */
    public List<IndexEntry> getSubmodules( String treeIsh ) throws GitException, InterruptedException {
        List<IndexEntry> submodules = lsTree(treeIsh,true);

        // Remove anything that isn't a submodule
        for (Iterator<IndexEntry> it = submodules.iterator(); it.hasNext();) {
            if (!it.next().getMode().equals("160000")) {
                it.remove();
            }
        }
        return submodules;
    }

    /**
     * fetch_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.FetchCommand} object.
     */
    public FetchCommand fetch_() {
        return new FetchCommand() {
            public URIish url;
            public List<RefSpec> refspecs;
            public boolean prune;
            public boolean shallow;
            public Integer timeout;
            public boolean tags = true;
            public Integer depth = 1;

            public FetchCommand from(URIish remote, List<RefSpec> refspecs) {
                this.url = remote;
                this.refspecs = refspecs;
                return this;
            }

            public FetchCommand tags(boolean tags) {
                this.tags = tags;
                return this;
            }

            public FetchCommand prune() {
                this.prune = true;
                return this;
            }

            public FetchCommand shallow(boolean shallow) {
                this.shallow = shallow;
                return this;
            }

            public FetchCommand timeout(Integer timeout) {
            	this.timeout = timeout;
            	return this;
            }

            public FetchCommand depth(Integer depth) {
                this.depth = depth;
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                listener.getLogger().println(
                        "Fetching upstream changes from " + url);

                ArgumentListBuilder args = new ArgumentListBuilder();
                args.add("fetch");
                args.add(tags ? "--tags" : "--no-tags");
                if (isAtLeastVersion(1,7,1,0))
                    args.add("--progress");

                StandardCredentials cred = credentials.get(url.toPrivateString());
                if (cred == null) cred = defaultCredentials;
                args.add(url);

                if (refspecs != null)
                    for (RefSpec rs: refspecs)
                        if (rs != null)
                            args.add(rs.toString());

                if (prune) args.add("--prune");

                if (shallow) {
                    if (depth == null){
                        depth = 1;
                    }
                    args.add("--depth=" + depth);
                }

                warnIfWindowsTemporaryDirNameHasSpaces();

                launchCommandWithCredentials(args, workspace, cred, url, timeout);
            }
        };
    }

    /** {@inheritDoc} */
    public void fetch(URIish url, List<RefSpec> refspecs) throws GitException, InterruptedException {
        fetch_().from(url, refspecs).execute();
    }

    /** {@inheritDoc} */
    public void fetch(String remoteName, RefSpec... refspec) throws GitException, InterruptedException {
        listener.getLogger().println(
                                     "Fetching upstream changes"
                                     + (remoteName != null ? " from " + remoteName : ""));

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("fetch", "-t");

        if (remoteName == null)
            remoteName = getDefaultRemote();

        String url = getRemoteUrl(remoteName);
        if (url == null)
            throw new GitException("remote." + remoteName + ".url not defined");
        args.add(url);
        if (refspec != null && refspec.length > 0)
            for (RefSpec rs: refspec)
                if (rs != null)
                    args.add(rs.toString());


        StandardCredentials cred = credentials.get(url);
        if (cred == null) cred = defaultCredentials;
        launchCommandWithCredentials(args, workspace, cred, url);
    }

    /** {@inheritDoc} */
    public void fetch(String remoteName, RefSpec refspec) throws GitException, InterruptedException {
        fetch(remoteName, new RefSpec[] {refspec});
    }

    /** {@inheritDoc} */
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
    public CloneCommand clone_() {
        return new CloneCommand() {
            String url;
            String origin = "origin";
            String reference;
            boolean shallow,shared;
            Integer timeout;
            boolean tags = true;
            List<RefSpec> refspecs;
            Integer depth = 1;

            public CloneCommand url(String url) {
                this.url = url;
                return this;
            }

            public CloneCommand repositoryName(String name) {
                this.origin = name;
                return this;
            }

            public CloneCommand shared() {
                this.shared = true;
                return this;
            }

            public CloneCommand shallow() {
                this.shallow = true;
                return this;
            }

            public CloneCommand noCheckout() {
                //this.noCheckout = true; Since the "clone" command has been replaced with init + fetch, the --no-checkout option is always satisfied
                return this;
            }

            public CloneCommand tags(boolean tags) {
                this.tags = tags;
                return this;
            }

            public CloneCommand reference(String reference) {
                this.reference = reference;
                return this;
            }

            public CloneCommand timeout(Integer timeout) {
            	this.timeout = timeout;
            	return this;
            }

            public CloneCommand depth(Integer depth) {
                this.depth = depth;
                return this;
            }

            public CloneCommand refspecs(List<RefSpec> refspecs) {
                this.refspecs = new ArrayList<>(refspecs);
                return this;
            }

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
                    if (!referencePath.exists())
                        listener.error("Reference path does not exist: " + reference);
                    else if (!referencePath.isDirectory())
                        listener.error("Reference path is not a directory: " + reference);
                    else {
                        // reference path can either be a normal or a base repository
                        File objectsPath = new File(referencePath, ".git/objects");
                        if (!objectsPath.isDirectory()) {
                            // reference path is bare repo
                            objectsPath = new File(referencePath, "objects");
                        }
                        if (!objectsPath.isDirectory())
                            listener.error("Reference path does not contain an objects directory (no git repo?): " + objectsPath);
                        else {
                            File alternates = new File(workspace, ".git/objects/info/alternates");
                            try (PrintWriter w = new PrintWriter(alternates, Charset.defaultCharset().toString())) {
                                // git implementations on windows also use
                                w.print(objectsPath.getAbsolutePath().replace('\\', '/'));
                            } catch (UnsupportedEncodingException ex) {
                                listener.error("Default character set is an unsupported encoding");
                            } catch (FileNotFoundException e) {
                                listener.error("Failed to setup reference");
                            }
                        }
                    }
                }

                if (refspecs == null) {
                    refspecs = Collections.singletonList(new RefSpec("+refs/heads/*:refs/remotes/"+origin+"/*"));
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
    public MergeCommand merge() {
        return new MergeCommand() {
            public ObjectId rev;
            public String comment;
            public String strategy;
            public String fastForwardMode;
            public boolean squash;
            public boolean commit = true;

            public MergeCommand setRevisionToMerge(ObjectId rev) {
                this.rev = rev;
                return this;
            }

            public MergeCommand setStrategy(MergeCommand.Strategy strategy) {
                this.strategy = strategy.toString();
                return this;
            }

            public MergeCommand setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode fastForwardMode) {
                this.fastForwardMode = fastForwardMode.toString();
                return this;
            }

            public MergeCommand setSquash(boolean squash) {
                this.squash = squash;
                return this;
            }

            public MergeCommand setMessage(String comment) {
                this.comment = comment;
                return this;
            }

            public MergeCommand setCommit(boolean commit) {
                this.commit = commit;
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                ArgumentListBuilder args = new ArgumentListBuilder();
                args.add("merge");
                if(squash) {
                    args.add("--squash");
                }

                if(!commit){
                    args.add("--no-commit");
                }

                if (comment != null && !comment.isEmpty()) {
                    args.add("-m");
                    args.add(comment);
                }

                if (strategy != null && !strategy.isEmpty() && !strategy.equals(MergeCommand.Strategy.DEFAULT.toString())) {
                    args.add("-s");
                    args.add(strategy);
                }
                args.add(fastForwardMode);
                args.add(rev.name());
                launchCommand(args);
            }
        };
    }

    /**
     * rebase.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RebaseCommand} object.
     */
    public RebaseCommand rebase() {
        return new RebaseCommand() {
            private String upstream;

            public RebaseCommand setUpstream(String upstream) {
                this.upstream = upstream;
                return this;
            }

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
    public InitCommand init_() {
        return new InitCommand() {

            public String workspace;
            public boolean bare;

            public InitCommand workspace(String workspace) {
                this.workspace = workspace;
                return this;
            }

            public InitCommand bare(boolean bare) {
                this.bare = bare;
                return this;
            }

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

                if(bare) args.add("--bare");

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
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public void clean() throws GitException, InterruptedException {
        reset(true);
        launchCommand("clean", "-fdx");
    }

    /** {@inheritDoc} */
    public ObjectId revParse(String revName) throws GitException, InterruptedException {

        String arg = sanitize(revName + "^{commit}");
        String result = launchCommand("rev-parse", arg);
        String line = StringUtils.trimToNull(result);
        if (line == null)
            throw new GitException("rev-parse no content returned for " + revName);
        return ObjectId.fromString(line);
    }

    /**
     * On Windows command prompt, '^' is an escape character (http://en.wikipedia.org/wiki/Escape_character#Windows_Command_Prompt)
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
        if (isWindows())
            arg = '"'+arg+'"';
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
        if (line == null)
            throw new GitException("null result from rev-parse(" + revName +")");
        return ObjectId.fromString(line);
    }

    /** {@inheritDoc} */
    public String describe(String commitIsh) throws GitException, InterruptedException {
        String result = launchCommand("describe", "--tags", commitIsh);
        String line = firstLine(result);
        if (line == null)
            throw new GitException("null first line from describe(" + commitIsh +")");
        return line.trim();
    }

    /** {@inheritDoc} */
    public void prune(RemoteConfig repository) throws GitException, InterruptedException {
        String repoName = repository.getName();
        String repoUrl = getRemoteUrl(repoName);
        if (repoUrl != null && !repoUrl.isEmpty()) {
            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add("remote", "prune", repoName);

            StandardCredentials cred = credentials.get(repoUrl);
            if (cred == null) cred = defaultCredentials;

            try {
                launchCommandWithCredentials(args, workspace, cred, new URIish(repoUrl));
            } catch (URISyntaxException ex) {
                throw new GitException("Invalid URL " + repoUrl, ex);
            }
        }
    }

    @SuppressFBWarnings(value = "RV_DONT_JUST_NULL_CHECK_READLINE",
            justification = "Only needs first line, exception if multiple detected")
    private @CheckForNull String firstLine(String result) {
        BufferedReader reader = new BufferedReader(new StringReader(result));
        String line;
        try {
            line = reader.readLine();
            if (line == null)
                return null;
            if (reader.readLine() != null)
                throw new GitException("Result has multiple lines");
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
    public ChangelogCommand changelog() {
        return new ChangelogCommand() {

            /** Equivalent to the git-log raw format but using ISO 8601 date format - also prevent to depend on git CLI future changes */
            public static final String RAW = "commit %H%ntree %T%nparent %P%nauthor %aN <%aE> %ai%ncommitter %cN <%cE> %ci%n%n%w(76,4,4)%s%n%n%b";
            final List<String> revs = new ArrayList<>();

            Integer n = null;
            Writer out = null;

            public ChangelogCommand excludes(String rev) {
                revs.add(sanitize('^'+rev));
                return this;
            }

            public ChangelogCommand excludes(ObjectId rev) {
                return excludes(rev.name());
            }

            public ChangelogCommand includes(String rev) {
                revs.add(rev);
                return this;
            }

            public ChangelogCommand includes(ObjectId rev) {
                return includes(rev.name());
            }

            public ChangelogCommand to(Writer w) {
                this.out = w;
                return this;
            }

            public ChangelogCommand max(int n) {
                this.n = n;
                return this;
            }

            public void abort() {
                /* No cleanup needed to abort the CliGitAPIImpl ChangelogCommand */
            }

            public void execute() throws GitException, InterruptedException {
                ArgumentListBuilder args = new ArgumentListBuilder(gitExe, "whatchanged", "--no-abbrev", "-M");
                args.add("--format="+RAW);
                if (n!=null)
                    args.add("-n").add(n);
                for (String rev : this.revs)
                    args.add(rev);

                if (out==null)  throw new IllegalStateException();

                // "git whatchanged" std output gives us byte stream of data
                // Commit messages in that byte stream are UTF-8 encoded.
                // We want to decode bytestream to strings using UTF-8 encoding.
                try (WriterOutputStream w = new WriterOutputStream(out, Charset.forName("UTF-8"))) {
                    if (launcher.launch().cmds(args).envs(environment).stdout(w).stderr(listener.getLogger()).pwd(workspace).join() != 0)
                        throw new GitException("Error launching git whatchanged");
                } catch (IOException e) {
                    throw new GitException("Error launching git whatchanged",e);
                }
            }
        };
    }

    /** {@inheritDoc} */
    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException, InterruptedException {
        return showRevision(from, to, true);
    }

    /** {@inheritDoc} */
    public List<String> showRevision(ObjectId from, ObjectId to, Boolean useRawOutput) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder("log", "--full-history", "--no-abbrev", "--format=raw", "-M", "-m");
        if (useRawOutput) {
            args.add("--raw");
        }

    	if (from != null){
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
    public void submoduleInit() throws GitException, InterruptedException {
        launchCommand("submodule", "init");
    }

    /** {@inheritDoc} */
    public void addSubmodule(String remoteURL, String subdir) throws GitException, InterruptedException {
        launchCommand("submodule", "add", remoteURL, subdir);
    }

    /**
     * Sync submodule URLs
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
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
    public SubmoduleUpdateCommand submoduleUpdate() {
        return new SubmoduleUpdateCommand() {
            boolean recursive                      = false;
            boolean remoteTracking                 = false;
            boolean parentCredentials              = false;
            String  ref                            = null;
            Map<String, String> submodBranch   = new HashMap<>();
            public Integer timeout;

            public SubmoduleUpdateCommand recursive(boolean recursive) {
                this.recursive = recursive;
                return this;
            }

            public SubmoduleUpdateCommand remoteTracking(boolean remoteTracking) {
                this.remoteTracking = remoteTracking;
                return this;
            }

            public SubmoduleUpdateCommand parentCredentials(boolean parentCredentials) {
                this.parentCredentials = parentCredentials;
                return this;
            }

            public SubmoduleUpdateCommand ref(String ref) {
                this.ref = ref;
                return this;
            }

            public SubmoduleUpdateCommand useBranch(String submodule, String branchname) {
                this.submodBranch.put(submodule, branchname);
                return this;
            }

            public SubmoduleUpdateCommand timeout(Integer timeout) {
                this.timeout = timeout;
                return this;
            }

            /**
             * @throws GitException if executing the Git command fails
             * @throws InterruptedException if called methods throw same exception
             */
            public void execute() throws GitException, InterruptedException {
                // Initialize the submodules to ensure that the git config
                // contains the URLs from .gitmodules.
                submoduleInit();

                ArgumentListBuilder args = new ArgumentListBuilder();
                args.add("submodule", "update");
                if (recursive) {
                    args.add("--init", "--recursive");
                }
                if (remoteTracking && isAtLeastVersion(1,8,2,0)) {
                    args.add("--remote");

                    for (Map.Entry<String, String> entry : submodBranch.entrySet()) {
                        launchCommand("config", "-f", ".gitmodules", "submodule."+entry.getKey()+".branch", entry.getValue());
                    }
                }
                if ((ref != null) && !ref.isEmpty()) {
                    File referencePath = new File(ref);
                    if (!referencePath.exists())
                        listener.error("Reference path does not exist: " + ref);
                    else if (!referencePath.isDirectory())
                        listener.error("Reference path is not a directory: " + ref);
                    else
                        args.add("--reference", ref);
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
                    cfgOutput = launchCommand("config", "-f", ".gitmodules", "--get-regexp", "^submodule\\.(.*)\\.url");
                } catch (GitException e) {
                    listener.error("No submodules found.");
                    return;
                }

                // Use a matcher to find each configured submodule name, and
                // then run the submodule update command with the provided
                // path.
                Pattern pattern = Pattern.compile("^submodule\\.(.*)\\.url", Pattern.MULTILINE);
                Matcher matcher = pattern.matcher(cfgOutput);
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
                    if (cred == null) cred = defaultCredentials;

                    // Find the path for this submodule
                    String sModulePath = getSubmodulePath(sModuleName);

                    perModuleArgs.add(sModulePath);
                    launchCommandWithCredentials(perModuleArgs, workspace, cred, urIish, timeout);
                }
            }
        };
    }

    /**
     * Reset submodules
     *
     * @param recursive if true, will recursively reset submodules (requres git&gt;=1.6.5)
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
    public @CheckForNull String getSubmoduleUrl(String name) throws GitException, InterruptedException {
        String result = launchCommand( "config", "--get", "submodule."+name+".url" );
        return StringUtils.trim(firstLine(result));
    }

    /**
     * {@inheritDoc}
     *
     * Set submodule URL
     */
    public void setSubmoduleUrl(String name, String url) throws GitException, InterruptedException {
        launchCommand( "config", "submodule."+name+".url", url );
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
        String result = launchCommand( "config", "-f", ".gitmodules", "--get", "submodule."+name+".path" );
        return StringUtils.trim(firstLine(result));
    }

    /** {@inheritDoc} */
    public @CheckForNull String getRemoteUrl(String name) throws GitException, InterruptedException {
        String result = launchCommand( "config", "--get", "remote."+name+".url" );
        return StringUtils.trim(firstLine(result));
    }

    /** {@inheritDoc} */
    public void setRemoteUrl(String name, String url) throws GitException, InterruptedException {
        launchCommand( "config", "remote."+name+".url", url );
    }

    /** {@inheritDoc} */
    public void addRemoteUrl(String name, String url) throws GitException, InterruptedException {
        launchCommand( "config", "--add", "remote."+name+".url", url );
    }

    /** {@inheritDoc} */
    public String getRemoteUrl(String name, String GIT_DIR) throws GitException, InterruptedException {
        final String remoteNameUrl = "remote." + name + ".url";
        String result;
        if (StringUtils.isBlank(GIT_DIR)) { /* Match JGitAPIImpl */
            result = launchCommand("config", "--get", remoteNameUrl);
        } else {
            final String dirArg = "--git-dir=" + GIT_DIR;
            result = launchCommand(dirArg, "config", "--get", remoteNameUrl);
        }
        String line = firstLine(result);
        if (line == null)
            throw new GitException("No output from bare repository check for " + GIT_DIR);
        return line.trim();
    }

    /** {@inheritDoc} */
    public void setRemoteUrl(String name, String url, String GIT_DIR ) throws GitException, InterruptedException {
        launchCommand( "--git-dir=" + GIT_DIR,
                       "config", "remote."+name+".url", url );
    }


    /** {@inheritDoc} */
    public String getDefaultRemote( String _default_ ) throws GitException, InterruptedException {
        BufferedReader rdr =
            new BufferedReader(
                new StringReader( launchCommand( "remote" ) )
            );

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
        } else if ( remotes.size() >= 1 ) {
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
    public boolean isBareRepository(String GIT_DIR) throws GitException, InterruptedException {
        String ret;
        if ( "".equals(GIT_DIR) )
            ret = launchCommand(        "rev-parse", "--is-bare-repository");
        else {
            String gitDir = "--git-dir=" + GIT_DIR;
            ret = launchCommand(gitDir, "rev-parse", "--is-bare-repository");
        }
        String line = StringUtils.trimToNull(ret);
        if (line == null)
            throw new GitException("No output from bare repository check for " + GIT_DIR);

        return !"false".equals(line);
    }

    /**
     * Returns true if this repository is configured as a shallow clone.
     * Shallow clone requires command line git 1.9 or later.
     * @return true if this repository is configured as a shallow clone
     */
    public boolean isShallowRepository() {
        return new File(workspace, pathJoin(".git", "shallow")).exists();
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
            List<String> paths = new ArrayList<>();
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

    /** {@inheritDoc} */
    public void tag(String tagName, String comment) throws GitException, InterruptedException {
        tagName = tagName.replace(' ', '_');
        try {
            launchCommand("tag", "-a", "-f", "-m", comment, tagName);
        } catch (GitException e) {
            throw new GitException("Could not apply tag " + tagName, e);
        }
    }

    /** {@inheritDoc} */
    public void appendNote(String note, String namespace ) throws GitException, InterruptedException {
        createNote(note,namespace,"append");
    }

    /** {@inheritDoc} */
    public void addNote(String note, String namespace ) throws GitException, InterruptedException {
        createNote(note,namespace,"add");
    }

    private File createTempFileInSystemDir(String prefix, String suffix) throws IOException {
        if (isWindows()) {
            return Files.createTempFile(prefix, suffix).toFile();
        }
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        FileAttribute fileAttribute = PosixFilePermissions.asFileAttribute(ownerOnly);
        return Files.createTempFile(prefix, suffix, fileAttribute).toFile();
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
     * @param prefix file name prefix for the generated temporary file
     * @param suffix file name suffix for the generated temporary file
     * @return temporary file
     * @throws IOException on error
     */
    File createTempFile(String prefix, String suffix) throws IOException {
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
            return Files.createTempFile(tmpPath, prefix, suffix).toFile();
        }
        // Unix specific
        if (workspaceTmp.getAbsolutePath().contains("`")) {
            // Avoid backquote shell expansion
            return createTempFileInSystemDir(prefix, suffix);
        }
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        FileAttribute fileAttribute = PosixFilePermissions.asFileAttribute(ownerOnly);
        return Files.createTempFile(tmpPath, prefix, suffix, fileAttribute).toFile();
    }

    private void deleteTempFile(File tempFile) {
        if (tempFile != null && !tempFile.delete() && tempFile.exists()) {
            listener.getLogger().println("[WARNING] temp file " + tempFile + " not deleted");
        }
    }

    private void createNote(String note, String namespace, String command ) throws GitException, InterruptedException {
        File msg = null;
        try {
            msg = createTempFile("git-note", ".txt");
            FileUtils.writeStringToFile(msg,note);
            launchCommand("notes", "--ref=" + namespace, command, "-F", msg.getAbsolutePath());
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

    private String launchCommandWithCredentials(ArgumentListBuilder args, File workDir,
                                                StandardCredentials credentials,
                                                @NonNull String url) throws GitException, InterruptedException {
        try {
            return launchCommandWithCredentials(args, workDir, credentials, new URIish(url));
        } catch (URISyntaxException e) {
            throw new GitException("Invalid URL " + url, e);
        }
    }

    private String launchCommandWithCredentials(ArgumentListBuilder args, File workDir,
    		StandardCredentials credentials,
    		@NonNull URIish url) throws GitException, InterruptedException {
    	return launchCommandWithCredentials(args, workDir, credentials, url, TIMEOUT);
    }
    private String launchCommandWithCredentials(ArgumentListBuilder args, File workDir,
                                                StandardCredentials credentials,
                                                @NonNull URIish url,
                                                Integer timeout) throws GitException, InterruptedException {

        File key = null;
        File ssh = null;
        File pass = null;
        File askpass = null;
        EnvVars env = environment;
        try {
            if (credentials instanceof SSHUserPrivateKey) {
                SSHUserPrivateKey sshUser = (SSHUserPrivateKey) credentials;
                listener.getLogger().println("using GIT_SSH to set credentials " + sshUser.getDescription());

                key = createSshKeyFile(sshUser);
                if (launcher.isUnix()) {
                    ssh =  createUnixGitSSH(key, sshUser.getUsername());
                    pass =  createUnixSshAskpass(sshUser);
                } else {
                    ssh =  createWindowsGitSSH(key, sshUser.getUsername());
                    pass =  createWindowsSshAskpass(sshUser);
                }

                env = new EnvVars(env);
                env.put("GIT_SSH", ssh.getAbsolutePath());
                env.put("SSH_ASKPASS", pass.getAbsolutePath());

                // supply a dummy value for DISPLAY if not already present
                // or else ssh will not invoke SSH_ASKPASS
                if (!env.containsKey("DISPLAY")) {
                    env.put("DISPLAY", ":");
                }

            } else if (credentials instanceof StandardUsernamePasswordCredentials) {
                StandardUsernamePasswordCredentials userPass = (StandardUsernamePasswordCredentials) credentials;
                listener.getLogger().println("using GIT_ASKPASS to set credentials " + userPass.getDescription());

                if (launcher.isUnix()) {
                    askpass = createUnixStandardAskpass(userPass);
                } else {
                    askpass = createWindowsStandardAskpass(userPass);
                }

                env = new EnvVars(env);
                env.put("GIT_ASKPASS", askpass.getAbsolutePath());
                // SSH binary does not recognize GIT_ASKPASS, so set SSH_ASKPASS also, in the case we have an ssh:// URL
                env.put("SSH_ASKPASS", askpass.getAbsolutePath());
            }

            if ("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme())) {
                if (proxy != null) {
                    boolean shouldProxy = true;
                    for(Pattern p : proxy.getNoProxyHostPatterns()) {
                        if(p.matcher(url.getHost()).matches()) {
                            shouldProxy = false;
                            break;
                        }
                    }
                    if(shouldProxy) {
                        env = new EnvVars(env);
                        listener.getLogger().println("Setting http proxy: " + proxy.name + ":" + proxy.port);
                        String userInfo = null;
                        if (proxy.getUserName() != null) {
                            userInfo = proxy.getUserName();
                            if (proxy.getPassword() != null) {
                                userInfo += ":" + proxy.getPassword();
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
            deleteTempFile(pass);
            deleteTempFile(key);
            deleteTempFile(ssh);
            deleteTempFile(askpass);
        }
    }

    private File createSshKeyFile(SSHUserPrivateKey sshUser) throws IOException, InterruptedException {
        File key = createTempFile("ssh", ".key");
        try (PrintWriter w = new PrintWriter(key, Charset.defaultCharset().toString())) {
            List<String> privateKeys = sshUser.getPrivateKeys();
            for (String s : privateKeys) {
                w.println(s);
            }
        }
        new FilePath(key).chmod(0400);
        return key;
    }

    /* package protected for testability */
    String escapeWindowsCharsForUnquotedString(String str) {
        // Quote special characters for Windows Batch Files
        // See: http://stackoverflow.com/questions/562038/escaping-double-quotes-in-batch-script
        // See: http://ss64.com/nt/syntax-esc.html
        String quoted = str.replace("%", "%%")
                        .replace("^", "^^")
                        .replace(" ", "^ ")
                        .replace("\t", "^\t")
                        .replace("\\", "^\\")
                        .replace("&", "^&")
                        .replace("|", "^|")
                        .replace("\"", "^\"")
                        .replace(">", "^>")
                        .replace("<", "^<");
        return quoted;
    }

    private String quoteUnixCredentials(String str) {
        // Assumes string will be used inside of single quotes, as it will
        // only replace "'" substrings.
        return str.replace("'", "'\\''");
    }

    private File createWindowsSshAskpass(SSHUserPrivateKey sshUser) throws IOException {
        File ssh = createTempFile("pass", ".bat");
        try (PrintWriter w = new PrintWriter(ssh, Charset.defaultCharset().toString())) {
            // avoid echoing command as part of the password
            w.println("@echo off");
            // no surrounding double quotes on windows echo -- they are echoed too
            w.println("echo " + escapeWindowsCharsForUnquotedString(Secret.toString(sshUser.getPassphrase())));
            w.flush();
        }
        ssh.setExecutable(true, true);
        return ssh;
    }

    private File createUnixSshAskpass(SSHUserPrivateKey sshUser) throws IOException {
        File ssh = createTempFile("pass", ".sh");
        try (PrintWriter w = new PrintWriter(ssh, Charset.defaultCharset().toString())) {
            w.println("#!/bin/sh");
            w.println("echo '" + quoteUnixCredentials(Secret.toString(sshUser.getPassphrase())) + "'");
        }
        ssh.setExecutable(true, true);
        return ssh;
    }

    /* Package protected for testability */
    File createWindowsBatFile(String userName, String password) throws IOException {
        File askpass = createTempFile("pass", ".bat");
        try (PrintWriter w = new PrintWriter(askpass, Charset.defaultCharset().toString())) {
            w.println("@set arg=%~1");
            w.println("@if (%arg:~0,8%)==(Username) echo " + escapeWindowsCharsForUnquotedString(userName));
            w.println("@if (%arg:~0,8%)==(Password) echo " + escapeWindowsCharsForUnquotedString(password));
        }
        askpass.setExecutable(true, true);
        return askpass;
    }

    private File createWindowsStandardAskpass(StandardUsernamePasswordCredentials creds) throws IOException {
        return createWindowsBatFile(creds.getUsername(), Secret.toString(creds.getPassword()));
    }

    private File createUnixStandardAskpass(StandardUsernamePasswordCredentials creds) throws IOException {
        File askpass = createTempFile("pass", ".sh");
        try (PrintWriter w = new PrintWriter(askpass, Charset.defaultCharset().toString())) {
            w.println("#!/bin/sh");
            w.println("case \"$1\" in");
            w.println("Username*) echo '" + quoteUnixCredentials(creds.getUsername()) + "' ;;");
            w.println("Password*) echo '" + quoteUnixCredentials(Secret.toString(creds.getPassword())) + "' ;;");
            w.println("esac");
        }
        askpass.setExecutable(true, true);
        return askpass;
    }

    private String getPathToExe(String userGitExe) {
        userGitExe = userGitExe.toLowerCase();

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

    /* package */ File getSSHExecutable() {
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
            sshexe = getSSHExeFromGitExeParentDir(gitPath.replace("/bin/", "/usr/bin/").replace("\\bin\\", "\\usr\\bin\\"));
            if (sshexe != null && sshexe.exists()) {
                return sshexe;
            }
            // In case we are using msysgit from the cmd directory
            // instead of the bin directory, replace cmd with bin in
            // the path while trying to find ssh.exe.
            sshexe = getSSHExeFromGitExeParentDir(gitPath.replace("/cmd/", "/bin/").replace("\\cmd\\", "\\bin\\"));
            if (sshexe != null && sshexe.exists()) {
                return sshexe;
            }
            sshexe = getSSHExeFromGitExeParentDir(gitPath.replace("/cmd/", "/usr/bin/").replace("\\cmd\\", "\\usr\\bin\\"));
            if (sshexe != null && sshexe.exists()) {
                return sshexe;
            }
            sshexe = getSSHExeFromGitExeParentDir(gitPath.replace("/mingw64/", "/").replace("\\mingw64\\", "\\"));
            if (sshexe != null && sshexe.exists()) {
                return sshexe;
            }
            sshexe = getSSHExeFromGitExeParentDir(gitPath.replace("/mingw64/bin/", "/usr/bin/").replace("\\mingw64\\bin\\", "\\usr\\bin\\"));
            if (sshexe != null && sshexe.exists()) {
                return sshexe;
            }
        }

        throw new RuntimeException("ssh executable not found. The git plugin only supports official git client http://git-scm.com/download/win");
    }

    private File createWindowsGitSSH(File key, String user) throws IOException {
        File ssh = createTempFile("ssh", ".bat");

        File sshexe = getSSHExecutable();

        try (PrintWriter w = new PrintWriter(ssh, Charset.defaultCharset().toString())) {
            w.println("@echo off");
            w.println("\"" + sshexe.getAbsolutePath() + "\" -i \"" + key.getAbsolutePath() +"\" -l \"" + user + "\" -o StrictHostKeyChecking=no %* ");
        }
        ssh.setExecutable(true, true);
        return ssh;
    }

    private File createUnixGitSSH(File key, String user) throws IOException {
        File ssh = createTempFile("ssh", ".sh");
        try (PrintWriter w = new PrintWriter(ssh, Charset.defaultCharset().toString())) {
            w.println("#!/bin/sh");
            // ${SSH_ASKPASS} might be ignored if ${DISPLAY} is not set
            w.println("if [ -z \"${DISPLAY}\" ]; then");
            w.println("  DISPLAY=:123.456");
            w.println("  export DISPLAY");
            w.println("fi");
            w.println("ssh -i \"" + key.getAbsolutePath() + "\" -l \"" + user + "\" -o StrictHostKeyChecking=no \"$@\"");
        }
        ssh.setExecutable(true, true);
        return ssh;
    }

    private String launchCommandIn(ArgumentListBuilder args, File workDir) throws GitException, InterruptedException {
        return launchCommandIn(args, workDir, environment);
    }

    private String launchCommandIn(ArgumentListBuilder args, File workDir, EnvVars env) throws GitException, InterruptedException {
    	return launchCommandIn(args, workDir, environment, TIMEOUT);
    }

    private String launchCommandIn(ArgumentListBuilder args, File workDir, EnvVars env, Integer timeout) throws GitException, InterruptedException {
        ByteArrayOutputStream fos = new ByteArrayOutputStream();
        // JENKINS-13356: capture the output of stderr separately
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        EnvVars freshEnv = new EnvVars(env);
        // If we don't have credentials, but the requested URL requires them,
        // it is possible for Git to hang forever waiting for interactive
        // credential input. Prevent this by setting GIT_ASKPASS to "echo"
        // if we haven't already set it.
        if (!env.containsKey("GIT_ASKPASS")) {
            freshEnv.put("GIT_ASKPASS", "echo");
        }
        String command = gitExe + " " + StringUtils.join(args.toCommandArray(), " ");
        try {
            args.prepend(gitExe);
            if (CALL_SETSID && launcher.isUnix() && env.containsKey("GIT_SSH") && env.containsKey("DISPLAY")) {
                /* Detach from controlling terminal for git calls with ssh authentication */
                /* GIT_SSH won't call the passphrase prompt script unless detached from controlling terminal */
                args.prepend("setsid");
            }
            listener.getLogger().println(" > " + command + (timeout != null ? TIMEOUT_LOG_PREFIX + timeout : ""));
            Launcher.ProcStarter p = launcher.launch().cmds(args.toCommandArray()).
                    envs(freshEnv).stdout(fos).stderr(err);
            if (workDir != null) p.pwd(workDir);
            int status = p.start().joinWithTimeout(timeout != null ? timeout : TIMEOUT, TimeUnit.MINUTES, listener);

            String result = fos.toString(Charset.defaultCharset().toString());
            if (status != 0) {
                throw new GitException("Command \""+command+"\" returned status code " + status + ":\nstdout: " + result + "\nstderr: "+ err.toString(Charset.defaultCharset().toString()));
            }

            return result;
        } catch (GitException | InterruptedException e) {
            throw e;
        } catch (IOException e) {
            throw new GitException("Error performing command: " + command, e);
        } catch (Throwable t) {
            throw new GitException("Error performing git command", t);
        }

    }

    /**
     * push.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     */
    public PushCommand push() {
        return new PushCommand() {
            public URIish remote;
            public String refspec;
            public boolean force;
            public boolean tags;
            public Integer timeout;

            public PushCommand to(URIish remote) {
                this.remote = remote;
                return this;
            }

            public PushCommand ref(String refspec) {
                this.refspec = refspec;
                return this;
            }

            public PushCommand force() {
                this.force = true;
                return this;
            }

            public PushCommand tags(boolean tags) {
                this.tags = tags;
                return this;
            }

            public PushCommand timeout(Integer timeout) {
                this.timeout = timeout;
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                ArgumentListBuilder args = new ArgumentListBuilder();
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

                if (!isAtLeastVersion(1,9,0,0) && isShallowRepository()) {
                    throw new GitException("Can't push from shallow repository using git client older than 1.9.0");
                }

                StandardCredentials cred = credentials.get(remote.toPrivateString());
                if (cred == null) cred = defaultCredentials;
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
    private Set<Branch> parseBranches(String fos) {
        Set<Branch> branches = new HashSet<>();
        BufferedReader rdr = new BufferedReader(new StringReader(fos));
        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                if (line.length() < 44 || !line.contains(" ")) {
                    // Line must contain 2 leading characters, branch
                    // name (at least 1 character), a space, and 40
                    // character SHA1.
                    // JENKINS-34309 found cases where a Ctrl-M was
                    // inserted into the output of
                    // "git branch -v --no-abbrev"
                    continue;
                }
                // Ignore leading 2 characters (marker for current branch)
                // Ignore line if second field is not SHA1 length (40 characters)
                // Split fields into branch name, SHA1, and rest of line
                // Fields are separated by one or more spaces
                String[] branchVerboseOutput = line.substring(2).split(" +", 3);
                if (branchVerboseOutput[1].length() == 40) {
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
    public Set<Branch> getRemoteBranches() throws GitException, InterruptedException {
        try (Repository db = getRepository()) {
            Map<String, Ref> refs = db.getAllRefs();
            Set<Branch> branches = new HashSet<>();

            for(Ref candidate : refs.values()) {
                if(candidate.getName().startsWith(Constants.R_REMOTES)) {
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
    public CheckoutCommand checkout() {
        return new CheckoutCommand() {

            public String ref;
            public String branch;
            public boolean deleteBranch;
            public List<String> sparseCheckoutPaths = Collections.emptyList();
            public Integer timeout;
            public String lfsRemote;

            public CheckoutCommand ref(String ref) {
                this.ref = ref;
                return this;
            }

            public CheckoutCommand branch(String branch) {
                this.branch = branch;
                return this;
            }

            public CheckoutCommand deleteBranchIfExist(boolean deleteBranch) {
                this.deleteBranch = deleteBranch;
                return this;
            }

            public CheckoutCommand sparseCheckoutPaths(List<String> sparseCheckoutPaths) {
                this.sparseCheckoutPaths = sparseCheckoutPaths == null ? Collections.<String>emptyList() : sparseCheckoutPaths;
                return this;
            }

            public CheckoutCommand timeout(Integer timeout) {
                this.timeout = timeout;
                return this;
            }

            public CheckoutCommand lfsRemote(String lfsRemote) {
                this.lfsRemote = lfsRemote;
                return this;
            }

            /* Allow test of index.lock cleanup when checkout is interrupted */
            private void interruptThisCheckout() throws InterruptedException {
                final File indexFile = new File(workspace.getPath() + File.separator
                        + INDEX_LOCK_FILE_PATH);
                try {
                    indexFile.createNewFile();
                } catch (IOException ex) {
                    throw new InterruptedException(ex.getMessage());
                }
                throw new InterruptedException(interruptMessage);
            }

            public void execute() throws GitException, InterruptedException {
                /* File.lastModified() limited by file system time, several
                 * popular Linux file systems only have 1 second granularity.
                 * None of the common file systems (Windows or Linux) have
                 * millisecond granularity.
                 */
                final long startTimeSeconds = (System.currentTimeMillis() / 1000) * 1000 ;
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

                    if (branch!=null && deleteBranch) {
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
                    launchCommandIn(args, workspace, checkoutEnv, timeout);

                    if (lfsRemote != null) {
                        final String url = getRemoteUrl(lfsRemote);
                        StandardCredentials cred = credentials.get(url);
                        if (cred == null) cred = defaultCredentials;
                        ArgumentListBuilder lfsArgs = new ArgumentListBuilder();
                        lfsArgs.add("lfs");
                        lfsArgs.add("pull");
                        lfsArgs.add(lfsRemote);
                        try {
                            launchCommandWithCredentials(lfsArgs, workspace, cred, new URIish(url), timeout);
                        } catch (URISyntaxException e) {
                            throw new GitException("Invalid URL " + url, e);
                        }
                    }
                } catch (GitException e) {
                    if (Pattern.compile("index\\.lock").matcher(e.getMessage()).find()) {
                        throw new GitLockFailedException("Could not lock repository. Please try again", e);
                    } else {
                        if (branch != null)
                            throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
                        else
                            throw new GitException("Could not checkout " + ref, e);
                    }
                } catch (InterruptedException e) {
                    final File indexFile = new File(workspace.getPath() + File.separator
                            + INDEX_LOCK_FILE_PATH);
                    if (indexFile.exists() && indexFile.lastModified() >= startTimeSeconds) {
                        // If lock file is created before checkout command
                        // started, it is not created by this checkout command
                        // and we should leave it in place
                        try {
                            FileUtils.forceDelete(indexFile);
                        } catch (IOException ioe) {
                            throw new GitException(
                                    "Could not remove index lock file on interrupting thread", ioe);
                        }
                    }
                    throw e;
                }
            }

            private void sparseCheckout(@NonNull List<String> paths) throws GitException, InterruptedException {

                boolean coreSparseCheckoutConfigEnable;
                try {
                    coreSparseCheckoutConfigEnable = launchCommand("config", "core.sparsecheckout").contains("true");
                } catch (GitException ge) {
                    coreSparseCheckoutConfigEnable = false;
                }

                boolean deactivatingSparseCheckout = false;
                if(paths.isEmpty() && ! coreSparseCheckoutConfigEnable) { // Nothing to do
                    return;
                } else if(paths.isEmpty() && coreSparseCheckoutConfigEnable) { // deactivating sparse checkout needed
                    deactivatingSparseCheckout = true;
                    paths = Lists.newArrayList("/*");
                } else if(! coreSparseCheckoutConfigEnable) { // activating sparse checkout
                    launchCommand( "config", "core.sparsecheckout", "true" );
                }

                File sparseCheckoutDir = new File(workspace, SPARSE_CHECKOUT_FILE_DIR);
                if (!sparseCheckoutDir.exists() && !sparseCheckoutDir.mkdir()) {
                    throw new GitException("Impossible to create sparse checkout dir " + sparseCheckoutDir.getAbsolutePath());
                }

                File sparseCheckoutFile = new File(workspace, SPARSE_CHECKOUT_FILE_PATH);
                try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(Files.newOutputStream(sparseCheckoutFile.toPath()), "UTF-8"))) {
		    for(String path : paths) {
			writer.println(path);
		    }
                } catch (IOException ex){
                    throw new GitException("Could not write sparse checkout file " + sparseCheckoutFile.getAbsolutePath(), ex);
                }

                try {
                    launchCommand( "read-tree", "-mu", "HEAD" );
                } catch (GitException ge) {
                    // Normal return code if sparse checkout path has never exist on the current checkout branch
                    String normalReturnCode = "128";
                    if(ge.getMessage().contains(normalReturnCode)) {
                        listener.getLogger().println(ge.getMessage());
                    } else {
                        throw ge;
                    }
                }

                if(deactivatingSparseCheckout) {
                    launchCommand( "config", "core.sparsecheckout", "false" );
                }
            }
        };
    }

    /** {@inheritDoc} */
    public boolean tagExists(String tagName) throws GitException, InterruptedException {
        return launchCommand("tag", "-l", tagName).trim().equals(tagName);
    }

    /** {@inheritDoc} */
    public void deleteBranch(String name) throws GitException, InterruptedException {
        try {
            launchCommand("branch", "-D", name);
        } catch (GitException e) {
            throw new GitException("Could not delete branch " + name, e);
        }

    }


    /** {@inheritDoc} */
    public void deleteTag(String tagName) throws GitException, InterruptedException {
        tagName = tagName.replace(' ', '_');
        try {
            launchCommand("tag", "-d", tagName);
        } catch (GitException e) {
            throw new GitException("Could not delete tag " + tagName, e);
        }
    }

    /** {@inheritDoc} */
    public List<IndexEntry> lsTree(String treeIsh, boolean recursive) throws GitException, InterruptedException {
        List<IndexEntry> entries = new ArrayList<>();
        String result = launchCommand("ls-tree", recursive?"-r":null, treeIsh);

        BufferedReader rdr = new BufferedReader(new StringReader(result));
        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                String[] entry = line.split("\\s+");
                entries.add(new IndexEntry(entry[0], entry[1], entry[2],
                                           entry[3]));
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
    public RevListCommand revList_() {
        return new RevListCommand() {
            public boolean all;
            public boolean firstParent;
            public String refspec;
            public List<ObjectId> out;

            public RevListCommand all() {
                this.all = true;
                return this;
            }

            public RevListCommand firstParent() {
                this.firstParent = true;
                return this;
            }

            public RevListCommand to(List<ObjectId> revs){
                this.out = revs;
                return this;
            }

            public RevListCommand reference(String reference){
                this.refspec = reference;
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                ArgumentListBuilder args = new ArgumentListBuilder("rev-list");

                if (firstParent) {
                   args.add("--first-parent");
                }

                if (all) {
                   args.add("--all");
                }

                if (refspec != null) {
                   args.add(refspec);
                }

                String result = launchCommand(args);
                BufferedReader rdr = new BufferedReader(new StringReader(result));
                String line;

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
    public List<ObjectId> revListAll() throws GitException, InterruptedException {
        List<ObjectId> oidList = new ArrayList<>();
        RevListCommand revListCommand = revList_();
        revListCommand.all();
        revListCommand.to(oidList);
        revListCommand.execute();
        return oidList;
    }

    /** {@inheritDoc} */
    public List<ObjectId> revList(String ref) throws GitException, InterruptedException {
        List<ObjectId> oidList = new ArrayList<>();
        RevListCommand revListCommand = revList_();
        revListCommand.reference(ref);
        revListCommand.to(oidList);
        revListCommand.execute();
        return oidList;
    }

    /** {@inheritDoc} */
    public boolean isCommitInRepo(ObjectId commit) throws InterruptedException {
        if (commit == null) {
            return false;
        }
        try {
            List<ObjectId> revs = revList(commit.name());

            return revs.size() != 0;
        } catch (GitException e) {
            return false;
        }
    }

    /** {@inheritDoc} */
    public void add(String filePattern) throws GitException, InterruptedException {
        try {
            launchCommand("add", filePattern);
        } catch (GitException e) {
            throw new GitException("Cannot add " + filePattern, e);
        }
    }

    /** {@inheritDoc} */
    public void branch(String name) throws GitException, InterruptedException {
        try {
            launchCommand("branch", name);
        } catch (GitException e) {
            throw new GitException("Cannot create branch " + name, e);
        }
    }

    /** {@inheritDoc} */
    public void commit(String message) throws GitException, InterruptedException {
        File f = null;
        try {
            f = createTempFile("gitcommit", ".txt");
            try (OutputStream out = Files.newOutputStream(f.toPath())) {
                out.write(message.getBytes(Charset.defaultCharset().toString()));
            }
            launchCommand("commit", "-F", f.getAbsolutePath());

        } catch (GitException e) {
            throw new GitException("Cannot commit " + message, e);
        } catch (FileNotFoundException e) {
            throw new GitException("Cannot commit " + message, e);
        } catch (IOException e) {
            throw new GitException("Cannot commit " + message, e);
        } finally {
            deleteTempFile(f);
        }
    }

    /** {@inheritDoc} */
    public void addCredentials(String url, StandardCredentials credentials) {
        this.credentials.put(url, credentials);
    }

    /**
     * clearCredentials.
     */
    public void clearCredentials() {
        this.credentials.clear();
    }

    /** {@inheritDoc} */
    public void addDefaultCredentials(StandardCredentials credentials) {
        this.defaultCredentials = credentials;
    }

    /** {@inheritDoc} */
    public void setAuthor(String name, String email) throws GitException {
        env("GIT_AUTHOR_NAME", name);
        env("GIT_AUTHOR_EMAIL", email);
    }

    /** {@inheritDoc} */
    public void setCommitter(String name, String email) throws GitException {
        env("GIT_COMMITTER_NAME", name);
        env("GIT_COMMITTER_EMAIL", email);
    }

    private void env(String name, String value) {
        if (value==null)    environment.remove(name);
        else                environment.put(name,value);
    }

    /**
     * Returns the {@link org.eclipse.jgit.lib.Repository} used by this git instance.
     *
     * @return a {@link org.eclipse.jgit.lib.Repository} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    @NonNull
    public Repository getRepository() throws GitException {
        try {
            return FileRepositoryBuilder.create(new File(workspace, Constants.DOT_GIT));
        } catch (IOException e) {
            throw new GitException("Failed to open Git repository " + workspace, e);
        }
    }

    /**
     * getWorkTree.
     *
     * @return a {@link hudson.FilePath} object.
     */
    public FilePath getWorkTree() {
        return new FilePath(workspace);
    }

    /** {@inheritDoc} */
    public Set<String> getRemoteTagNames(String tagPattern) throws GitException {
        try {
            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add("ls-remote", "--tags");
            args.add(getRemoteUrl("origin"));
            if (tagPattern != null)
                args.add(tagPattern);
            String result = launchCommandIn(args, workspace);
            Set<String> tags = new HashSet<>();
            BufferedReader rdr = new BufferedReader(new StringReader(result));
            String tag;
            while ((tag = rdr.readLine()) != null) {
                // Add the tag name without the SHA1
                tags.add(tag.replaceFirst(".*refs/tags/", ""));
            }
            return tags;
        } catch (Exception e) {
            throw new GitException("Error retrieving remote tag names", e);
        }
    }

    /** {@inheritDoc} */
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
        } catch (Exception e) {
            throw new GitException("Error retrieving tag names", e);
        }
    }

    /** {@inheritDoc} */
    public String getTagMessage(String tagName) throws GitException, InterruptedException {
        // 10000 lines of tag message "ought to be enough for anybody"
        String out = launchCommand("tag", "-l", tagName, "-n10000");
        // Strip the leading four spaces which git prefixes multi-line messages with
        return out.substring(tagName.length()).replaceAll("(?m)(^    )", "").trim();
    }

    /** {@inheritDoc} */
    public void ref(String refName) throws GitException, InterruptedException {
	refName = refName.replace(' ', '_');
	try {
	    launchCommand("update-ref", refName, "HEAD");
	} catch (GitException e) {
	    throw new GitException("Could not apply ref " + refName, e);
	}
    }

    /** {@inheritDoc} */
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
    public void deleteRef(String refName) throws GitException, InterruptedException {
	refName = refName.replace(' ', '_');
	try {
	    launchCommand("update-ref", "-d", refName);
	} catch (GitException e) {
	    throw new GitException("Could not delete ref " + refName, e);
	}
    }

    /** {@inheritDoc} */
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
    public Map<String, ObjectId> getHeadRev(String url) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder("ls-remote");
        args.add("-h");
        args.add(url);

        StandardCredentials cred = credentials.get(url);
        if (cred == null) cred = defaultCredentials;

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
    public ObjectId getHeadRev(String url, String branchSpec) throws GitException, InterruptedException {
        final String branchName = extractBranchNameFromBranchSpec(branchSpec);
        ArgumentListBuilder args = new ArgumentListBuilder("ls-remote");
        if(!branchName.startsWith("refs/tags/")) {
            args.add("-h");
        }

        StandardCredentials cred = credentials.get(url);
        if (cred == null) cred = defaultCredentials;

        args.add(url);
        if (branchName.startsWith("refs/tags/")) {
            args.add(branchName+"^{}"); // JENKINS-23299 - tag SHA1 needs to be converted to commit SHA1
        } else {
            args.add(branchName);
        }
        String result = launchCommandWithCredentials(args, null, cred, url);
        return result.length()>=40 ? ObjectId.fromString(result.substring(0, 40)) : null;
    }

    /** {@inheritDoc} */
    public Map<String, ObjectId> getRemoteReferences(String url, String pattern, boolean headsOnly, boolean tagsOnly)
            throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder("ls-remote");
        if (headsOnly) {
            args.add("-h");
        }
        if (tagsOnly) {
            args.add("-t");
        }
        args.add(url);
        if (pattern != null) {
            args.add(pattern);
        }

        StandardCredentials cred = credentials.get(url);
        if (cred == null) cred = defaultCredentials;

        String result = launchCommandWithCredentials(args, null, cred, url);

        Map<String, ObjectId> references = new HashMap<>();
        String[] lines = result.split("\n");
        for (String line : lines) {
            if (line.length() < 41) throw new GitException("unexpected ls-remote output " + line);
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
            args.add(url);
            if (pattern != null) {
                args.add(pattern);
            }

            StandardCredentials cred = credentials.get(url);
            if (cred == null) cred = defaultCredentials;

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
    public void merge(String refSpec) throws GitException, InterruptedException {
        try {
            launchCommand("merge", refSpec);
        } catch (GitException e) {
            throw new GitException("Could not merge " + refSpec, e);
        }
    }



    /** {@inheritDoc} */
    @Deprecated
    public void push(RemoteConfig repository, String refspec) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        URIish uri = repository.getURIs().get(0);
        String url = uri.toPrivateString();
        StandardCredentials cred = credentials.get(url);
        if (cred == null) cred = defaultCredentials;

        args.add("push", url);

        if (refspec != null)
            args.add(refspec);

        launchCommandWithCredentials(args, workspace, cred, uri);
        // Ignore output for now as there's many different formats
        // That are possible.

    }

    /** {@inheritDoc} */
    @Deprecated
    public List<Branch> getBranchesContaining(String revspec) throws GitException,
            InterruptedException {
        // For backward compatibility we do query remote branches here
        return getBranchesContaining(revspec, true);
    }

    /** {@inheritDoc} */
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
    public ObjectId mergeBase(ObjectId id1, ObjectId id2) throws InterruptedException {
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
        } catch (IOException | GitException e) {
            throw new GitException("Error parsing merge base", e);
        }

        return null;
    }

    /** {@inheritDoc} */
    @Deprecated
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
    public static final int TIMEOUT = Integer.getInteger(Git.class.getName() + ".timeOut", 10);

    /** inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue */
    private boolean isWindows() {
        return File.pathSeparatorChar==';';
    }

    /* Return true if setsid program exists */
    static private boolean setsidExists() {
        if (File.pathSeparatorChar == ';') {
            return false;
        }
        String[] prefixes = { "/usr/local/bin/", "/usr/bin/", "/bin/", "/usr/local/sbin/", "/usr/sbin/", "/sbin/" };
        for (String prefix : prefixes) {
            File setsidFile = new File(prefix + "setsid");
            if (setsidFile.exists()) {
                return true;
            }
        }
        return false;
    }
}
