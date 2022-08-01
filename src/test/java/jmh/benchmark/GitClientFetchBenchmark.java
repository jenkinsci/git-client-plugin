package jmh.benchmark;

import hudson.EnvVars;
import hudson.model.TaskListener;
import jenkins.benchmark.jmh.JmhBenchmark;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.FetchCommand;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


/**
 * A JMH micro-benchmark performance test, it aims to compare the performance of git-fetch using both "git" and "jgit"
 * implementations represented by CliGitAPIImpl and JGitAPIImpl respectively.
 */
@JmhBenchmark
public class GitClientFetchBenchmark {

    @State(Scope.Thread)
    public static class ClientState {

        @Param({"git", "jgit"})
        String gitExe;

        final FolderForBenchmark tmp = new FolderForBenchmark();
        File gitDir;
        GitClient gitClient;
        List<RefSpec> refSpecs = new ArrayList<>();

        /**
         * We want to create a temporary local git repository after each iteration of the benchmark, works just like
         * "before" and "after" JUnit annotations.
         */
        @Setup(Level.Iteration)
        public void setup() throws Exception {
            tmp.before();
            gitDir = tmp.newFolder();

            gitClient = Git.with(TaskListener.NULL, new EnvVars()).in(gitDir).using(gitExe).getClient();

            // fetching all branches
            refSpecs.add(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));

            // initialize the test folder for git fetch
            gitClient.init();

            System.out.println("Do Setup for: " + gitExe);
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            try {
                // making sure that git init made a git an empty repository
                File gitDir = gitClient.withRepository((repo, channel) -> repo.getDirectory());
                System.out.println(gitDir.isDirectory());
            } catch (Exception e) {
                e.getMessage();
            }
            tmp.after();
            System.out.println("Do TearDown for: " + gitExe);
        }
    }

    @State(Scope.Thread)
    public static class CloneRepoState {

        final FolderForBenchmark tmp = new FolderForBenchmark();

        File localRemoteDir;
        File remoteRepoDir;
        URIish urIish;
        /**
         * We test the performance of git fetch on four repositories, varying them on the basis of their
         * commit history size, number of branches and ultimately their overall size.
         * Java-logging-benchmarks: (0.034 MiB) https://github.com/stephenc/java-logging-benchmarks.git
         * Coreutils: (4.58 MiB) https://github.com/uutils/coreutils.git
         * Cairo: (93.54 MiB) https://github.com/cairoshell/cairoshell.git
         * Samba: (324.26 MiB) https://github.com/samba-team/samba.git
         */
        @Param({"https://github.com/stephenc/java-logging-benchmarks.git",
                "https://github.com/uutils/coreutils.git",
                "https://github.com/freedesktop/cairo.git",
                "https://github.com/samba-team/samba.git"})
        String repoUrl;

        private File cloneUpstreamRepositoryLocally(File parentDir, String repoUrl) throws Exception {
            String repoName = repoUrl.split("/")[repoUrl.split("/").length - 1];
            File gitRepoDir = new File(parentDir, repoName);
            gitRepoDir.mkdir();
            GitClient cloningGitClient = Git.with(TaskListener.NULL, new EnvVars()).in(gitRepoDir).using("git").getClient();
            cloningGitClient.clone_().url(repoUrl).execute();
//            assertTrue("Unable to create git repo", gitRepoDir.exists());
            return gitRepoDir;
        }

        @Setup(Level.Trial)
        public void cloneUpstreamRepo() throws Exception {
            tmp.before();
            localRemoteDir = tmp.newFolder();
            remoteRepoDir = cloneUpstreamRepositoryLocally(localRemoteDir, repoUrl);
            // Coreutils is a repo sized 4.58 MiB, Cairo is 93.64 MiB and samba is 324.26 MiB
            urIish = new URIish("file://" + remoteRepoDir.getAbsolutePath());
            System.out.println("Created local upstream directory for: " + repoUrl);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            tmp.after();
            System.out.println("Removed local upstream directory for: " + repoUrl);
        }
    }

    @Benchmark
    public void gitFetchBenchmark(ClientState gitClientState, CloneRepoState cloneRepoState, Blackhole blackhole) throws Exception {
        FetchCommand fetch = gitClientState.gitClient.fetch_().from(cloneRepoState.urIish, gitClientState.refSpecs);
        fetch.execute();
        blackhole.consume(fetch);
    }
}
