package org.jenkinsci.plugins.gitclient;

import static org.junit.Assert.*;

import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.StreamBuildListener;
import hudson.remoting.Callable;
import hudson.remoting.VirtualChannel;
import hudson.slaves.DumbSlave;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;
import java.io.IOException;
import org.jenkinsci.remoting.RoleChecker;

/**
 * @author Kohsuke Kawaguchi
 */
public class RemotingTest {

    @ClassRule
    public static JenkinsRule j = new JenkinsRule();

    @ClassRule
    public static TemporaryFolder tempFolder = new TemporaryFolder();

    /**
     * Makes sure {@link GitClient} is remotable.
     */
    @Test
    public void testRemotability() throws Exception {
        DumbSlave agent = j.createSlave();

        GitClient jgit = new JGitAPIImpl(tempFolder.getRoot(), StreamBuildListener.fromStdout());

        Computer c = agent.toComputer();
        c.connect(false).get();
        VirtualChannel channel = c.getChannel();
        channel.call(new Work(jgit));
        channel.close();
    }

    private static class Work implements Callable<Void, IOException> {

        private static final long serialVersionUID = 1L;

        private final GitClient git;

        private Work(GitClient git) throws Exception {
            this.git = git;
        }

        @Override
        public Void call() throws IOException {
            try {
                git.init();
                git.getWorkTree().child("foo").touch(0);
                git.add("foo");
                PersonIdent alice = new PersonIdent("alice", "alice@jenkins-ci.org");
                git.setAuthor(alice);
                git.setCommitter(alice);
                git.commit("committing changes");

                FilePath ws = git.withRepository(new RepositoryCallableImpl());
                assertEquals(ws, git.getWorkTree());

                return null;
            } catch (InterruptedException e) {
                throw new Error(e);
            }
        }

        @Override
        public void checkRoles(RoleChecker rc) throws SecurityException {
            throw new UnsupportedOperationException("unexpected call to checkRoles in private static Work class");
        }
    }

    private static class RepositoryCallableImpl implements RepositoryCallback<FilePath> {

        private static final long serialVersionUID = 1L;

        @Override
        public FilePath invoke(Repository repo, VirtualChannel channel) {
            assertNotNull(repo);
            return new FilePath(repo.getWorkTree());
        }
    }
}
