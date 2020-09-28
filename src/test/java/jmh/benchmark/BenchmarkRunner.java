package jmh.benchmark;

import hudson.EnvVars;
import hudson.model.TaskListener;

import java.io.File;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;

import jenkins.benchmark.jmh.BenchmarkFinder;

import org.jenkinsci.plugins.gitclient.ChangelogCommand;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.JGitAPIImpl;

import org.junit.Test;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * A runner class which finds benchmark tests annotated with @JmhBenchmark and launches them with the selected options
 * provided by JMH
 */
public class BenchmarkRunner {
    /**
     * Return true if benchmarks should be run.
     *
     * If one of the last 3 commits includes the word "Benchmark" or
     * "benchmark", then benchmarks should run.  Otherwise, do not run
     * benchmarks.
     *
     * See Jenkinsfile for the same logic.
     *
     * @return true if benchmarks should be run
     */
    private boolean shouldRunBenchmarks() throws Exception {
        GitClient defaultClient = Git.with(TaskListener.NULL, new EnvVars()).in(new File(".")).using("git").getClient();
        StringWriter changelogWriter = new StringWriter();
        defaultClient.changelog().includes("HEAD").max(3).to(changelogWriter).execute();
        return changelogWriter.toString().matches("[Bb]enchmark");
    }

    @Test
    public void runJmhBenchmarks() throws Exception {
        if (!shouldRunBenchmarks()) {
            return;
        }
        ChainedOptionsBuilder options = new OptionsBuilder()
                .mode(Mode.AverageTime) // Performance metric is Average time (ms per operation)
                .warmupIterations(5) // Used to warm JVM before executing benchmark tests
                .measurementIterations(5)
                .timeUnit(TimeUnit.MILLISECONDS)
                .threads(2) // TODO: Increase the number of threads and measure performance
                .forks(2)   // Need to increase more forks to get more observations, increases precision.
                .shouldFailOnError(true) // Will stop forking of JVM as soon as there is a compilation error
                .shouldDoGC(true) // do GC between measurement iterations
                .output("jmh-report.json");
//                .resultFormat(ResultFormatType.JSON) // store the results in a file called jmh-report.json
//                .result("jmh-report.json");

        BenchmarkFinder bf = new BenchmarkFinder(getClass());
        bf.findBenchmarks(options);
        new Runner(options.build()).run();
    }
}
