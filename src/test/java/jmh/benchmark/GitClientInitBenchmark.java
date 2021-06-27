package jmh.benchmark;

import hudson.EnvVars;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.InitCommand;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.File;

//@JmhBenchmark
public class GitClientInitBenchmark {

    @State(Scope.Thread)
    public static class JenkinsState {

        @Param({"git", "jgit"})
        String gitExe;

        final FolderForBenchmark tmp = new FolderForBenchmark();
        File gitDir;
        GitClient gitClient;

        /**
         * We want to create a temporary local git repository after each iteration of the benchmark, similar to
         * "before" and "after" JUnit annotations.
         */
        @Setup(Level.Iteration)
        public void setup() throws Exception {
            tmp.before();
            gitDir = tmp.newFolder();

            gitClient = Git.with(TaskListener.NULL, new EnvVars()).in(gitDir).using(gitExe).getClient();

            System.out.println("Do Setup");
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            try {
                // making sure that git init made a git an empty repository
                File gitDir = gitClient.withRepository((repo, channel) -> repo.getDirectory());
                System.out.println(gitDir.isDirectory());
            } catch (Exception e){
                e.getMessage();
            }
            tmp.after();
            System.out.println("Do TearDown");
        }
    }

    @Benchmark
    public void gitInitBenchmark(JenkinsState jenkinsState, Blackhole blackhole) throws Exception {
        InitCommand initCmd = jenkinsState.gitClient.init_().workspace(jenkinsState.gitDir.getAbsolutePath());
        initCmd.execute();
        blackhole.consume(initCmd);
    }

}
