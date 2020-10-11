package jmh.benchmark;

import hudson.EnvVars;
import hudson.model.TaskListener;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.FetchCommand;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GitClientRedundantFetchBenchmark {

    @State(Scope.Thread)
    public static class ClientState {

        @Param({"git", "jgit"})
        String gitExe;

        final FolderForBenchmark tmp = new FolderForBenchmark();
        File gitDir;
        GitClient gitClient;
        List<RefSpec> narrowRefSpecs = new ArrayList<>();
        List<RefSpec> wideRefSpecs = new ArrayList<>();

        final FolderForBenchmark dirForRemoteClone = new FolderForBenchmark();
        File localRemoteDir;
        File remoteRepoDir;
        URIish urIish;

        @Param({"https://github.com/jenkinsci/jenkins-charm.git",
                "https://github.com/jenkinsci/parameterized-trigger-plugin.git",
                "https://github.com/jenkinsci/ec2-plugin.git",
                "https://github.com/jenkinsci/git-plugin.git",
                "https://github.com/jenkinsci/jenkins.git"})
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
        public void cloneRemoteRepo() throws Exception {
            dirForRemoteClone.before();
            localRemoteDir = dirForRemoteClone.newFolder();
            remoteRepoDir = cloneUpstreamRepositoryLocally(localRemoteDir, repoUrl);
            System.out.println("Size of cloned upstream repo: " + FileUtils.sizeOfDirectory(remoteRepoDir));
            // Coreutils is a repo sized 4.58 MiB, Cairo is 93.64 MiB and samba is 324.26 MiB
            urIish = new URIish("file://" + remoteRepoDir.getAbsolutePath());
            System.out.println("Created local upstream directory for: " + repoUrl);
        }

        @TearDown(Level.Trial)
        public void tearRemoteRepoDown() {
            tmp.after();
            System.out.println("Removed local upstream directory for: " + repoUrl);
        }

        /**
         * We want to create a temporary local git repository after each iteration of the benchmark, works just like
         * "before" and "after" JUnit annotations.
         */
        @Setup(Level.Iteration)
        public void setup() throws Exception {
            tmp.before();
            gitDir = tmp.newFolder();
            gitClient = Git.with(TaskListener.NULL, new EnvVars()).in(gitDir).using(gitExe).getClient();

            // fetching just master branch
            narrowRefSpecs.add(new RefSpec("+refs/heads/master:refs/remotes/origin/master"));

            // wide refspec
            wideRefSpecs.add(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));

            // initialize the test folder for git fetch
            gitClient.clone_().url(urIish.toString()).execute();
            System.out.println("Fetching for the first time");
            System.out.println("git client dir is: " + FileUtils.sizeOfDirectory(gitDir));
//            gitClient.setRemoteUrl("origin", "file:///tmp/experiment/TestProject.git");
            System.out.println("Do Setup");
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

    @Benchmark
    public void gitFetchBenchmarkRedundantWithWideRefSpec(ClientState jenkinsState, Blackhole blackhole) throws Exception {
        FetchCommand incrementalFetch = jenkinsState.gitClient.fetch_().from(jenkinsState.urIish, jenkinsState.wideRefSpecs);
        incrementalFetch.execute();
        System.out.println("Incremental fetch done");
        blackhole.consume(incrementalFetch);
    }

    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
                .include(GitClientRedundantFetchBenchmark.class.getSimpleName())
                .mode(Mode.AverageTime)
                .warmupIterations(5)
                .measurementIterations(5)
                .timeUnit(TimeUnit.MILLISECONDS)
                .forks(2)
                .shouldFailOnError(true)
                .shouldDoGC(true)
                .build();

        new Runner(options).run();
    }

}
