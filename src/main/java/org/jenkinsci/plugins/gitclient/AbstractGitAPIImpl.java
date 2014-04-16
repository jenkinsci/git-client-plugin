package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import hudson.ProxyConfiguration;
import hudson.plugins.git.GitException;
import hudson.remoting.Channel;
import jenkins.model.Jenkins.MasterComputer;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.Writer;

/**
 * Common parts between {@link JGitAPIImpl} and {@link CliGitAPIImpl}.
 *
 * @author Kohsuke Kawaguchi
 */
abstract class AbstractGitAPIImpl implements GitClient, Serializable {
    public <T> T withRepository(RepositoryCallback<T> callable) throws IOException, InterruptedException {
        Repository repo = getRepository();
        try {
            return callable.invoke(repo, MasterComputer.localChannel);
        } finally {
            repo.close();
        }
    }

    public void commit(String message, PersonIdent author, PersonIdent committer) throws GitException, InterruptedException {
        setAuthor(author);
        setCommitter(committer);
        commit(message);
    }

    public void setAuthor(PersonIdent p) {
        if (p!=null)
            setAuthor(p.getName(),p.getEmailAddress());
    }

    public void setCommitter(PersonIdent p) {
        if (p!=null)
            setCommitter(p.getName(), p.getEmailAddress());
    }

    public void changelog(String revFrom, String revTo, OutputStream outputStream) throws GitException, InterruptedException {
        changelog(revFrom, revTo, new OutputStreamWriter(outputStream));
    }

    public void changelog(String revFrom, String revTo, Writer w) throws GitException, InterruptedException {
        changelog().excludes(revFrom).includes(revTo).to(w).execute();
    }

    public void clone(String url, String origin, boolean useShallowClone, String reference) throws GitException, InterruptedException {
        CloneCommand c = clone_().url(url).repositoryName(origin).reference(reference);
        if (useShallowClone)    c.shallow();
        c.execute();
    }

    public void checkout(String commit) throws GitException, InterruptedException {
        checkout().ref(commit).execute();
    }

    public void checkout(String ref, String branch) throws GitException, InterruptedException {
        checkout().ref(ref).branch(branch).execute();
    }

    public void checkoutBranch(String branch, String ref) throws GitException, InterruptedException {
        checkout().ref(ref).branch(branch).deleteBranchIfExist(true).execute();
    }

    public void merge(ObjectId rev) throws GitException, InterruptedException {
        merge().setRevisionToMerge(rev).execute();
    }

    /**
     * When sent to remote, switch to the proxy.
     */
    protected Object writeReplace() {
        return remoteProxyFor(Channel.current().export(GitClient.class, this));
    }

    protected RemoteGitImpl remoteProxyFor(GitClient proxy) {
        return new RemoteGitImpl(proxy);
    }

    public void setCredentials(StandardUsernameCredentials cred) {
        clearCredentials();
        addDefaultCredentials(cred);
    }

    protected ProxyConfiguration proxy;

    public void setProxy(ProxyConfiguration proxy) {
        this.proxy = proxy;
    }


    public void submoduleUpdate(boolean recursive) throws GitException, InterruptedException {
        submoduleUpdate().recursive(recursive).execute();
    }
    public void submoduleUpdate(boolean recursive, String reference) throws GitException, InterruptedException {
        submoduleUpdate().recursive(recursive).ref(reference).execute();
    }
    public void submoduleUpdate(boolean recursive, boolean remoteTracking) throws GitException, InterruptedException {
        submoduleUpdate().recursive(recursive).remoteTracking(remoteTracking).execute();
    }
    public void submoduleUpdate(boolean recursive, boolean remoteTracking, String reference) throws GitException, InterruptedException {
        submoduleUpdate().recursive(recursive).remoteTracking(remoteTracking).ref(reference).execute();
    }
}
