package jmh.benchmark;

import hudson.EnvVars;
import hudson.model.TaskListener;
import org.eclipse.jgit.lib.ObjectId;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class GitClientLSRemoteBenchmark {

    @State(Scope.Thread)
    public static class ClientState {

        @Param({"git", "jgit"})
        String gitExe;

        @Param({"https://github.com/jenkinsci/ec2-plugin.git",
                "https://github.com/jenkinsci/git-client-plugin.git",
                "https://github.com/jenkinsci/jenkins.git",
                "https://github.com/ruby/ruby.git",
                "https://github.com/kubernetes/kubernetes.git"})
        String repositoryURL;

        final FolderForBenchmark tmp = new FolderForBenchmark();
        File gitDir;
        GitClient gitClient;
        Map<String, ObjectId> remoteReferences;

        /**
         * We want to create a temporary local git repository after each iteration of the benchmark, works just like
         * "before" and "after" JUnit annotations.
         */
        @Setup(Level.Iteration)
        public void setup() throws Exception {
            tmp.before();
            gitDir = tmp.newFolder();

            gitClient = Git.with(TaskListener.NULL, new EnvVars()).in(gitDir).using(gitExe).getClient();
            remoteReferences = new HashMap<>();

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

        public boolean validateReferences(String gitURL, int numberOfRefs) {
            switch (gitURL) {
                case "https://github.com/jenkinsci/ec2-plugin.git":
                    return numberOfRefs >= 696;
                case "https://github.com/jenkinsci/git-client-plugin.git":
                    return numberOfRefs >= 836;
                case "https://github.com/jenkinsci/jenkins.git":
                    return numberOfRefs >= 7137;
                case "https://github.com/ruby/ruby.git":
                    return numberOfRefs >= 6308;
                case "https://github.com/kubernetes/kubernetes.git":
                    return numberOfRefs >= 88027;
            }
            return false;
        }
    }

    @Benchmark
    public void gitlsremoteBenchmark(ClientState clientState, Blackhole blackhole) throws Exception {
        clientState.remoteReferences = clientState.gitClient.getRemoteReferences(clientState.repositoryURL, null, false, false);
        if (clientState.validateReferences(clientState.repositoryURL, clientState.remoteReferences.keySet().size())) {
            blackhole.consume(clientState.remoteReferences);
        }
        blackhole.consume(clientState.remoteReferences);
    }
}
