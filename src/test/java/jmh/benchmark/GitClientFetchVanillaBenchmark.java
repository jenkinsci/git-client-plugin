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
 * A vanilla benchmark is basically a performance test without JMH, it uses System.nanoTime() to measure the execution
 * time. This test was created for the sole purpose to "sanity check" the JMH benchmark results.
 */
@RunWith(Parameterized.class)
public class GitClientFetchVanillaBenchmark {

    String gitExe;

    public GitClientFetchVanillaBenchmark(final String gitImplName) {
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
    List<RefSpec> refSpecs = new ArrayList<>();
    URIish urIish;

    @Before
    public void setupEnv() throws Exception {
        tmp.before();
        gitDir = tmp.newFolder();
        gitClient = Git.with(TaskListener.NULL, new EnvVars()).in(gitDir).using(gitExe).getClient();

        // fetching all branches branch
        refSpecs.add(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));

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

    @Test
    public void gitFetchBenchmark1() throws Exception {
        urIish = new URIish("file:///tmp/experiment/test4/java-logging-benchmarks.git");
        FetchCommand fetch = gitClient.fetch_().from(urIish, refSpecs);
        long begin = System.nanoTime();
        fetch.execute();
        long end = System.nanoTime();
        System.out.println("The execution time is" + " " + (end - begin));
    }

    @Test
    public void gitFetchBenchmark2() throws Exception {
        urIish = new URIish("file:///tmp/experiment/test2/coreutils.git");
        FetchCommand fetch = gitClient.fetch_().from(urIish, refSpecs);
        long begin = System.nanoTime();
        fetch.execute();
        long end = System.nanoTime();
        System.out.println("The execution time is" + " " + (end - begin));
    }

    @Test
    public void gitFetchBenchmark3() throws Exception {
        urIish = new URIish("file:///tmp/experiment/test/cairo.git");
        FetchCommand fetch = gitClient.fetch_().from(urIish, refSpecs);
        long begin = System.nanoTime();
        fetch.execute();
        long end = System.nanoTime();
        System.out.println("The execution time is" + " " + (end - begin));
    }

    @Test
    public void gitFetchBenchmark4() throws Exception {
        urIish = new URIish("file:///tmp/experiment/test3/samba.git");
        FetchCommand fetch = gitClient.fetch_().from(urIish, refSpecs);
        long begin = System.nanoTime();
        fetch.execute();
        long end = System.nanoTime();
        System.out.println("The execution time is" + " " + (end - begin));
    }
}
