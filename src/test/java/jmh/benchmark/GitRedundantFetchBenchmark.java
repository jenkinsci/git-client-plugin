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
 * See https://github.com/jenkinsci/git-plugin/pull/845.
 * This micro-benchmark tests the execution time added because of a second fetch call in the git scm checkout step.
 */
@JmhBenchmark
public class GitRedundantFetchBenchmark {

    @State(Scope.Thread)
    public static class JenkinsState {

        String gitExe = "git";

        /**
         * We test the performance of git fetch on four repositories, varying them on the basis of their
         * commit history size, number of branches and ultimately their overall size.
         * Java-logging-benchmarks: (0.034 MiB) https://github.com/stephenc/java-logging-benchmarks.git
         * Coreutils: (4.58 MiB) https://github.com/uutils/coreutils.git
         * Cairo: (93.54 MiB) https://github.com/cairoshell/cairoshell.git
         * Samba: (324.26 MiB) https://github.com/samba-team/samba.git
         */
        @Param({"file:///tmp/experiment/test4/java-logging-benchmarks.git",
                "file:///tmp/experiment/test2/coreutils.git",
                "file:///tmp/experiment/test/cairo.git",
                "file:///tmp/experiment/test3/samba.git"})
        String repoUrl;

        final FolderForBenchmark tmp = new FolderForBenchmark();
        File gitDir;
        GitClient gitClient;
        List<RefSpec> narrowRefSpecs = new ArrayList<>();
        List<RefSpec> wideRefSpecs = new ArrayList<>();
        URIish urIish;

        @Setup(Level.Iteration)
        public void doSetup() throws Exception {
            tmp.before();
            gitDir = tmp.newFolder();
            gitClient = Git.with(TaskListener.NULL, new EnvVars()).in(gitDir).using(gitExe).getClient();

            urIish = new URIish(repoUrl);

            // fetching just master branch, narrow refspec
            narrowRefSpecs.add(new RefSpec("+refs/heads/master:refs/remotes/origin/master"));

            // wide refspec
            wideRefSpecs.add(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));

            // initialize the test folder for git fetch
            gitClient.init();
            System.out.println("Do Setup");
        }

        @TearDown(Level.Iteration)
        public void doTearDown() {
            try {
                File gitDir = gitClient.withRepository((repo, channel) -> repo.getDirectory());
                System.out.println(gitDir.isDirectory());
            } catch (Exception e){
                e.getMessage();
            }
            tmp.after();
            System.out.println("Do TearDown");
        }
    }

    /**
     * A baseline micro-benchmark which records the execution time of git fetch when refspec is wide, for this case,
     * it is "refs/heads/*:refs/remotes/origin/*"
     * @param jenkinsState a static state object which provides the setup environment variables
     * @param blackhole a blackhole object is used to consume object to avoid Dead Code Elimination, in this case, it
     *                  consumes a FetchCommand.
     * @throws Exception
     */
    @Benchmark
    public void gitFetchBenchmarkBaselineWithWideRefSpec(JenkinsState jenkinsState, Blackhole blackhole) throws Exception {
        FetchCommand fetch = jenkinsState.gitClient.fetch_().from(jenkinsState.urIish, jenkinsState.wideRefSpecs);
        fetch.execute();
        blackhole.consume(fetch);
    }

    /**
     * A baseline micro-benchmark which records the execution time of git fetch when refspec is narrow, for this case,
     * it is "refs/heads/master:refs/remotes/origin/master"
     * @param jenkinsState a static state object which provides the setup environment variables
     * @param blackhole a blackhole object is used to consume object to avoid Dead Code Elimination, in this case, it
     *                  consumes a FetchCommand.
     * @throws Exception
     */
    @Benchmark
    public void gitFetchBenchmarkBaselineWithNarrowRefSpec(JenkinsState jenkinsState, Blackhole blackhole) throws Exception {
        FetchCommand fetch = jenkinsState.gitClient.fetch_().from(jenkinsState.urIish, jenkinsState.narrowRefSpecs);
        fetch.execute();
        blackhole.consume(fetch);
    }

    /**
     * A micro-benchmark which records the execution time of double git fetch when honor refspec option is true, the
     * refspec is honored in the first fetch and the same is used in the second fetch as well (narrowRefSpec)
     * @param jenkinsState a static state object which provides the setup environment variables
     * @param blackhole a blackhole object is used to consume object to avoid Dead Code Elimination, in this case, it
     *                  consumes a FetchCommand.
     * @throws Exception
     */
    @Benchmark
    public void gitFetchBenchmarkWithHonorRefSpec(JenkinsState jenkinsState, Blackhole blackhole) throws Exception {
        FetchCommand fetch = jenkinsState.gitClient.fetch_().from(jenkinsState.urIish, jenkinsState.narrowRefSpecs);
        fetch.execute();
        FetchCommand incrementalFetch = jenkinsState.gitClient.fetch_().from(jenkinsState.urIish, jenkinsState.narrowRefSpecs);
        incrementalFetch.execute();
        blackhole.consume(fetch);
        blackhole.consume(incrementalFetch);
    }

    /**
     * A micro-benchmark which records the execution time of double git fetch when honor refspec option is false, the
     * refspec is not honored in the first fetch and it defaults to the usage of a wide refspec (fetching all branches),
     * the second fetch call uses the narrowRefSpec (fetching just the master branch).
     * @param jenkinsState a static state object which provides the setup environment variables
     * @param blackhole a blackhole object is used to consume object to avoid Dead Code Elimination, in this case, it
     *                  consumes a FetchCommand.
     * @throws Exception
     */
    @Benchmark
    public void gitFetchBenchmarkWithoutHonorRefSpec(JenkinsState jenkinsState, Blackhole blackhole) throws Exception {
        FetchCommand fetch = jenkinsState.gitClient.fetch_().from(jenkinsState.urIish, jenkinsState.wideRefSpecs);
        fetch.execute();
        FetchCommand incrementalFetch = jenkinsState.gitClient.fetch_().from(jenkinsState.urIish, jenkinsState.narrowRefSpecs);
        incrementalFetch.execute();
        blackhole.consume(fetch);
        blackhole.consume(incrementalFetch);
    }
}