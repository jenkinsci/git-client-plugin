package jmh.benchmark;

import jenkins.benchmark.jmh.BenchmarkFinder;
import org.junit.Test;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * A runner class which finds benchmark tests annotated with @JmhBenchmark and launches them with the selected options
 * provided by JMH
 */
public class BenchmarkRunner {
    @Test
    public void runJmhBenchmarks() throws Exception {
        ChainedOptionsBuilder options = new OptionsBuilder()
                .mode(Mode.AverageTime) // Performance metric is Average time (ms per operation)
                .warmupIterations(1) // Used to warm JVM before executing benchmark tests
                .measurementIterations(3)
                .timeUnit(TimeUnit.MILLISECONDS)
                .threads(2) // TODO: Increase the number of threads and measure performance
                .forks(1)   // Need to increase more forks to get more observations, increases precision.
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
