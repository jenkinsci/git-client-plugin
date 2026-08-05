package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@ParameterizedClass(name = "{0}")
@MethodSource("pushParameters")
class PushSimpleTest {

    @Parameter(0)
    protected String gitImpl;

    private static File bareRepo;
    protected static URIish bareURI;
    private static GitClient bareGitClient;
    private static ObjectId bareFirstCommit;

    private ObjectId previousCommit;

    private File workingRepo;
    protected GitClient workingGitClient;
    private ObjectId workingCommit;
    private boolean skipVerify = false;

    @TempDir
    private static File staticTemporaryFolder;

    @TempDir
    private File temporaryFolder;

    @Test
    void pushNonFastForwardThrows() throws Exception {
        checkoutOldBranchAndCommitFile(); // Old branch can't be pushed without force()
        assertThrows(
                GitException.class,
                () -> workingGitClient
                        .push()
                        .to(bareURI)
                        .ref("master")
                        .timeout(1)
                        .execute());
    }

    @Test
    void pushBadURIThrows() throws Exception {
        checkoutAndCommitFile();
        URIish bad = new URIish(bareURI.toString() + "-bad");
        assertThrows(
                GitException.class,
                () -> workingGitClient.push().to(bad).ref("master").execute());
    }

    static List<Arguments> pushParameters() {
        List<Arguments> parameters = new ArrayList<>();
        Arguments gitParameter = Arguments.of("git");
        parameters.add(gitParameter);
        Arguments jgitParameter = Arguments.of("jgit");
        parameters.add(jgitParameter);
        return parameters;
    }

    @BeforeEach
    void createWorkingRepository() throws Exception {
        skipVerify = false;
        hudson.EnvVars env = new hudson.EnvVars();
        TaskListener listener = StreamTaskListener.fromStderr();
        workingRepo = newFolder(temporaryFolder, "PushSimpleTest-" + System.nanoTime());
        workingGitClient =
                Git.with(listener, env).in(workingRepo).using(gitImpl).getClient();
        workingGitClient
                .clone_()
                .url(bareRepo.getAbsolutePath())
                .repositoryName("origin")
                .execute();
        workingGitClient
                .checkout()
                .branch("master")
                .deleteBranchIfExist(true)
                .ref("origin/master")
                .execute();
        assertNotNull(bareFirstCommit);
        assertTrue(
                workingGitClient.revList("origin/master").contains(bareFirstCommit),
                "Clone does not contain " + bareFirstCommit);
        ObjectId workingHead = workingGitClient.getHeadRev(workingRepo.getAbsolutePath(), "master");
        ObjectId bareHead = bareGitClient.getHeadRev(bareRepo.getAbsolutePath(), "master");
        assertEquals(bareHead, workingHead, "Initial checkout of master has different HEAD than bare repo");
        CliGitCommand gitCmd = new CliGitCommand(workingGitClient);
        gitCmd.initializeRepository("PushSimpleTest user", "email.from.git.client@example.com");
    }

    @BeforeAll
    static void createBareRepository() throws Exception {
        /* Create the bare repository */
        bareRepo = newFolder(staticTemporaryFolder, "PushSimpleTest-" + System.nanoTime());
        bareURI = new URIish(bareRepo.getAbsolutePath());
        hudson.EnvVars env = new hudson.EnvVars();
        TaskListener listener = StreamTaskListener.fromStderr();
        bareGitClient = Git.with(listener, env).in(bareRepo).using("git").getClient();
        bareGitClient.init_().workspace(bareRepo.getAbsolutePath()).bare(true).execute();

        /* Clone the bare repository into a working copy */
        File cloneRepo = newFolder(staticTemporaryFolder, "PushSimpleTest-" + System.nanoTime());
        GitClient cloneGitClient =
                Git.with(listener, env).in(cloneRepo).using("git").getClient();
        cloneGitClient
                .clone_()
                .url(bareRepo.getAbsolutePath())
                .repositoryName("origin")
                .execute();
        CliGitCommand gitCmd = new CliGitCommand(cloneGitClient);
        gitCmd.initializeRepository("PushSimpleTest user", "email.from.git.client@example.com");

        /* Add a file with random content to the current branch of working repo */
        File added = File.createTempFile("added-", ".txt", cloneRepo);
        String randomContent = java.util.UUID.randomUUID().toString();
        String addedContent = "Initial commit to branch master content '" + randomContent + "'";
        Files.writeString(added.toPath(), addedContent, StandardCharsets.UTF_8);
        cloneGitClient.add(added.getName());
        cloneGitClient.commit("Initial commit to master file " + added.getName() + " with " + randomContent);
        Files.writeString(added.toPath(), "Another revision " + randomContent, StandardCharsets.UTF_8);
        cloneGitClient.add(added.getName());
        cloneGitClient.commit("Second commit to master");

        /* Push HEAD of current branch to "master" on the bare repository */
        cloneGitClient.push().to(bareURI).ref("HEAD:master").execute();

        /* Remember the SHA1 of the first commit */
        bareFirstCommit = bareGitClient.getHeadRev(bareRepo.getAbsolutePath(), "master");
    }

    @AfterAll
    static void removeBareRepository() throws Exception {
        FileUtils.deleteDirectory(bareRepo);
    }

    protected void checkoutAndCommitFile() throws Exception {
        previousCommit = checkoutBranch(false);
        workingCommit = commitFileToCurrentBranch();
    }

    protected void checkoutOldBranchAndCommitFile() throws Exception {
        previousCommit = checkoutBranch(true);
        workingCommit = commitFileToCurrentBranch();
    }

    private Collection<String> getBranchNames(List<Branch> branches) {
        return branches.stream().map(Branch::getName).toList();
    }

    private ObjectId checkoutBranch(boolean useOldCommit) throws Exception {
        /* Checkout "master" */
        workingGitClient.checkoutBranch("master", "origin/master" + (useOldCommit ? "^" : ""));
        List<Branch> branches = workingGitClient.getBranchesContaining("master", false);
        assertThat(getBranchNames(branches), contains("master"));
        return bareGitClient.getHeadRev(bareRepo.getAbsolutePath(), "master");
    }

    private ObjectId commitFileToCurrentBranch() throws Exception {
        /* Add a file with random content to the current branch of working repo */
        File added = File.createTempFile("added-", ".txt", workingRepo);
        String randomContent = java.util.UUID.randomUUID().toString();
        String addedContent = "Push test " + randomContent;
        Files.writeString(added.toPath(), addedContent, StandardCharsets.UTF_8);
        workingGitClient.add(added.getName());
        workingGitClient.commit("Added " + added.getName() + " with " + randomContent);

        /* Confirm file was committed */
        workingCommit = workingGitClient.getHeadRev(workingRepo.getAbsolutePath(), "master");
        assertNotNull(workingCommit);
        assertNotEquals(bareFirstCommit, workingCommit);

        return workingCommit;
    }

    private static File newFolder(File root, String... subDirs) throws Exception {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + result);
        }
        return result;
    }
}
