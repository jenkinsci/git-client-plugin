package jmh.benchmark;


import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import jenkins.benchmark.jmh.BenchmarkFinder;


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
     * Returns true if property benchmark.run is set.
     * @return true if benchmarks should be run
     */
    private boolean shouldRunBenchmarks() throws Exception {
        return Boolean.parseBoolean(System.getProperty("benchmark.run", "false"));
    }

    @Test
    public void runJmhBenchmarks() throws Exception {
        if (!shouldRunBenchmarks()) {
            String msg = "{\"benchmark.run\": false, \"reason\": \"Benchmark not run because benchmark.run is false\"}";
            Files.write(Paths.get("jmh-report.json"), msg.getBytes(StandardCharsets.UTF_8));
            return;
        }
        ChainedOptionsBuilder options = new OptionsBuilder()
                .mode(Mode.AverageTime) // Performance metric is Average time (ms per operation)
                .warmupIterations(1) // Used to warm JVM before executing benchmark tests
                .measurementIterations(3)
                .timeUnit(TimeUnit.MILLISECONDS)
                .threads(2) // TODO: Increase the number of threads and measure performance
                .forks(1)   // Need to increase more forks to get more observations, increases precision.
                .shouldFailOnError(true) // Will stop forking of JVM as soon as there is a compilation error
                .shouldDoGC(true) // do GC between measurement iterations
                .resultFormat(ResultFormatType.JSON) // store the results in a file called jmh-report.json
                .result("jmh-report.json");

        BenchmarkFinder bf = new BenchmarkFinder(getClass());
        bf.findBenchmarks(options);
        new Runner(options.build()).run();
    }
}
