package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.GitException;
import hudson.remoting.Channel;
import jenkins.model.Jenkins.MasterComputer;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.io.Serializable;

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

    public void commit(String message, PersonIdent author, PersonIdent committer) throws GitException {
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
            setCommitter(p.getName(),p.getEmailAddress());
    }


    /**
     * When sent to remote, switch to the proxy.
     */
    private Object writeReplace() {
        return Channel.current().export(GitClient.class,this);
    }
}
