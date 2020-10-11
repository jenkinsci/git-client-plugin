//package jmh.benchmark;
//
//import hudson.EnvVars;
//import hudson.model.TaskListener;
//import org.eclipse.jgit.transport.RefSpec;
//import org.eclipse.jgit.transport.URIish;
//import org.jenkinsci.plugins.gitclient.FetchCommand;
//import org.jenkinsci.plugins.gitclient.Git;
//import org.jenkinsci.plugins.gitclient.GitClient;
//import org.junit.After;
//import org.junit.Before;
//import org.junit.Test;
//import org.junit.runner.RunWith;
//import org.junit.runners.Parameterized;
//
//import java.io.File;
//import java.nio.file.Files;
//import java.util.ArrayList;
//import java.util.Collection;
//import java.util.List;
//
//import static org.junit.Assert.assertFalse;
//import static org.junit.Assert.assertTrue;
//
///**
// * A vanilla benchmark is basically a performance test without JMH, it uses System.nanoTime() to measure the execution
// * time. This test was created for the sole purpose to "sanity check" the JMH benchmark results.
// */
//@RunWith(Parameterized.class)
//public class GitClientFetchVanillaBenchmark {
//
//    String gitExe;
//
//    public GitClientFetchVanillaBenchmark(final String gitImplName) {
//        this.gitExe = gitImplName;
//    }
//
//    @Parameterized.Parameters(name = "{0}")
//    public static Collection gitObjects() {
//        List<Object[]> arguments = new ArrayList<>();
//        String[] gitImplNames = {"git", "jgit"};
//        for (String gitImplName : gitImplNames) {
//            Object[] item = {gitImplName};
//            arguments.add(item);
//        }
//        return arguments;
//    }
//
//    final FolderForBenchmark tmp = new FolderForBenchmark();
//    File gitDir;
//    File parentDir;
//    GitClient gitClient;
//    List<RefSpec> refSpecs = new ArrayList<>();
//    URIish urIish;
//    String urlOne = "https://github.com/stephenc/java-logging-benchmarks.git";
//    String urlTwo = "https://github.com/uutils/coreutils.git";
//    String urlThree = "https://github.com/freedesktop/cairo.git";
//    String urlFour = "https://github.com/samba-team/samba.git";
//    File repoOneDir;
//    File repoTwoDir;
//    File repoThreeDir;
//    File repoFourDir;
//
//    private File cloneUpstreamRepositoryLocally(File parentDir, String repoUrl) throws Exception {
//        String repoName = repoUrl.split("/")[repoUrl.split("/").length - 1];
//        File gitRepoDir = new File(parentDir, repoName);
//        gitRepoDir.mkdir();
//        GitClient cloningGitClient = Git.with(TaskListener.NULL, new EnvVars()).in(gitRepoDir).using(gitExe).getClient();
//        cloningGitClient.clone_().url(repoUrl).execute();
//        assertTrue("Unable to create git repo", gitRepoDir.exists());
//        return gitRepoDir;
//    }
//
//    @Before
//    public void setupEnv() throws Exception {
//        tmp.before();
//        gitDir = tmp.newFolder(); // local test git repository
//        parentDir = tmp.newFolder(); // local copy of upstream git repository
//        repoOneDir = cloneUpstreamRepositoryLocally(parentDir, urlOne);
//        repoTwoDir = cloneUpstreamRepositoryLocally(parentDir, urlTwo);
//        repoThreeDir = cloneUpstreamRepositoryLocally(parentDir, urlThree);
//        repoFourDir = cloneUpstreamRepositoryLocally(parentDir, urlFour);
//
//        gitClient = Git.with(TaskListener.NULL, new EnvVars()).in(gitDir).using(gitExe).getClient();
//
//        // fetching all branches branch
//        refSpecs.add(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));
//
//        // initialize the test folder for git fetch
//        gitClient.init();
//        System.out.println("Do Setup");
//    }
//
//    @After
//    public void tearDown() {
//        try {
//            File gitDir = gitClient.withRepository((repo, channel) -> repo.getDirectory());
//            System.out.println(gitDir.isDirectory());
//        } catch (Exception e) {
//            e.getMessage();
//        }
//        tmp.after();
//        System.out.println("Do TearDown");
//    }
//
//    @Test
//    public void gitFetchBenchmark1() throws Exception {
//        urIish = new URIish("file://" + repoOneDir.getAbsolutePath());
//        FetchCommand fetch = gitClient.fetch_().from(urIish, refSpecs);
//        long begin = System.nanoTime();
//        fetch.execute();
//        long end = System.nanoTime();
//        System.out.println("The execution time is" + " " + (end - begin));
//    }
//
//    @Test
//    public void gitFetchBenchmark2() throws Exception {
//        urIish = new URIish("file://" + repoTwoDir.getAbsolutePath());
//        FetchCommand fetch = gitClient.fetch_().from(urIish, refSpecs);
//        long begin = System.nanoTime();
//        fetch.execute();
//        long end = System.nanoTime();
//        System.out.println("The execution time is" + " " + (end - begin));
//    }
//
//    @Test
//    public void gitFetchBenchmark3() throws Exception {
//        urIish = new URIish("file://" + repoThreeDir.getAbsolutePath());
//        FetchCommand fetch = gitClient.fetch_().from(urIish, refSpecs);
//        long begin = System.nanoTime();
//        fetch.execute();
//        long end = System.nanoTime();
//        System.out.println("The execution time is" + " " + (end - begin));
//    }
//
//    @Test
//    public void gitFetchBenchmark4() throws Exception {
//        urIish = new URIish("file://" + repoFourDir.getAbsolutePath());
//        FetchCommand fetch = gitClient.fetch_().from(urIish, refSpecs);
//        long begin = System.nanoTime();
//        fetch.execute();
//        long end = System.nanoTime();
//        System.out.println("The execution time is" + " " + (end - begin));
//    }
//}
