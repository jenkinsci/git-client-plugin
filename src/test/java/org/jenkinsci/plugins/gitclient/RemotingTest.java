package org.jenkinsci.plugins.gitclient;

import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.StreamBuildListener;
import hudson.remoting.Callable;
import hudson.remoting.VirtualChannel;
import hudson.slaves.DumbSlave;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.jvnet.hudson.test.HudsonTestCase;

import java.io.File;
import java.io.IOException;
import org.jenkinsci.remoting.RoleChecker;

/**
 * @author Kohsuke Kawaguchi
 */
public class RemotingTest extends HudsonTestCase {
    /**
     * Makes sure {@link GitClient} is remotable.
     */
    public void testRemotability() throws Exception {
        DumbSlave s = createSlave();

        File dir = createTmpDir();
        final GitClient jgit = new JGitAPIImpl(dir,StreamBuildListener.fromStdout());

        Computer c = s.toComputer();
        c.connect(false).get();
        VirtualChannel channel = c.getChannel();
        channel.call(new Work(jgit));
        channel.close();
    }

    private static class Work implements Callable<Void,IOException> {
        private final GitClient git;

        private static boolean cliGitDefaultsSet = false;

        private void setCliGitDefaults() throws Exception {
            if (!cliGitDefaultsSet) {
                CliGitCommand gitCmd = new CliGitCommand(null);
                gitCmd.setDefaults();
            }
            cliGitDefaultsSet = true;
        }

        public Work(GitClient git) throws Exception {
            setCliGitDefaults();
            this.git = git;
        }

        public Void call() throws IOException {
            try {
                git.init();
                git.getWorkTree().child("foo").touch(0);
                git.add("foo");
                PersonIdent alice = new PersonIdent("alice", "alice@jenkins-ci.org");
                git.commit("committing changes", alice, alice);

                FilePath ws = git.withRepository(new RepositoryCallableImpl());
                assertEquals(ws,git.getWorkTree());

                return null;
            } catch (InterruptedException e) {
                throw new Error(e);
            }
        }

        private static final long serialVersionUID = 1L;

        @Override
        public void checkRoles(RoleChecker rc) throws SecurityException {
            throw new UnsupportedOperationException("unexpected call to checkRoles in private static Work class");
        }
    }

    private static class RepositoryCallableImpl implements RepositoryCallback<FilePath> {
        public FilePath invoke(Repository repo, VirtualChannel channel) throws IOException, InterruptedException {
            assertNotNull(repo);
            return new FilePath(repo.getWorkTree());
        }

        private static final long serialVersionUID = 1L;
    }
}
