package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitObject;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import hudson.plugins.git.Tag;
import hudson.remoting.Channel;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.RemoteWriter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.Writer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link GitClient} that delegates to a remote {@link GitClient}.
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings("deprecation") // Suppressing deprecation warnings intentionally
class RemoteGitImpl implements GitClient, hudson.plugins.git.IGitAPI, Serializable {
    private final GitClient proxy;
    private transient Channel channel;

    RemoteGitImpl(GitClient proxy) {
        this.proxy = proxy;
    }

    private hudson.plugins.git.IGitAPI getGitAPI() {
        return (hudson.plugins.git.IGitAPI)proxy;
    }

    protected Object readResolve() {
        channel = Channel.current();
        return this;
    }

    private Object writeReplace() {
        if (channel!=null)
            return proxy; // when sent back to where it came from, switch back to the original object
        return this;
    }

    static class Invocation implements Serializable {
        private final String methodName;
        private final String[] parameterTypes;
        private final Object[] args;

        Invocation(Method method, @NonNull Object[] args) {
            this.methodName = method.getName();
            this.args = args;
            this.parameterTypes = new String[args.length];
            Class[] paramTypes = method.getParameterTypes();
            for (int i=0; i<args.length; i++) {
                parameterTypes[i] = paramTypes[i].getName();
            }
            for (int i=0; i<args.length; i++) {
                if (args[i] instanceof OutputStream)
                    args[i] = new RemoteOutputStream((OutputStream)args[i]);
                if (args[i] instanceof Writer)
                    args[i] = new RemoteWriter((Writer)args[i]);
            }
        }

        public void replay(Object target) throws InvocationTargetException, IllegalAccessException {
            OUTER:
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterTypes().length==parameterTypes.length) {
                    Class<?>[] t = m.getParameterTypes();
                    for (int i=0; i<parameterTypes.length; i++) {
                        if (!t[i].getName().equals(parameterTypes[i]))
                            continue OUTER;
                    }
                    // matched
                    m.invoke(target,args);
                    return;
                }
            }
            throw new IllegalStateException("Method not found: "+methodName+"("+ Util.join(Arrays.asList(parameterTypes),",")+")");
        }

        private static final long serialVersionUID = 1L;
    }

    private <T extends GitCommand> T command(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, new CommandInvocationHandler(type,this)));
    }

    private static class CommandInvocationHandler implements InvocationHandler, GitCommand, Serializable {
        private final Class<? extends GitCommand> command;
        private final List<Invocation> invocations = new ArrayList<>();
        private transient final Channel channel;
        private final GitClient proxy;

        private CommandInvocationHandler(Class<? extends GitCommand> command, RemoteGitImpl owner) {
            this.command = command;
            this.channel = owner.channel;
            this.proxy = owner.proxy;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Class<?> decl = method.getDeclaringClass();
            if (args == null) args = new Object[0];
            if (GitCommand.class == decl || Object.class==decl) {
                try {
                    return method.invoke(this,args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            if (GitCommand.class.isAssignableFrom(decl)) {
                invocations.add(new Invocation(method, args));
                return proxy;
            }
            throw new IllegalStateException("Unexpected invocation: "+method);
        }

        public void execute() throws GitException, InterruptedException {
            try {
                channel.call(new GitCommandMasterToSlaveCallable());
            } catch (IOException e) {
                throw new GitException(e);
            }
        }

        private static final long serialVersionUID = 1L;

        private class GitCommandMasterToSlaveCallable extends jenkins.security.MasterToSlaveCallable<Void, GitException> {
            public Void call() throws GitException {
                try {
                    GitCommand cmd = createCommand();
                    for (Invocation inv : invocations) {
                        inv.replay(cmd);
                    }
                    cmd.execute();
                    return null;
                } catch (InvocationTargetException | IllegalAccessException | InterruptedException e) {
                    throw new GitException(e);
                }
            }

            private GitCommand createCommand() throws InvocationTargetException, IllegalAccessException {
                for (Method m : GitClient.class.getMethods()) {
                    if (m.getReturnType()==command && m.getParameterTypes().length==0)
                        return command.cast(m.invoke(proxy));
                }
                throw new IllegalStateException("Can't find the factory method for "+command);
            }
        }
    }

    private OutputStream wrap(OutputStream os) {
        return new RemoteOutputStream(os);
    }

    /**
     * getRepository.
     *
     * @return a {@link org.eclipse.jgit.lib.Repository} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     */
    @NonNull
    public Repository getRepository() throws GitException {
        throw new UnsupportedOperationException();
    }

    /**
     * clearCredentials.
     */
    public void clearCredentials() {
        proxy.clearCredentials();
    }

    /** {@inheritDoc} */
    public void addCredentials(String url, StandardCredentials credentials) {
        proxy.addCredentials(url, CredentialsProvider.snapshot(StandardCredentials.class, credentials)); // credentials are Serializable
    }

    /** {@inheritDoc} */
    public void setCredentials(StandardUsernameCredentials cred) {
        proxy.setCredentials(CredentialsProvider.snapshot(StandardUsernameCredentials.class, cred)); // credentials are Serializable
    }

    /** {@inheritDoc} */
    public void addDefaultCredentials(StandardCredentials credentials) {
        proxy.addDefaultCredentials(CredentialsProvider.snapshot(StandardCredentials.class, credentials)); // credentials are Serializable
    }

    /** {@inheritDoc} */
    public void setAuthor(String name, String email) throws GitException {
        proxy.setAuthor(name, email);
    }

    /** {@inheritDoc} */
    public void setAuthor(PersonIdent p) throws GitException {
        proxy.setAuthor(p);
    }

    /** {@inheritDoc} */
    public void setCommitter(String name, String email) throws GitException {
        proxy.setCommitter(name, email);
    }

    /** {@inheritDoc} */
    public void setCommitter(PersonIdent p) throws GitException {
        proxy.setCommitter(p);
    }

    /** {@inheritDoc} */
    public <T> T withRepository(RepositoryCallback<T> callable) throws IOException, InterruptedException {
        return proxy.withRepository(callable);
    }

    /**
     * getWorkTree.
     *
     * @return a {@link hudson.FilePath} object.
     */
    public FilePath getWorkTree() {
        return proxy.getWorkTree();
    }

    /**
     * init.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public void init() throws GitException, InterruptedException {
        proxy.init();
    }

    /** {@inheritDoc} */
    public void add(String filePattern) throws GitException, InterruptedException {
        proxy.add(filePattern);
    }

    /** {@inheritDoc} */
    public void commit(String message) throws GitException, InterruptedException {
        proxy.commit(message);
    }

    /** {@inheritDoc} */
    public void commit(String message, PersonIdent author, PersonIdent committer) throws GitException, InterruptedException {
        proxy.setAuthor(author);
        proxy.setCommitter(committer);
        proxy.commit(message);
    }

    /**
     * Returns true if the current workspace has a git repository.
     *
     * @return true if this workspace has a git repository
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public boolean hasGitRepo() throws GitException, InterruptedException {
        return proxy.hasGitRepo();
    }

    /**
     * Returns true if the current workspace has a git repository.
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
        return proxy.hasGitRepo(checkParentDirectories);
    }

    /** {@inheritDoc} */
    public boolean isCommitInRepo(ObjectId commit) throws GitException, InterruptedException {
        return proxy.isCommitInRepo(commit);
    }

    /** {@inheritDoc} */
    public String getRemoteUrl(String name) throws GitException, InterruptedException {
        return proxy.getRemoteUrl(name);
    }

    /** {@inheritDoc} */
    public void setRemoteUrl(String name, String url) throws GitException, InterruptedException {
        proxy.setRemoteUrl(name, url);
    }

    /** {@inheritDoc} */
    public void addRemoteUrl(String name, String url) throws GitException, InterruptedException {
        proxy.addRemoteUrl(name, url);
    }

    /** {@inheritDoc} */
    public void checkout(String ref) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.checkout(ref);
    }

    /** {@inheritDoc} */
    public void checkout(String ref, String branch) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.checkout(ref, branch);
    }

    /**
     * checkout.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CheckoutCommand} object.
     */
    public CheckoutCommand checkout() {
        return command(CheckoutCommand.class);
    }

    /** {@inheritDoc} */
    public void checkoutBranch(String branch, String ref) throws GitException, InterruptedException {
        proxy.checkoutBranch(branch, ref);
    }

    /** {@inheritDoc} */
    public ObjectId mergeBase(ObjectId sha1, ObjectId sha12) throws InterruptedException {
        return getGitAPI().mergeBase(sha1, sha12);
    }

    /** {@inheritDoc} */
    public String getAllLogEntries(String branch) throws InterruptedException {
        return getGitAPI().getAllLogEntries(branch);
    }

    /** {@inheritDoc} */
    public List<String> showRevision(Revision r) throws GitException, InterruptedException {
        return getGitAPI().showRevision(r);
    }

    /** {@inheritDoc} */
    public void clone(String url, String origin, boolean useShallowClone, String reference) throws GitException, InterruptedException {
        proxy.clone(url, origin, useShallowClone, reference);
    }

    /**
     * clone_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    public CloneCommand clone_() {
        return command(CloneCommand.class);
    }

    /**
     * merge.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.MergeCommand} object.
     */
    public MergeCommand merge() {
        return command(MergeCommand.class);
    }

    /**
     * rebase.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RebaseCommand} object.
     */
    public RebaseCommand rebase() {
       return command(RebaseCommand.class);
    }

    /**
     * init_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.InitCommand} object.
     */
    public InitCommand init_() {
        return command(InitCommand.class);
    }

    /**
     * fetch_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.FetchCommand} object.
     */
    public FetchCommand fetch_() {
        return command(FetchCommand.class);
    }

    /**
     * push.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     */
    public PushCommand push() {
        return command(PushCommand.class);
    }

    /**
     * {@inheritDoc}
     *
     * @param url a {@link org.eclipse.jgit.transport.URIish} object.
     * @param refspecs a {@link java.util.List} object.
     * @throws hudson.plugins.git.GitException if any.
     * @throws java.lang.InterruptedException if any.
     */
    public void fetch(URIish url, List<RefSpec> refspecs) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.fetch(url, refspecs);
    }

    /** {@inheritDoc} */
    public void fetch(String remoteName, RefSpec... refspec) throws GitException, InterruptedException {
        proxy.fetch(remoteName, refspec);
    }

    /** {@inheritDoc} */
    public void fetch(String remoteName, RefSpec refspec) throws GitException, InterruptedException {
        fetch(remoteName, new RefSpec[]{refspec});
    }

    /** {@inheritDoc} */
    public void push(String remoteName, String refspec) throws GitException, InterruptedException {
        proxy.push(remoteName, refspec);
    }

    /** {@inheritDoc} */
    public void push(URIish url, String refspec) throws GitException, InterruptedException {
        proxy.push(url, refspec);
    }

    /** {@inheritDoc} */
    public void merge(ObjectId rev) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.merge(rev);
    }

    /** {@inheritDoc} */
    public void prune(RemoteConfig repository) throws GitException, InterruptedException {
        proxy.prune(repository);
    }

    /**
     * clean.
     *
     * @param cleanSubmodule flag to add extra -f
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public void clean(boolean cleanSubmodule) throws GitException, InterruptedException {
        proxy.clean(cleanSubmodule);
    }

    /**
     * clean.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public void clean() throws GitException, InterruptedException {
        proxy.clean();
    }

    /** {@inheritDoc} */
    public void branch(String name) throws GitException, InterruptedException {
        proxy.branch(name);
    }

    /** {@inheritDoc} */
    public void deleteBranch(String name) throws GitException, InterruptedException {
        proxy.deleteBranch(name);
    }

    /**
     * getBranches.
     *
     * @return a {@link java.util.Set} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public Set<Branch> getBranches() throws GitException, InterruptedException {
        return proxy.getBranches();
    }

    /**
     * getRemoteBranches.
     *
     * @return a {@link java.util.Set} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public Set<Branch> getRemoteBranches() throws GitException, InterruptedException {
        return proxy.getRemoteBranches();
    }

    /** {@inheritDoc} */
    public void tag(String tagName, String comment) throws GitException, InterruptedException {
        proxy.tag(tagName, comment);
    }

    /** {@inheritDoc} */
    public boolean tagExists(String tagName) throws GitException, InterruptedException {
        return proxy.tagExists(tagName);
    }

    /** {@inheritDoc} */
    public String getTagMessage(String tagName) throws GitException, InterruptedException {
        return proxy.getTagMessage(tagName);
    }

    /** {@inheritDoc} */
    public void deleteTag(String tagName) throws GitException, InterruptedException {
        proxy.deleteTag(tagName);
    }

    /** {@inheritDoc} */
    public Set<String> getTagNames(String tagPattern) throws GitException, InterruptedException {
        return proxy.getTagNames(tagPattern);
    }

    /** {@inheritDoc} */
    public void ref(String refName) throws GitException, InterruptedException {
	proxy.ref(refName);
    }

    /** {@inheritDoc} */
    public boolean refExists(String refName) throws GitException, InterruptedException {
	return proxy.refExists(refName);
    }

    /** {@inheritDoc} */
    public void deleteRef(String refName) throws GitException, InterruptedException {
	proxy.deleteRef(refName);
    }

    /** {@inheritDoc} */
    public Set<String> getRefNames(String refPrefix) throws GitException, InterruptedException {
	return proxy.getRefNames(refPrefix);
    }

    /** {@inheritDoc} */
    public Set<String> getRemoteTagNames(String tagPattern) throws GitException, InterruptedException {
        return proxy.getTagNames(tagPattern);
    }

    /** {@inheritDoc} */
    public Map<String, ObjectId> getHeadRev(String url) throws GitException, InterruptedException {
        return proxy.getHeadRev(url);
    }

    /** {@inheritDoc} */
    public ObjectId getHeadRev(String remoteRepoUrl, String branch) throws GitException, InterruptedException {
        return proxy.getHeadRev(remoteRepoUrl, branch);
    }

    /** {@inheritDoc} */
    public Map<String, ObjectId> getRemoteReferences(String remoteRepoUrl, String pattern, boolean headsOnly, boolean tagsOnly) throws GitException, InterruptedException {
        return proxy.getRemoteReferences(remoteRepoUrl, pattern, headsOnly, tagsOnly);
    }

    /** {@inheritDoc} */
    public Map<String, String> getRemoteSymbolicReferences(String remoteRepoUrl, String pattern)
            throws GitException, InterruptedException {
        return proxy.getRemoteSymbolicReferences(remoteRepoUrl, pattern);
    }

    /** {@inheritDoc} */
    public ObjectId revParse(String revName) throws GitException, InterruptedException {
        return proxy.revParse(revName);
    }

    /**
     * revList_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     */
    public RevListCommand revList_() {
        return proxy.revList_();
    }

    /**
     * revListAll.
     *
     * @return a {@link java.util.List} object.
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public List<ObjectId> revListAll() throws GitException, InterruptedException {
        return proxy.revListAll();
    }

    /** {@inheritDoc} */
    public List<ObjectId> revList(String ref) throws GitException, InterruptedException {
        return proxy.revList(ref);
    }

    /** {@inheritDoc} */
    public GitClient subGit(String subdir) {
        return proxy.subGit(subdir);
    }

    /**
     * hasGitModules.
     *
     * @return true if this repository has submodules
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public boolean hasGitModules() throws GitException, InterruptedException {
        return proxy.hasGitModules();
    }

    /** {@inheritDoc} */
    public List<IndexEntry> getSubmodules(String treeIsh) throws GitException, InterruptedException {
        return proxy.getSubmodules(treeIsh);
    }

    /** {@inheritDoc} */
    public void addSubmodule(String remoteURL, String subdir) throws GitException, InterruptedException {
        proxy.addSubmodule(remoteURL, subdir);
    }

    /** {@inheritDoc} */
    public void submoduleUpdate(boolean recursive) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.submoduleUpdate(recursive);
    }

    /**
     * {@inheritDoc}
     *
     * @param recursive a boolean.
     * @param ref a {@link java.lang.String} object.
     * @throws hudson.plugins.git.GitException if any.
     * @throws java.lang.InterruptedException if any.
     */
    public void submoduleUpdate(boolean recursive, String ref) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.submoduleUpdate(recursive, ref);
    }

    /** {@inheritDoc} */
    public void submoduleUpdate(boolean recursive, boolean remoteTracking) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.submoduleUpdate(recursive, remoteTracking);
    }

    /** {@inheritDoc} */
    public void submoduleUpdate(boolean recursive, boolean remoteTracking, String reference) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.submoduleUpdate(recursive, remoteTracking, reference);
    }

    /**
     * submoduleUpdate.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand} object.
     */
    public SubmoduleUpdateCommand submoduleUpdate() {
        return command(SubmoduleUpdateCommand.class);
    }

    /** {@inheritDoc} */
    public void submoduleClean(boolean recursive) throws GitException, InterruptedException {
        proxy.submoduleClean(recursive);
    }

    /** {@inheritDoc} */
    public void setupSubmoduleUrls(Revision rev, TaskListener listener) throws GitException, InterruptedException {
        proxy.setupSubmoduleUrls(rev, listener);
    }

    /** {@inheritDoc} */
    public void changelog(String revFrom, String revTo, OutputStream os) throws GitException, InterruptedException {
        proxy.changelog(revFrom, revTo, wrap(os));
    }

    /**
     * {@inheritDoc}
     *
     * @param revFrom a {@link java.lang.String} object.
     * @param revTo a {@link java.lang.String} object.
     * @param os a {@link java.io.Writer} object.
     * @throws hudson.plugins.git.GitException if any.
     * @throws java.lang.InterruptedException if any.
     */
    public void changelog(String revFrom, String revTo, Writer os) throws GitException, InterruptedException {
        proxy.changelog(revFrom, revTo, os); // TODO: wrap
    }

    /**
     * changelog.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    public ChangelogCommand changelog() {
        return command(ChangelogCommand.class);
    }

    /** {@inheritDoc} */
    public void appendNote(String note, String namespace) throws GitException, InterruptedException {
        proxy.appendNote(note, namespace);
    }

    /** {@inheritDoc} */
    public void addNote(String note, String namespace) throws GitException, InterruptedException {
        proxy.addNote(note, namespace);
    }

    /** {@inheritDoc} */
    public List<String> showRevision(ObjectId r) throws GitException, InterruptedException {
        return proxy.showRevision(r);
    }

    /** {@inheritDoc} */
    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException, InterruptedException {
        return proxy.showRevision(from, to);
    }

    /** {@inheritDoc} */
    public List<String> showRevision(ObjectId from, ObjectId to, Boolean useRawOutput) throws GitException, InterruptedException {
        return proxy.showRevision(from, to, useRawOutput);
    }

    /** {@inheritDoc} */
    public boolean hasGitModules(String treeIsh) throws GitException, InterruptedException {
        return getGitAPI().hasGitModules(treeIsh);
    }

    /** {@inheritDoc} */
    public String getRemoteUrl(String name, String GIT_DIR) throws GitException, InterruptedException {
        return getGitAPI().getRemoteUrl(name, GIT_DIR);
    }

    /** {@inheritDoc} */
    public void setRemoteUrl(String name, String url, String GIT_DIR) throws GitException, InterruptedException {
        getGitAPI().setRemoteUrl(name, url, GIT_DIR);
    }

    /** {@inheritDoc} */
    public String getDefaultRemote(String _default_) throws GitException, InterruptedException {
        return getGitAPI().getDefaultRemote(_default_);
    }

    /**
     * isBareRepository.
     *
     * @return true if this repository is bare
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public boolean isBareRepository() throws GitException, InterruptedException {
        return getGitAPI().isBareRepository();
    }

    /** {@inheritDoc} */
    public boolean isBareRepository(String GIT_DIR) throws GitException, InterruptedException {
        return getGitAPI().isBareRepository(GIT_DIR);
    }

    /**
     * submoduleInit.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public void submoduleInit() throws GitException, InterruptedException {
        getGitAPI().submoduleInit();
    }

    /**
     * submoduleSync.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public void submoduleSync() throws GitException, InterruptedException {
        getGitAPI().submoduleSync();
    }

    /** {@inheritDoc} */
    public String getSubmoduleUrl(String name) throws GitException, InterruptedException {
        return getGitAPI().getSubmoduleUrl(name);
    }

    /** {@inheritDoc} */
    public void setSubmoduleUrl(String name, String url) throws GitException, InterruptedException {
        getGitAPI().setSubmoduleUrl(name, url);
    }

    /** {@inheritDoc} */
    public void fixSubmoduleUrls(String remote, TaskListener listener) throws GitException, InterruptedException {
        getGitAPI().fixSubmoduleUrls(remote, listener);
    }

    /** {@inheritDoc} */
    public void setupSubmoduleUrls(String remote, TaskListener listener) throws GitException, InterruptedException {
        getGitAPI().setupSubmoduleUrls(remote, listener);
    }

    /** {@inheritDoc} */
    public void fetch(String repository, String refspec) throws GitException, InterruptedException {
        getGitAPI().fetch(repository, refspec);
    }

    /** {@inheritDoc} */
    public void fetch(RemoteConfig remoteRepository) throws InterruptedException {
        getGitAPI().fetch(remoteRepository);
    }

    /**
     * fetch.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public void fetch() throws GitException, InterruptedException {
        getGitAPI().fetch();
    }

    /** {@inheritDoc} */
    public void reset(boolean hard) throws GitException, InterruptedException {
        getGitAPI().reset(hard);
    }

    /**
     * reset.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    public void reset() throws GitException, InterruptedException {
        getGitAPI().reset();
    }

    /** {@inheritDoc} */
    public void push(RemoteConfig repository, String revspec) throws GitException, InterruptedException {
        getGitAPI().push(repository, revspec);
    }

    /** {@inheritDoc} */
    public void merge(String revSpec) throws GitException, InterruptedException {
        getGitAPI().merge(revSpec);
    }

    /** {@inheritDoc} */
    public void clone(RemoteConfig source) throws GitException, InterruptedException {
        getGitAPI().clone(source);
    }

    /** {@inheritDoc} */
    public void clone(RemoteConfig rc, boolean useShallowClone) throws GitException, InterruptedException {
        getGitAPI().clone(rc, useShallowClone);
    }

    /** {@inheritDoc} */
    public List<Branch> getBranchesContaining(String revspec) throws GitException, InterruptedException {
        return getGitAPI().getBranchesContaining(revspec);
    }

    /** {@inheritDoc} */
    public List<IndexEntry> lsTree(String treeIsh) throws GitException, InterruptedException {
        return getGitAPI().lsTree(treeIsh);
    }

    /** {@inheritDoc} */
    public List<IndexEntry> lsTree(String treeIsh, boolean recursive) throws GitException, InterruptedException {
        return getGitAPI().lsTree(treeIsh, recursive);
    }

    /** {@inheritDoc} */
    public List<ObjectId> revListBranch(String branchId) throws GitException, InterruptedException {
        return getGitAPI().revListBranch(branchId);
    }

    /** {@inheritDoc} */
    public String describe(String commitIsh) throws GitException, InterruptedException {
        return getGitAPI().describe(commitIsh);
    }

    /** {@inheritDoc} */
    public List<Tag> getTagsOnCommit(String revName) throws GitException, IOException, InterruptedException {
        return getGitAPI().getTagsOnCommit(revName);
    }

    /** {@inheritDoc} */
    public void setProxy(ProxyConfiguration proxyConfiguration) {
        proxy.setProxy(proxyConfiguration);
    }

    private static final long serialVersionUID = 1L;

    /** {@inheritDoc} */
    public List<Branch> getBranchesContaining(String revspec, boolean allBranches)
            throws GitException, InterruptedException {
        return getGitAPI().getBranchesContaining(revspec, allBranches);
    }

    /** {@inheritDoc} */
    @Override
    public Set<GitObject> getTags() throws GitException, InterruptedException {
        return proxy.getTags();
    }
}
