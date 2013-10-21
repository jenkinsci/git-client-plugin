package org.jenkinsci.plugins.gitclient;

import com.cloudbees.jenkins.plugins.sshagent.RemoteAgent;
import com.cloudbees.jenkins.plugins.sshagent.RemoteAgentFactory;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.common.collect.Lists;
import hudson.*;
import hudson.Launcher.LocalLauncher;
import hudson.model.TaskListener;
import hudson.plugins.git.*;
import hudson.util.ArgumentListBuilder;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
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
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation class using command line CLI ran as external command.
 * <b>
 * For internal use only, dont use directly. See {@link Git}
 * </b>
 */
public class CliGitAPIImpl extends LegacyCompatibleGitAPIImpl {

    static final String SPARSE_CHECKOUT_FILE_PATH = ".git/info/sparse-checkout";

    Launcher launcher;
    TaskListener listener;
    String gitExe;
    EnvVars environment;
    private Map<String, StandardCredentials> credentials = new HashMap<String, StandardCredentials>();
    private StandardCredentials defaultCredentials;

    protected CliGitAPIImpl(String gitExe, File workspace,
                         TaskListener listener, EnvVars environment) {
        super(workspace);
        this.listener = listener;
        this.gitExe = gitExe;
        this.environment = environment;

        launcher = new LocalLauncher(IGitAPI.verbose?listener:TaskListener.NULL);
    }

    public GitClient subGit(String subdir) {
        return new CliGitAPIImpl(gitExe, new File(workspace, subdir), listener, environment);
    }

    private int[] getGitVersion() throws InterruptedException {
        int minorVer = 1;
        int majorVer = 6;

        try {
            String v = firstLine(launchCommand("--version")).trim();
            listener.getLogger().println("git --version\n" + v);
            Pattern p = Pattern.compile("git version ([0-9]+)\\.([0-9+])\\..*");
            Matcher m = p.matcher(v);
            if (m.matches() && m.groupCount() >= 2) {
                try {
                    majorVer = Integer.parseInt(m.group(1));
                    minorVer = Integer.parseInt(m.group(2));
                } catch (NumberFormatException e) { }
            }
        } catch(GitException ex) {
            listener.getLogger().println("Error trying to determine the git version: " + ex.getMessage());
            listener.getLogger().println("Assuming 1.6");
        }

        return new int[]{majorVer,minorVer};
    }

    public void init() throws GitException, InterruptedException {
        if (hasGitRepo()) {
            throw new GitException(".git directory already exists! Has it already been initialised?");
        }
        Repository repo = getRepository();
        try {
            repo.create();
        } catch (IOException ioe) {
            throw new GitException("Error initiating git repo.", ioe);
        } finally {
            repo.close();
        }
    }

    public boolean hasGitRepo() throws GitException, InterruptedException {
        if (hasGitRepo(".git")) {
            // Check if this is actually a valid git repo by checking ls-files. If it's duff, this will
            // fail. HEAD is not guaranteed to be valid (e.g. new repo).
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

    public void fetch(URIish url, List<RefSpec> refspecs) throws GitException, InterruptedException {
        listener.getLogger().println(
                "Fetching upstream changes from " + url);

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("fetch", "-t");

        StandardCredentials cred = credentials.get(url.toPrivateString());
        if (cred == null) cred = defaultCredentials;
        String urlWithCrendentials = getURLWithCrendentials(url, cred);
        args.add(urlWithCrendentials);

        if (refspecs != null)
            for (RefSpec rs: refspecs)
                if (rs != null)
                    args.add(rs.toString());

        launchCommandWithCredentials(args, workspace, cred, urlWithCrendentials, url.toString());

    }

    public void fetch(String remoteName, RefSpec... refspec) throws GitException, InterruptedException {
        listener.getLogger().println(
                                     "Fetching upstream changes"
                                     + (remoteName != null ? " from " + remoteName : ""));

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("fetch", "-t");

        if (remoteName == null)
            remoteName = getDefaultRemote();

        args.add(remoteName);
        if (refspec != null && refspec.length > 0)
            for (RefSpec rs: refspec)
                if (rs != null)
                    args.add(rs.toString());


        StandardCredentials cred = credentials.get(getRemoteUrl(remoteName));
        if (cred == null) cred = defaultCredentials;
        launchCommandWithCredentials(args, workspace, cred, null, null);
    }

    public void fetch(String remoteName, RefSpec refspec) throws GitException, InterruptedException {
        fetch(remoteName, new RefSpec[] {refspec});
    }

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

    public CloneCommand clone_() {
        return new CloneCommand() {
            String url;
            String origin;
            String reference;
            boolean shallow,shared,noCheckout;

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
                this.noCheckout = true;
                return this;
            }

            public CloneCommand reference(String reference) {
                this.reference = reference;
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                listener.getLogger().println("Cloning repository " + url);
                final int[] gitVer = getGitVersion();

                // TODO: Not here!
                try {
                    Util.deleteContentsRecursive(workspace);
                } catch (Exception e) {
                    e.printStackTrace(listener.error("Failed to clean the workspace"));
                    throw new GitException("Failed to delete workspace", e);
                }

                try {
                    final ArgumentListBuilder args = new ArgumentListBuilder();
                    args.add("clone");
                    if ((gitVer[0] >= 1) && (gitVer[1] >= 7)) {
                        args.add("--progress");
                    }
                    if (reference != null && !reference.equals("")) {
                        File referencePath = new File(reference);
                        if (!referencePath.exists())
                            listener.error("Reference path does not exist: " + reference);
                        else if (!referencePath.isDirectory())
                            listener.error("Reference path is not a directory: " + reference);
                        else
                            args.add("--reference", reference);

                    }
                    args.add("-o", origin);
                    if (shared)
                        args.add("--shared");
                    if(shallow) args.add("--depth", "1");
                    if(noCheckout) args.add("--no-checkout");

                    StandardCredentials cred = credentials.get(url);
                    if (cred == null) cred = defaultCredentials;

                    String urlWithCrendentials = getURLWithCrendentials(url, cred);
                    args.add(urlWithCrendentials);
                    args.add(workspace);

                    launchCommandWithCredentials(args, null, cred, urlWithCrendentials, url);
                } catch (Exception e) {
                    throw new GitException("Could not clone " + url, e);
                }
            }

        };
    }

    public MergeCommand merge() {
        return new MergeCommand() {
            public ObjectId rev;
            public String strategy;

            public MergeCommand setRevisionToMerge(ObjectId rev) {
                this.rev = rev;
                return this;
            }

            public MergeCommand setStrategy(MergeCommand.Strategy strategy) {
                this.strategy = strategy.toString();
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                try {
                    if (strategy != null && !strategy.isEmpty() && !strategy.equals(MergeCommand.Strategy.DEFAULT.toString())) {
                        launchCommand("merge", "-s", strategy, rev.name()); }
                    else {
                        launchCommand("merge", rev.name()); }
                } catch (GitException e) {
                    throw new GitException("Could not merge " + rev, e);
                }
            }
        };
    }

    public void clean() throws GitException, InterruptedException {
        reset(true);
        launchCommand("clean", "-fdx");
    }

    public ObjectId revParse(String revName) throws GitException, InterruptedException {
        /*
            On Windows command prompt, '^' is an escape character (http://en.wikipedia.org/wiki/Escape_character#Windows_Command_Prompt)
            This isn't a problem if 'git' we are executing is git.exe, because '^' is a special character only for the command processor,
            but if 'git' we are executing is git.cmd (which is the case of msysgit), then the arguments we pass in here ends up getting
            processed by the command processor, and so 'xyz^{commit}' becomes 'xyz{commit}' and fails.

            We work around this problem by surrounding this with double-quote on Windows.
            Unlike POSIX, where the arguments of a process is modeled as String[], Win32 API models the
            arguments of a process as a single string (see CreateProcess). When we surround one argument with a quote,
            java.lang.ProcessImpl on Windows preserve as-is and generate a single string like the following to pass to CreateProcess:

                git rev-parse "tag^{commit}"

            If we invoke git.exe, MSVCRT startup code in git.exe will handle escape and executes it as we expect.
            If we invoke git.cmd, cmd.exe will not eats this ^ that's in double-quote. So it works on both cases.

            Note that this is a borderline-buggy behaviour arguably. If I were implementing ProcessImpl for Windows
            in JDK, My passing a string with double-quotes around it to be expanded to the following:

               git rev-parse "\"tag^{commit}\""

            So this work around that we are doing for Windows relies on the assumption that Java runtime will not
            change this behaviour.

            Also note that on Unix we cannot do this. Similarly, other ways of quoting (like using '^^' instead of '^'
            that you do on interactive command prompt) do not work either, because MSVCRT startup won't handle
            those in the same way cmd.exe does.

            See JENKINS-13007 where this blew up on Windows users.
            See https://github.com/msysgit/msysgit/issues/36 where I filed this as a bug to msysgit.
         */
        String arg = revName + "^{commit}";
        if (Functions.isWindows())
            arg = '"'+arg+'"';
        String result = launchCommand("rev-parse", arg);
        return ObjectId.fromString(firstLine(result).trim());
    }

    public ObjectId validateRevision(String revName) throws GitException, InterruptedException {
        String result = launchCommand("rev-parse", "--verify", revName);
        return ObjectId.fromString(firstLine(result).trim());
    }

    public String describe(String commitIsh) throws GitException, InterruptedException {
        String result = launchCommand("describe", "--tags", commitIsh);
        return firstLine(result).trim();
    }

    public void prune(RemoteConfig repository) throws GitException, InterruptedException {
        if (getRemoteUrl(repository.getName()) != null &&
            !getRemoteUrl(repository.getName()).equals("")) {
            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add("remote", "prune", repository.getName());

            launchCommand(args);
        }
    }

    private String firstLine(String result) {
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

    public ChangelogCommand changelog() {
        return new ChangelogCommand() {
            final List<String> revs = new ArrayList<String>();
            Integer n = null;
            Writer out = null;

            public ChangelogCommand excludes(String rev) {
                revs.add('^' + rev);
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

            public void execute() throws GitException, InterruptedException {
                ArgumentListBuilder args = new ArgumentListBuilder(gitExe, "whatchanged", "--no-abbrev", "-M", "--pretty=raw");
                if (n!=null)
                    args.add("-n").add(n);
                for (String rev : this.revs)
                    args.add(rev);

                if (out==null)  throw new IllegalStateException();

                try {
                    WriterOutputStream w = new WriterOutputStream(out);
                    try {
                        if (launcher.launch().cmds(args).envs(environment).stdout(w).stderr(listener.getLogger()).pwd(workspace).join() != 0)
                            throw new GitException("Error launching git whatchanged");
                    } finally {
                        w.flush();
                    }
                } catch (IOException e) {
                    throw new GitException("Error launching git whatchanged",e);
                }
            }
        };
    }

    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder("log", "--full-history", "--no-abbrev", "--format=raw", "-M", "-m", "--raw");
    	if (from != null){
            args.add(from.name() + ".." + to.name());
        } else {
            args.add("-1", to.name());
    	}

        StringWriter writer = new StringWriter();
        writer.write(launchCommand(args));
        return new ArrayList<String>(Arrays.asList(writer.toString().split("\\n")));
    }

    public void submoduleInit() throws GitException, InterruptedException {
        launchCommand("submodule", "init");
    }

    public void addSubmodule(String remoteURL, String subdir) throws GitException, InterruptedException {
        launchCommand("submodule", "add", remoteURL, subdir);
    }

    /**
     * Sync submodule URLs
     */
    public void submoduleSync() throws GitException, InterruptedException {
        // Check if git submodule has sync support.
        // Only available in git 1.6.1 and above
        launchCommand("submodule", "sync");
    }


    /**
     * Update submodules.
     *
     * @param recursive if true, will recursively update submodules (requires git>=1.6.5)
     *
     * @throws GitException if executing the Git command fails
     */
    public void submoduleUpdate(boolean recursive) throws GitException, InterruptedException {
        submoduleUpdate(recursive, null);
    }

    public void submoduleUpdate(boolean recursive, String reference) throws GitException, InterruptedException {
    	ArgumentListBuilder args = new ArgumentListBuilder();
    	args.add("submodule", "update");
    	if (recursive) {
            args.add("--init", "--recursive");
        }
        if (reference != null && !reference.equals("")) {
            File referencePath = new File(reference);
            if (!referencePath.exists())
                listener.error("Reference path does not exist: " + reference);
            else if (!referencePath.isDirectory())
                listener.error("Reference path is not a directory: " + reference);
            else
                args.add("--reference", reference);
        }

        launchCommand(args);
    }

    /**
     * Reset submodules
     *
     * @param recursive if true, will recursively reset submodules (requres git>=1.6.5)
     *
     * @throws GitException if executing the git command fails
     */
    public void submoduleReset(boolean recursive, boolean hard) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("submodule", "foreach");
        if (recursive) {
            args.add("--recursive");
        }
        args.add("git reset");
        if (hard) {
            args.add("--hard");
        }

        launchCommand(args);
    }

    /**
     * Cleans submodules
     *
     * @param recursive if true, will recursively clean submodules (requres git>=1.6.5)
     *
     * @throws GitException if executing the git command fails
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
     * Get submodule URL
     *
     * @param name The name of the submodule
     *
     * @throws GitException if executing the git command fails
     */
    public String getSubmoduleUrl(String name) throws GitException, InterruptedException {
        String result = launchCommand( "config", "--get", "submodule."+name+".url" );
        return firstLine(result).trim();
    }

    /**
     * Set submodule URL
     *
     * @param name The name of the submodule
     *
     * @param url The new value of the submodule's URL
     *
     * @throws GitException if executing the git command fails
     */
    public void setSubmoduleUrl(String name, String url) throws GitException, InterruptedException {
        launchCommand( "config", "submodule."+name+".url", url );
    }

    public String getRemoteUrl(String name) throws GitException, InterruptedException {
        String result = launchCommand( "config", "--get", "remote."+name+".url" );
        return firstLine(result).trim();
    }

    public void setRemoteUrl(String name, String url) throws GitException, InterruptedException {
        StandardCredentials cred = credentials.get(url);
        if (cred == null) cred = defaultCredentials;

        url = getURLWithCrendentials(url, (UsernamePasswordCredentialsImpl) cred);
        launchCommand( "config", "remote."+name+".url", url );
    }


    public String getRemoteUrl(String name, String GIT_DIR) throws GitException, InterruptedException {
        String result
            = launchCommand("--git-dir=" + GIT_DIR,
                "config", "--get", "remote." + name + ".url");
        return firstLine(result).trim();
    }

    public void setRemoteUrl(String name, String url, String GIT_DIR ) throws GitException, InterruptedException {
        launchCommand( "--git-dir=" + GIT_DIR,
                       "config", "remote."+name+".url", url );
    }


    public String getDefaultRemote( String _default_ ) throws GitException, InterruptedException {
        BufferedReader rdr =
            new BufferedReader(
                new StringReader( launchCommand( "remote" ) )
            );

        List<String> remotes = new ArrayList<String>();

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
     * @return "origin" if it exists, otherwise return the first remote.
     *
     * @throws GitException if executing the git command fails
     */
    public String getDefaultRemote() throws GitException, InterruptedException {
        return getDefaultRemote("origin");
    }

    public boolean isBareRepository(String GIT_DIR) throws GitException, InterruptedException {
        String ret;
        if ( "".equals(GIT_DIR) )
            ret = launchCommand(        "rev-parse", "--is-bare-repository");
        else {
            String gitDir = "--git-dir=" + GIT_DIR;
            ret = launchCommand(gitDir, "rev-parse", "--is-bare-repository");
        }

        return !"false".equals(firstLine(ret).trim());
    }

    private String pathJoin( String a, String b ) {
        return new File(a, b).toString();
    }

    /**
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
     *
     * @param listener The task listener.
     *
     * @throws GitException if executing the git command fails
     */
    public void fixSubmoduleUrls( String remote,
                                  TaskListener listener ) throws GitException, InterruptedException {
        boolean is_bare = true;

        URI origin;
        try {
            String url = getRemoteUrl(remote);

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
            throw new GitException("Could determine remote.origin.url", e);
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

    /**
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

    public void tag(String tagName, String comment) throws GitException, InterruptedException {
        tagName = tagName.replace(' ', '_');
        try {
            launchCommand("tag", "-a", "-f", "-m", comment, tagName);
        } catch (GitException e) {
            throw new GitException("Could not apply tag " + tagName, e);
        }
    }

    public void appendNote(String note, String namespace ) throws GitException, InterruptedException {
        createNote(note,namespace,"append");
    }

    public void addNote(String note, String namespace ) throws GitException, InterruptedException {
        createNote(note,namespace,"add");
    }

    private void createNote(String note, String namespace, String command ) throws GitException, InterruptedException {
        File msg = null;
        try {
            msg = File.createTempFile("git-note", "txt", workspace);
            FileUtils.writeStringToFile(msg,note);
            launchCommand("notes", "--ref=" + namespace, command, "-F", msg.getAbsolutePath());
        } catch (IOException e) {
            throw new GitException("Could not apply note " + note, e);
        } catch (GitException e) {
            throw new GitException("Could not apply note " + note, e);
        } finally {
            if (msg!=null)
                msg.delete();
        }
    }

    /**
     * Launch command using the workspace as working directory
     * @param args
     * @return command output
     * @throws GitException
     */
    public String launchCommand(ArgumentListBuilder args) throws GitException, InterruptedException {
        return launchCommandIn(args, workspace);
    }

    /**
     * Launch command using the workspace as working directory
     * @param args
     * @return command output
     * @throws GitException
     */
    public String launchCommand(String... args) throws GitException, InterruptedException {
        return launchCommand(new ArgumentListBuilder(args));
    }

    /**
     *
     * @param args
     * @param workDir
     * @param urlWithCrendentials
     * @return command output
     * @throws GitException
     */
    private String launchCommandWithCredentials(ArgumentListBuilder args, File workDir,
                                                StandardCredentials credentials,
                                                String urlWithCrendentials, String safeurl) throws GitException, InterruptedException {
        RemoteAgent agent = null;
        try {
            if (credentials != null && credentials instanceof SSHUserPrivateKey) {
                SSHUserPrivateKey sshUser = (SSHUserPrivateKey) credentials;

                for (RemoteAgentFactory factory : Jenkins.getInstance().getExtensionList(RemoteAgentFactory.class)) {
                    if (factory.isSupported(launcher, listener)) {
                        try {
                            agent = factory.start(launcher, listener);
                            for (String key : sshUser.getPrivateKeys()) {
                                agent.addIdentity(key, Secret.toString(sshUser.getPassphrase()), sshUser.getId());
                            }
                            break;
                        } catch (Throwable throwable) {
                            throwable.printStackTrace(listener.getLogger());
                        }
                    }
                }

            }

            String command = StringUtils.join(args.toCommandArray(), " ");
            if (urlWithCrendentials != null && safeurl != null) {
                command = command.replace(urlWithCrendentials, safeurl);
            }
            return launchCommandIn(args, workDir, command);
        } finally {
            if (agent != null) agent.stop();
        }
    }

    private String launchCommandIn(ArgumentListBuilder args, File workDir) throws GitException, InterruptedException {
        return launchCommandIn(args, workDir, StringUtils.join(args.toCommandArray(), " "));
    }

    private String launchCommandIn(ArgumentListBuilder args, File workDir, String publicCommand) throws GitException, InterruptedException {
        ByteArrayOutputStream fos = new ByteArrayOutputStream();
        // JENKINS-13356: capture the output of stderr separately
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        environment.put("GIT_ASKPASS", launcher.isUnix() ? "/bin/echo " : "echo ");
        try {

            args.prepend(gitExe);
            Launcher.ProcStarter p = launcher.launch().cmds(args.toCommandArray()).
                    envs(environment).stdout(fos).stderr(err);
            if (workDir != null) p.pwd(workDir);
            int status = p.join();

            String result = fos.toString();
            if (status != 0) {
                throw new GitException("Command \""+publicCommand+"\" returned status code " + status + ":\nstdout: " + result + "\nstderr: "+ err.toString());
            }

            return result;
        } catch (GitException e) {
            throw e;
        } catch (IOException e) {
            throw new GitException("Error performing command: " + publicCommand, e);
        } catch (Throwable t) {
            throw new GitException("Error performing git command", t);
        }

    }

    public void push(String remoteName, String refspec) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("push", remoteName);

        if (refspec != null)
            args.add(refspec);

        StandardCredentials cred = credentials.get(getRemoteUrl(remoteName));
        if (cred == null) cred = defaultCredentials;
        launchCommandWithCredentials(args, workspace, cred, null, null);
        // Ignore output for now as there's many different formats
        // That are possible.
    }

    protected Set<Branch> parseBranches(String fos) throws GitException, InterruptedException {
        // TODO: git branch -a -v --abbrev=0 would do this in one shot..

        Set<Branch> branches = new HashSet<Branch>();

        BufferedReader rdr = new BufferedReader(new StringReader(fos));
        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                // Ignore the 1st
                line = line.substring(2);
                // Ignore '(no branch)' or anything with " -> ", since I think
                // that's just noise
                if ((!line.startsWith("("))
                    && (line.indexOf(" -> ") == -1)) {
                    branches.add(new Branch(line, revParse(line)));
                }
            }
        } catch (IOException e) {
            throw new GitException("Error parsing branches", e);
        }

        return branches;
    }

    public Set<Branch> getBranches() throws GitException, InterruptedException {
        return parseBranches(launchCommand("branch", "-a"));
    }

    public Set<Branch> getRemoteBranches() throws GitException, InterruptedException {
        Repository db = getRepository();
        try {
            Map<String, Ref> refs = db.getAllRefs();
            Set<Branch> branches = new HashSet<Branch>();

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
        } finally {
            db.close();
        }
    }

    public CheckoutCommand checkout() {
        return new CheckoutCommand() {

            public void execute() throws GitException, InterruptedException {
                try {

                    // Will activate or deactivate sparse checkout depending on the given paths
                    sparseCheckout(sparseCheckoutPaths);

                    if (branch!=null && deleteBranch) {
                        // First, checkout to detached HEAD, so we can delete the branch.
                        launchCommand("checkout", "-f", ref);

                        // Second, check to see if the branch actually exists, and then delete it if it does.
                        for (Branch b : getBranches()) {
                            if (b.getName().equals(branch)) {
                                deleteBranch(branch);
                            }
                        }
                    }
                    if (branch != null)
                        launchCommand("checkout", "-b", branch, ref);
                    else
                        launchCommand("checkout", "-f", ref);
                } catch (GitException e) {
                    throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
                }

            }

            private void sparseCheckout(List<String> paths) throws GitException, InterruptedException {

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

                File sparseCheckoutFile = new File(workspace, SPARSE_CHECKOUT_FILE_PATH);
                PrintWriter writer;
                try {
                    writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(sparseCheckoutFile, false), "UTF-8"));
                } catch (IOException ex){
                    throw new GitException("Impossible to open sparse checkout file " + sparseCheckoutFile.getAbsolutePath());
                }

                for(String path : paths) {
                    writer.println(path);
                }

                try {
                    writer.close();
                } catch (Exception ex) {
                    throw new GitException("Impossible to close sparse checkout file " + sparseCheckoutFile.getAbsolutePath());
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

    public boolean tagExists(String tagName) throws GitException, InterruptedException {
        return launchCommand("tag", "-l", tagName).trim().equals(tagName);
    }

    public void deleteBranch(String name) throws GitException, InterruptedException {
        try {
            launchCommand("branch", "-D", name);
        } catch (GitException e) {
            throw new GitException("Could not delete branch " + name, e);
        }

    }


    public void deleteTag(String tagName) throws GitException, InterruptedException {
        tagName = tagName.replace(' ', '_');
        try {
            launchCommand("tag", "-d", tagName);
        } catch (GitException e) {
            throw new GitException("Could not delete tag " + tagName, e);
        }
    }

    public List<IndexEntry> lsTree(String treeIsh, boolean recursive) throws GitException, InterruptedException {
        List<IndexEntry> entries = new ArrayList<IndexEntry>();
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

    public List<ObjectId> revListAll() throws GitException, InterruptedException {
        return doRevList("--all");
    }

    public List<ObjectId> revList(String ref) throws GitException, InterruptedException {
        return doRevList(ref);
    }

    private List<ObjectId> doRevList(String... extraArgs) throws GitException, InterruptedException {
        List<ObjectId> entries = new ArrayList<ObjectId>();
        ArgumentListBuilder args = new ArgumentListBuilder("rev-list");
        args.add(extraArgs);
        String result = launchCommand(args);
        BufferedReader rdr = new BufferedReader(new StringReader(result));
        String line;

        try {
            while ((line = rdr.readLine()) != null) {
                // Add the SHA1
                entries.add(ObjectId.fromString(line));
            }
        } catch (IOException e) {
            throw new GitException("Error parsing rev list", e);
        }

        return entries;
    }

    public boolean isCommitInRepo(ObjectId commit) throws InterruptedException {
        try {
            List<ObjectId> revs = revList(commit.name());

            if (revs.size() == 0) {
                return false;
            } else {
                return true;
            }
        } catch (GitException e) {
            return false;
        }
    }

    public void add(String filePattern) throws GitException, InterruptedException {
        try {
            launchCommand("add", filePattern);
        } catch (GitException e) {
            throw new GitException("Cannot add " + filePattern, e);
        }
    }

    public void branch(String name) throws GitException, InterruptedException {
        try {
            launchCommand("branch", name);
        } catch (GitException e) {
            throw new GitException("Cannot create branch " + name, e);
        }
    }

    public void commit(String message) throws GitException, InterruptedException {
        File f = null;
        try {
            f = File.createTempFile("gitcommit", ".txt");
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(f);
                fos.write(message.getBytes());
            } finally {
                if (fos != null)
                    fos.close();
            }
            launchCommand("commit", "-F", f.getAbsolutePath());

        } catch (GitException e) {
            throw new GitException("Cannot commit " + message, e);
        } catch (FileNotFoundException e) {
            throw new GitException("Cannot commit " + message, e);
        } catch (IOException e) {
            throw new GitException("Cannot commit " + message, e);
        } finally {
            if (f != null) f.delete();
        }
    }

    public void addCredentials(String url, StandardCredentials credentials) {
        this.credentials.put(url, credentials);
    }

    public void clearCredentials() {
        this.credentials.clear();
    }

    public void addDefaultCredentials(StandardCredentials credentials) {
        this.defaultCredentials = credentials;
    }

    public void setAuthor(String name, String email) throws GitException {
        env("GIT_AUTHOR_NAME", name);
        env("GIT_AUTHOR_EMAIL", email);
    }

    public void setCommitter(String name, String email) throws GitException {
        env("GIT_COMMITTER_NAME", name);
        env("GIT_COMMITTER_EMAIL", email);
    }

    private void env(String name, String value) {
        if (value==null)    environment.remove(name);
        else                environment.put(name,value);
    }

    public Repository getRepository() throws GitException {
        try {
            return FileRepositoryBuilder.create(new File(workspace, Constants.DOT_GIT));
        } catch (IOException e) {
            throw new GitException("Failed to open Git repository " + workspace, e);
        }
    }

    public FilePath getWorkTree() {
        return new FilePath(workspace);
    }

    public Set<String> getTagNames(String tagPattern) throws GitException {
        try {
            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add("tag", "-l", tagPattern);

            String result = launchCommandIn(args, workspace);

            Set<String> tags = new HashSet<String>();
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

    public String getTagMessage(String tagName) throws GitException, InterruptedException {
        // 10000 lines of tag message "ought to be enough for anybody"
        String out = launchCommand("tag", "-l", tagName, "-n10000");
        // Strip the leading four spaces which git prefixes multi-line messages with
        return out.substring(tagName.length()).replaceAll("(?m)(^    )", "").trim();
    }

    public ObjectId getHeadRev(String url, String branch) throws GitException, InterruptedException {
        String[] branchExploded = branch.split("/");
        branch = branchExploded[branchExploded.length-1];
        ArgumentListBuilder args = new ArgumentListBuilder("ls-remote");
        args.add("-h");

        StandardCredentials cred = credentials.get(url);
        if (cred == null) cred = defaultCredentials;

        String urlWithCrendentials = getURLWithCrendentials(url, cred);
        args.add(urlWithCrendentials);
        args.add(branch);
        String result = launchCommandWithCredentials(args, null, cred, urlWithCrendentials, url);
        return result.length()>=40 ? ObjectId.fromString(result.substring(0, 40)) : null;
    }


    //
    //
    // Legacy Implementation of IGitAPI
    //
    //

    @Deprecated
    public void merge(String refSpec) throws GitException, InterruptedException {
        try {
            launchCommand("merge", refSpec);
        } catch (GitException e) {
            throw new GitException("Could not merge " + refSpec, e);
        }
    }



    @Deprecated
    public void push(RemoteConfig repository, String refspec) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        URIish uri = repository.getURIs().get(0);
        String remote = uri.toPrivateString();
        StandardCredentials cred = credentials.get(remote);
        if (cred == null) cred = defaultCredentials;

        String urlWithCrendentials = getURLWithCrendentials(uri, cred);
        args.add("push", urlWithCrendentials);

        if (refspec != null)
            args.add(refspec);

        launchCommandWithCredentials(args, workspace, cred, urlWithCrendentials, uri.toString());
        // Ignore output for now as there's many different formats
        // That are possible.

    }

    @Deprecated
    public List<Branch> getBranchesContaining(String revspec) throws GitException, InterruptedException {
        return new ArrayList<Branch>(parseBranches(launchCommand("branch", "-a", "--contains", revspec)));
    }

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
        } catch (IOException e) {
            throw new GitException("Error parsing merge base", e);
        } catch (GitException e) {
            throw new GitException("Error parsing merge base", e);
        }

        return null;
    }

    @Deprecated
    public String getAllLogEntries(String branch) throws InterruptedException {
        // BROKEN: --all and branch are conflicting.
        return launchCommand("log", "--all", "--pretty=format:'%H#%ct'", branch);
    }

    private String getURLWithCrendentials(String url, StandardCredentials cred) {
        try {
            return getURLWithCrendentials(new URIish(url), cred);
        } catch (URISyntaxException e) {
            throw new GitException("invalid repository URL " + url, e);
        }
    }
    private String getURLWithCrendentials(URIish u, StandardCredentials cred) {
        String scheme = u.getScheme();
        URIish uri = new URIish()
            .setScheme(scheme)
            .setUser(u.getUser())
            .setPass(u.getPass())
            .setHost(u.getHost())
            .setPort(u.getPort())
            .setPath(u.getPath());
        if (cred != null && cred instanceof UsernamePasswordCredentialsImpl) {
            UsernamePasswordCredentialsImpl up = (UsernamePasswordCredentialsImpl) cred;
            uri = uri.setUser(up.getUsername())
                     .setPass(Secret.toString(up.getPassword()));
        }

        String url = uri.toPrivateString();

        // assert http URL is accessible to avoid git process to hung asking for username
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            HttpClient client = new HttpClient();
            if (uri.getUser() != null && uri.getPass() != null) {
                client.getParams().setAuthenticationPreemptive(true);
                Credentials defaultcreds = new UsernamePasswordCredentials(uri.getUser(), uri.getPass());
                client.getState().setCredentials(AuthScope.ANY, defaultcreds);
            }
            int status = 0;
            try {
                // dump-http
                status = client.executeMethod(new GetMethod(url + "/info/refs"));
                if (status != 200)
                    // smart-http
                    status = client.executeMethod(new GetMethod(url + "/info/refs?service=git-upload-pack"));
                if (status != 200)
                    throw new GitException("Failed to connect to " + u.toString()
                        + (cred != null ? " using credentials " + cred.getDescription() : "" )
                        + " (status = "+status+")");
            } catch (IOException e) {
                throw new GitException("Failed to connect to " + u.toString()
                        + (cred != null ? " using credentials " + cred.getDescription() : "" ));
            } catch (IllegalArgumentException e) {
                throw new GitException("Invalid URL " + u.toString());
            }
        }

        return url;
    }
}
