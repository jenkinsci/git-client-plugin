package jmh.benchmark;

import hudson.EnvVars;
import hudson.model.TaskListener;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.FetchCommand;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * See https://github.com/jenkinsci/git-plugin/pull/845.
 * This vanilla benchmark tests the execution time added because of a second fetch call in the git scm checkout step
 * without JMH. We use its result to sanity check the relevant micro-benchmark results.
 */
@RunWith(Parameterized.class)
public class GitRedundantFetchVanillaBenchmark {

    String gitExe;

    public GitRedundantFetchVanillaBenchmark(final String gitImplName) {
        this.gitExe = gitImplName;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection gitObjects() {
        List<Object[]> arguments = new ArrayList<>();
        String[] gitImplNames = {"git", "jgit"};
        for (String gitImplName : gitImplNames) {
            Object[] item = {gitImplName};
            arguments.add(item);
        }
        return arguments;
    }

    final FolderForBenchmark tmp = new FolderForBenchmark();
    File gitDir;
    GitClient gitClient;
    List<RefSpec> narrowRefSpecs = new ArrayList<>();
    List<RefSpec> wideRefSpecs = new ArrayList<>();
    URIish urIish;

    @Before
    public void setupEnv() throws Exception {
        tmp.before();
        gitDir = tmp.newFolder();
        gitClient = Git.with(TaskListener.NULL, new EnvVars()).in(gitDir).using(gitExe).getClient();

        // narrow refspec
        narrowRefSpecs.add(new RefSpec("+refs/heads/master:refs/remotes/origin/master"));

        // wide refspec
        wideRefSpecs.add(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));

        // initialize the test folder for git fetch
        gitClient.init();
        System.out.println("Do Setup");
    }

    @After
    public void doTearDown() {
        try {
            File gitDir = gitClient.withRepository((repo, channel) -> repo.getDirectory());
            System.out.println(gitDir.isDirectory());
        } catch (Exception e) {
            e.getMessage();
        }
        tmp.after();
        System.out.println("Do TearDown");
    }

    // See GitRedundantFetchBenchmark to see the description behind these tests. These tests are performed for just one
    // repository, that is, java-logging-benchmark.

    @Test
    public void gitFetchBenchmarkBaselineWithWideRefSpec() throws Exception {
        urIish = new URIish("file:///tmp/experiment/test4/java-logging-benchmarks.git");
        long start = System.nanoTime();
        FetchCommand fetch = gitClient.fetch_().from(urIish, wideRefSpecs);
        fetch.execute();
        long end = System.nanoTime();
        System.out.println("The execution time in ms + " + (end - start)/1000000);
    }

    @Test
    public void gitFetchBenchmarkBaselineWithNarrowRefSpec() throws Exception {
        urIish = new URIish("file:///tmp/experiment/test4/java-logging-benchmarks.git");
        long start = System.nanoTime();
        FetchCommand fetch = gitClient.fetch_().from(urIish, narrowRefSpecs);
        fetch.execute();
        long end = System.nanoTime();
        System.out.println("The execution time in ms + " + (end - start)/1000000);
    }

    @Test
    public void gitFetchBenchmarkWithHonorRefSpec() throws Exception {
        urIish = new URIish("file:///tmp/experiment/test4/java-logging-benchmarks.git");
        long start = System.nanoTime();
        FetchCommand fetch = gitClient.fetch_().from(urIish, narrowRefSpecs);
        fetch.execute();
        FetchCommand incrementalFetch = gitClient.fetch_().from(urIish, narrowRefSpecs);
        incrementalFetch.execute();
        long end = System.nanoTime();
        System.out.println("The execution time in ms + " + (end - start)/1000000);
    }

    @Test
    public void gitFetchBenchmarkWithoutHonorRefSpec() throws Exception {
        urIish = new URIish("file:///tmp/experiment/test4/java-logging-benchmarks.git");
        long start = System.nanoTime();
        FetchCommand fetch = gitClient.fetch_().from(urIish, wideRefSpecs);
        fetch.execute();
        FetchCommand incrementalFetch = gitClient.fetch_().from(urIish, narrowRefSpecs);
        incrementalFetch.execute();
        long end = System.nanoTime();
        System.out.println("The execution time in ms + " + (end - start)/1000000);
    }
}
