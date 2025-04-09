package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.ProxyConfiguration;
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
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.io.Writer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

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
        return (hudson.plugins.git.IGitAPI) proxy;
    }

    protected Object readResolve() {
        channel = Channel.current();
        return this;
    }

    private Object writeReplace() {
        if (channel != null) {
            return proxy; // when sent back to where it came from, switch back to the original object
        }
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
            for (int i = 0; i < args.length; i++) {
                parameterTypes[i] = paramTypes[i].getName();
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof OutputStream stream) {
                    args[i] = new RemoteOutputStream(stream);
                }
                if (args[i] instanceof Writer writer) {
                    args[i] = new RemoteWriter(writer);
                }
            }
        }

        public void replay(Object target) throws InvocationTargetException, IllegalAccessException {
            OUTER:
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterTypes().length == parameterTypes.length) {
                    Class<?>[] t = m.getParameterTypes();
                    for (int i = 0; i < parameterTypes.length; i++) {
                        if (!t[i].getName().equals(parameterTypes[i])) {
                            continue OUTER;
                        }
                    }
                    // matched
                    m.invoke(target, args);
                    return;
                }
            }
            throw new IllegalStateException(
                    "Method not found: " + methodName + "(" + String.join(",", parameterTypes) + ")");
        }

        @Serial
        private static final long serialVersionUID = 1L;
    }

    private <T extends GitCommand> T command(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class[] {type}, new CommandInvocationHandler(type, this)));
    }

    private static class CommandInvocationHandler implements InvocationHandler, GitCommand, Serializable {
        private final Class<? extends GitCommand> command;
        private final List<Invocation> invocations = new ArrayList<>();
        private final transient Channel channel;
        private final GitClient proxy;

        private CommandInvocationHandler(Class<? extends GitCommand> command, RemoteGitImpl owner) {
            this.command = command;
            this.channel = owner.channel;
            this.proxy = owner.proxy;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Class<?> decl = method.getDeclaringClass();
            if (args == null) {
                args = new Object[0];
            }
            if (GitCommand.class == decl || Object.class == decl) {
                try {
                    return method.invoke(this, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            if (GitCommand.class.isAssignableFrom(decl)) {
                invocations.add(new Invocation(method, args));
                return proxy;
            }
            throw new IllegalStateException("Unexpected invocation: " + method);
        }

        @Override
        public void execute() throws GitException, InterruptedException {
            try {
                channel.call(new GitCommandMasterToSlaveCallable());
            } catch (IOException e) {
                throw new GitException(e);
            }
        }

        @Serial
        private static final long serialVersionUID = 1L;

        private class GitCommandMasterToSlaveCallable
                extends jenkins.security.MasterToSlaveCallable<Void, GitException> {
            @Override
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
                    if (m.getReturnType() == command && m.getParameterTypes().length == 0) {
                        return command.cast(m.invoke(proxy));
                    }
                }
                throw new IllegalStateException("Can't find the factory method for " + command);
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
    @Override
    @NonNull
    public Repository getRepository() throws GitException {
        throw new UnsupportedOperationException();
    }

    /**
     * clearCredentials.
     */
    @Override
    public void clearCredentials() {
        proxy.clearCredentials();
    }

    /** {@inheritDoc} */
    @Override
    public void addCredentials(String url, StandardCredentials credentials) {
        proxy.addCredentials(
                url,
                CredentialsProvider.snapshot(StandardCredentials.class, credentials)); // credentials are Serializable
    }

    /** {@inheritDoc} */
    @Override
    public void setCredentials(StandardUsernameCredentials cred) {
        proxy.setCredentials(
                CredentialsProvider.snapshot(StandardUsernameCredentials.class, cred)); // credentials are Serializable
    }

    /** {@inheritDoc} */
    @Override
    public void addDefaultCredentials(StandardCredentials credentials) {
        proxy.addDefaultCredentials(
                CredentialsProvider.snapshot(StandardCredentials.class, credentials)); // credentials are Serializable
    }

    /** {@inheritDoc} */
    @Override
    public void setAuthor(String name, String email) throws GitException {
        proxy.setAuthor(name, email);
    }

    /** {@inheritDoc} */
    @Override
    public void setAuthor(PersonIdent p) throws GitException {
        proxy.setAuthor(p);
    }

    /** {@inheritDoc} */
    @Override
    public void setCommitter(String name, String email) throws GitException {
        proxy.setCommitter(name, email);
    }

    /** {@inheritDoc} */
    @Override
    public void setCommitter(PersonIdent p) throws GitException {
        proxy.setCommitter(p);
    }

    /** {@inheritDoc} */
    @Override
    public <T> T withRepository(RepositoryCallback<T> callable) throws GitException, IOException, InterruptedException {
        return proxy.withRepository(callable);
    }

    /**
     * getWorkTree.
     *
     * @return a {@link hudson.FilePath} object.
     */
    @Override
    public FilePath getWorkTree() {
        return proxy.getWorkTree();
    }

    /**
     * init.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public void init() throws GitException, InterruptedException {
        proxy.init();
    }

    /** {@inheritDoc} */
    @Override
    public void add(String filePattern) throws GitException, InterruptedException {
        proxy.add(filePattern);
    }

    /** {@inheritDoc} */
    @Override
    public void commit(String message) throws GitException, InterruptedException {
        proxy.commit(message);
    }

    /** {@inheritDoc} */
    @Override
    public void commit(String message, PersonIdent author, PersonIdent committer)
            throws GitException, InterruptedException {
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
    @Override
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
    @Override
    public boolean isCommitInRepo(ObjectId commit) throws GitException, InterruptedException {
        return proxy.isCommitInRepo(commit);
    }

    /** {@inheritDoc} */
    @Override
    public String getRemoteUrl(String name) throws GitException, InterruptedException {
        return proxy.getRemoteUrl(name);
    }

    /** {@inheritDoc} */
    @Override
    public void setRemoteUrl(String name, String url) throws GitException, InterruptedException {
        proxy.setRemoteUrl(name, url);
    }

    /** {@inheritDoc} */
    @Override
    public void addRemoteUrl(String name, String url) throws GitException, InterruptedException {
        proxy.addRemoteUrl(name, url);
    }

    /** {@inheritDoc} */
    @Override
    public void checkout(String ref) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.checkout(ref);
    }

    /** {@inheritDoc} */
    @Override
    public void checkout(String ref, String branch) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.checkout(ref, branch);
    }

    /**
     * checkout.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CheckoutCommand} object.
     */
    @Override
    public CheckoutCommand checkout() {
        return command(CheckoutCommand.class);
    }

    /** {@inheritDoc} */
    @Override
    public void checkoutBranch(String branch, String ref) throws GitException, InterruptedException {
        proxy.checkoutBranch(branch, ref);
    }

    /** {@inheritDoc} */
    @Override
    public ObjectId mergeBase(ObjectId sha1, ObjectId sha12) throws GitException, InterruptedException {
        return getGitAPI().mergeBase(sha1, sha12);
    }

    /** {@inheritDoc} */
    @Override
    public String getAllLogEntries(String branch) throws GitException, InterruptedException {
        return getGitAPI().getAllLogEntries(branch);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> showRevision(Revision r) throws GitException, InterruptedException {
        return getGitAPI().showRevision(r);
    }

    /** {@inheritDoc} */
    @Override
    public void clone(String url, String origin, boolean useShallowClone, String reference)
            throws GitException, InterruptedException {
        proxy.clone(url, origin, useShallowClone, reference);
    }

    /**
     * clone_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.CloneCommand} object.
     */
    @Override
    public CloneCommand clone_() {
        return command(CloneCommand.class);
    }

    /**
     * merge.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.MergeCommand} object.
     */
    @Override
    public MergeCommand merge() {
        return command(MergeCommand.class);
    }

    /**
     * rebase.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RebaseCommand} object.
     */
    @Override
    public RebaseCommand rebase() {
        return command(RebaseCommand.class);
    }

    /**
     * init_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.InitCommand} object.
     */
    @Override
    public InitCommand init_() {
        return command(InitCommand.class);
    }

    /**
     * fetch_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.FetchCommand} object.
     */
    @Override
    public FetchCommand fetch_() {
        return command(FetchCommand.class);
    }

    /**
     * push.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     */
    @Override
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
    @Override
    public void fetch(URIish url, List<RefSpec> refspecs) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.fetch(url, refspecs);
    }

    /** {@inheritDoc} */
    @Override
    public void fetch(String remoteName, RefSpec... refspec) throws GitException, InterruptedException {
        proxy.fetch(remoteName, refspec);
    }

    /** {@inheritDoc} */
    @Override
    public void fetch(String remoteName, RefSpec refspec) throws GitException, InterruptedException {
        fetch(remoteName, new RefSpec[] {refspec});
    }

    /** {@inheritDoc} */
    @Override
    public void push(String remoteName, String refspec) throws GitException, InterruptedException {
        proxy.push(remoteName, refspec);
    }

    /** {@inheritDoc} */
    @Override
    public void push(URIish url, String refspec) throws GitException, InterruptedException {
        proxy.push(url, refspec);
    }

    /** {@inheritDoc} */
    @Override
    public void merge(ObjectId rev) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.merge(rev);
    }

    /** {@inheritDoc} */
    @Override
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
    @Override
    public void clean(boolean cleanSubmodule) throws GitException, InterruptedException {
        proxy.clean(cleanSubmodule);
    }

    /**
     * clean.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public void clean() throws GitException, InterruptedException {
        proxy.clean();
    }

    /** {@inheritDoc} */
    @Override
    public void branch(String name) throws GitException, InterruptedException {
        proxy.branch(name);
    }

    /** {@inheritDoc} */
    @Override
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
    @Override
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
    @Override
    public Set<Branch> getRemoteBranches() throws GitException, InterruptedException {
        return proxy.getRemoteBranches();
    }

    /** {@inheritDoc} */
    @Override
    public void tag(String tagName, String comment) throws GitException, InterruptedException {
        proxy.tag(tagName, comment);
    }

    /** {@inheritDoc} */
    @Override
    public boolean tagExists(String tagName) throws GitException, InterruptedException {
        return proxy.tagExists(tagName);
    }

    /** {@inheritDoc} */
    @Override
    public String getTagMessage(String tagName) throws GitException, InterruptedException {
        return proxy.getTagMessage(tagName);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteTag(String tagName) throws GitException, InterruptedException {
        proxy.deleteTag(tagName);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getTagNames(String tagPattern) throws GitException, InterruptedException {
        return proxy.getTagNames(tagPattern);
    }

    /** {@inheritDoc} */
    @Override
    public void ref(String refName) throws GitException, InterruptedException {
        proxy.ref(refName);
    }

    /** {@inheritDoc} */
    @Override
    public boolean refExists(String refName) throws GitException, InterruptedException {
        return proxy.refExists(refName);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteRef(String refName) throws GitException, InterruptedException {
        proxy.deleteRef(refName);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getRefNames(String refPrefix) throws GitException, InterruptedException {
        return proxy.getRefNames(refPrefix);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getRemoteTagNames(String tagPattern) throws GitException, InterruptedException {
        return proxy.getTagNames(tagPattern);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, ObjectId> getHeadRev(String url) throws GitException, InterruptedException {
        return proxy.getHeadRev(url);
    }

    /** {@inheritDoc} */
    @Override
    public ObjectId getHeadRev(String remoteRepoUrl, String branch) throws GitException, InterruptedException {
        return proxy.getHeadRev(remoteRepoUrl, branch);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, ObjectId> getRemoteReferences(
            String remoteRepoUrl, String pattern, boolean headsOnly, boolean tagsOnly)
            throws GitException, InterruptedException {
        return proxy.getRemoteReferences(remoteRepoUrl, pattern, headsOnly, tagsOnly);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, String> getRemoteSymbolicReferences(String remoteRepoUrl, String pattern)
            throws GitException, InterruptedException {
        return proxy.getRemoteSymbolicReferences(remoteRepoUrl, pattern);
    }

    /** {@inheritDoc} */
    @Override
    public ObjectId revParse(String revName) throws GitException, InterruptedException {
        return proxy.revParse(revName);
    }

    /**
     * revList_.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     */
    @Override
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
    @Override
    public List<ObjectId> revListAll() throws GitException, InterruptedException {
        return proxy.revListAll();
    }

    /** {@inheritDoc} */
    @Override
    public List<ObjectId> revList(String ref) throws GitException, InterruptedException {
        return proxy.revList(ref);
    }

    /** {@inheritDoc} */
    @Override
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
    @Override
    public boolean hasGitModules() throws GitException, InterruptedException {
        return proxy.hasGitModules();
    }

    /** {@inheritDoc} */
    @Override
    public List<IndexEntry> getSubmodules(String treeIsh) throws GitException, InterruptedException {
        return proxy.getSubmodules(treeIsh);
    }

    /** {@inheritDoc} */
    @Override
    public void addSubmodule(String remoteURL, String subdir) throws GitException, InterruptedException {
        proxy.addSubmodule(remoteURL, subdir);
    }

    /** {@inheritDoc} */
    @Override
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
    @Override
    public void submoduleUpdate(boolean recursive, String ref) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.submoduleUpdate(recursive, ref);
    }

    /** {@inheritDoc} */
    @Override
    public void submoduleUpdate(boolean recursive, boolean remoteTracking) throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.submoduleUpdate(recursive, remoteTracking);
    }

    /** {@inheritDoc} */
    @Override
    public void submoduleUpdate(boolean recursive, boolean remoteTracking, String reference)
            throws GitException, InterruptedException {
        /* Intentionally using the deprecated method because the replacement method is not serializable. */
        proxy.submoduleUpdate(recursive, remoteTracking, reference);
    }

    /**
     * submoduleUpdate.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.SubmoduleUpdateCommand} object.
     */
    @Override
    public SubmoduleUpdateCommand submoduleUpdate() {
        return command(SubmoduleUpdateCommand.class);
    }

    /** {@inheritDoc} */
    @Override
    public void submoduleClean(boolean recursive) throws GitException, InterruptedException {
        proxy.submoduleClean(recursive);
    }

    /** {@inheritDoc} */
    @Override
    public void setupSubmoduleUrls(Revision rev, TaskListener listener) throws GitException, InterruptedException {
        proxy.setupSubmoduleUrls(rev, listener);
    }

    /** {@inheritDoc} */
    @Override
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
    @Override
    public void changelog(String revFrom, String revTo, Writer os) throws GitException, InterruptedException {
        proxy.changelog(revFrom, revTo, os); // TODO: wrap
    }

    /**
     * changelog.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    @Override
    public ChangelogCommand changelog() {
        return command(ChangelogCommand.class);
    }

    /** {@inheritDoc} */
    @Override
    public void appendNote(String note, String namespace) throws GitException, InterruptedException {
        proxy.appendNote(note, namespace);
    }

    /** {@inheritDoc} */
    @Override
    public void addNote(String note, String namespace) throws GitException, InterruptedException {
        proxy.addNote(note, namespace);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> showRevision(ObjectId r) throws GitException, InterruptedException {
        return proxy.showRevision(r);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException, InterruptedException {
        return proxy.showRevision(from, to);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> showRevision(ObjectId from, ObjectId to, Boolean useRawOutput)
            throws GitException, InterruptedException {
        return proxy.showRevision(from, to, useRawOutput);
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasGitModules(String treeIsh) throws GitException, InterruptedException {
        return getGitAPI().hasGitModules(treeIsh);
    }

    /** {@inheritDoc} */
    @Override
    public String getRemoteUrl(String name, String GIT_DIR) throws GitException, InterruptedException {
        return getGitAPI().getRemoteUrl(name, GIT_DIR);
    }

    /** {@inheritDoc} */
    @Override
    public void setRemoteUrl(String name, String url, String GIT_DIR) throws GitException, InterruptedException {
        getGitAPI().setRemoteUrl(name, url, GIT_DIR);
    }

    /** {@inheritDoc} */
    @Override
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
    @Override
    public boolean isBareRepository() throws GitException, InterruptedException {
        return getGitAPI().isBareRepository();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isBareRepository(String GIT_DIR) throws GitException, InterruptedException {
        return getGitAPI().isBareRepository(GIT_DIR);
    }

    /**
     * submoduleInit.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public void submoduleInit() throws GitException, InterruptedException {
        getGitAPI().submoduleInit();
    }

    /**
     * submoduleSync.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public void submoduleSync() throws GitException, InterruptedException {
        getGitAPI().submoduleSync();
    }

    /** {@inheritDoc} */
    @Override
    public String getSubmoduleUrl(String name) throws GitException, InterruptedException {
        return getGitAPI().getSubmoduleUrl(name);
    }

    /** {@inheritDoc} */
    @Override
    public void setSubmoduleUrl(String name, String url) throws GitException, InterruptedException {
        getGitAPI().setSubmoduleUrl(name, url);
    }

    /** {@inheritDoc} */
    @Override
    public void fixSubmoduleUrls(String remote, TaskListener listener) throws GitException, InterruptedException {
        getGitAPI().fixSubmoduleUrls(remote, listener);
    }

    /** {@inheritDoc} */
    @Override
    public void setupSubmoduleUrls(String remote, TaskListener listener) throws GitException, InterruptedException {
        getGitAPI().setupSubmoduleUrls(remote, listener);
    }

    /** {@inheritDoc} */
    @Override
    public void fetch(String repository, String refspec) throws GitException, InterruptedException {
        getGitAPI().fetch(repository, refspec);
    }

    /** {@inheritDoc} */
    @Override
    public void fetch(RemoteConfig remoteRepository) throws GitException, InterruptedException {
        getGitAPI().fetch(remoteRepository);
    }

    /**
     * fetch.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public void fetch() throws GitException, InterruptedException {
        getGitAPI().fetch();
    }

    /** {@inheritDoc} */
    @Override
    public void reset(boolean hard) throws GitException, InterruptedException {
        getGitAPI().reset(hard);
    }

    /**
     * reset.
     *
     * @throws hudson.plugins.git.GitException if underlying git operation fails.
     * @throws java.lang.InterruptedException if interrupted.
     */
    @Override
    public void reset() throws GitException, InterruptedException {
        getGitAPI().reset();
    }

    /** {@inheritDoc} */
    @Override
    public void push(RemoteConfig repository, String revspec) throws GitException, InterruptedException {
        getGitAPI().push(repository, revspec);
    }

    /** {@inheritDoc} */
    @Override
    public void merge(String revSpec) throws GitException, InterruptedException {
        getGitAPI().merge(revSpec);
    }

    /** {@inheritDoc} */
    @Override
    public void clone(RemoteConfig source) throws GitException, InterruptedException {
        getGitAPI().clone(source);
    }

    /** {@inheritDoc} */
    @Override
    public void clone(RemoteConfig rc, boolean useShallowClone) throws GitException, InterruptedException {
        getGitAPI().clone(rc, useShallowClone);
    }

    /** {@inheritDoc} */
    @Override
    public List<Branch> getBranchesContaining(String revspec) throws GitException, InterruptedException {
        return getGitAPI().getBranchesContaining(revspec);
    }

    /** {@inheritDoc} */
    @Override
    public List<IndexEntry> lsTree(String treeIsh) throws GitException, InterruptedException {
        return getGitAPI().lsTree(treeIsh);
    }

    /** {@inheritDoc} */
    @Override
    public List<IndexEntry> lsTree(String treeIsh, boolean recursive) throws GitException, InterruptedException {
        return getGitAPI().lsTree(treeIsh, recursive);
    }

    /** {@inheritDoc} */
    @Override
    public List<ObjectId> revListBranch(String branchId) throws GitException, InterruptedException {
        return getGitAPI().revListBranch(branchId);
    }

    /** {@inheritDoc} */
    @Override
    public String describe(String commitIsh) throws GitException, InterruptedException {
        return getGitAPI().describe(commitIsh);
    }

    /** {@inheritDoc} */
    @Override
    public List<Tag> getTagsOnCommit(String revName) throws GitException, IOException, InterruptedException {
        return getGitAPI().getTagsOnCommit(revName);
    }

    /** {@inheritDoc} */
    @Override
    public void setProxy(ProxyConfiguration proxyConfiguration) {
        proxy.setProxy(proxyConfiguration);
    }

    @Serial
    private static final long serialVersionUID = 1L;

    /** {@inheritDoc} */
    @Override
    public List<Branch> getBranchesContaining(String revspec, boolean allBranches)
            throws GitException, InterruptedException {
        return getGitAPI().getBranchesContaining(revspec, allBranches);
    }

    /** {@inheritDoc} */
    @Override
    public Set<GitObject> getTags() throws GitException, InterruptedException {
        return proxy.getTags();
    }

    @Override
    public boolean maintenance(String task) {
        return false;
    }

    @Override
    public void config(ConfigLevel configLevel, String key, String value) throws GitException, InterruptedException {
        proxy.config(configLevel, key, value);
    }
}
