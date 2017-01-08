package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.io.FileUtils;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;

import org.jvnet.hudson.test.Issue;

/**
 *
 * @author Mark Waite
 */
@RunWith(Parameterized.class)
public class GitClientTest {

    /** Git implementation name, either "git", "jgit", or "jgitapache". */
    private final String gitImplName;

    /** Git client plugin repository directory.  */
    private final File srcRepoDir = new File(".");

    /** Absolute path to git client plugin repository directory. */
    private final String srcRepoAbsolutePath = srcRepoDir.getAbsolutePath();

    /** GitClient for plugin development respository. */
    private final GitClient srcGitClient;

    /** commit known to exist in git client plugin repository and in upstream. */
    final ObjectId srcGitClientCommit = ObjectId.fromString("f75720d5de9d79ab4be2633a21de23b3ccbf8ce3");
    final String srcGitClientCommitAuthor = "Teubel György";
    final String srcGitClientCommitEmail = "<tgyurci@freemail.hu>";
    final ObjectId srcGitClientCommitPredecessor = ObjectId.fromString("867e5f148377fd5a6d96e5aafbdaac132a117a5a");

    /** URL of upstream (GitHub) repository. */
    private final String upstreamRepoURL = "https://github.com/jenkinsci/git-client-plugin";

    /* Directory allocator */
    private final TemporaryDirectoryAllocator dirAllocator = new TemporaryDirectoryAllocator();

    /* Instance of object under test */
    private GitClient gitClient = null;

    /* Capabilities of command line git in current environment */
    private final boolean CLI_GIT_REPORTS_DETACHED_SHA1;
    private final boolean CLI_GIT_SUPPORTS_SUBMODULES;
    private final boolean CLI_GIT_SUPPORTS_SUBMODULE_DEINIT;
    private final boolean CLI_GIT_SUPPORTS_SUBMODULE_RENAME;

    @Rule
    public TemporaryFolder repoFolder = new TemporaryFolder();

    public GitClientTest(final String gitImplName) throws IOException, InterruptedException {
        this.gitImplName = gitImplName;
        this.srcGitClient = Git.with(TaskListener.NULL, new EnvVars()).in(srcRepoDir).using(gitImplName).getClient();

        CliGitAPIImpl cliGitClient;
        if (this.srcGitClient instanceof CliGitAPIImpl) {
            cliGitClient = (CliGitAPIImpl) this.srcGitClient;
        } else {
            cliGitClient = (CliGitAPIImpl) Git.with(TaskListener.NULL, new EnvVars()).in(srcRepoDir).using("git").getClient();
        }
        CLI_GIT_REPORTS_DETACHED_SHA1 = cliGitClient.isAtLeastVersion(1, 8, 0, 0);
        CLI_GIT_SUPPORTS_SUBMODULES = cliGitClient.isAtLeastVersion(1, 8, 0, 0);
        CLI_GIT_SUPPORTS_SUBMODULE_DEINIT = cliGitClient.isAtLeastVersion(1, 9, 0, 0);
        CLI_GIT_SUPPORTS_SUBMODULE_RENAME = cliGitClient.isAtLeastVersion(1, 9, 0, 0);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection gitObjects() {
        List<Object[]> arguments = new ArrayList<>();
        String[] gitImplNames = {"git", "jgit", "jgitapache"};
        for (String gitImplName : gitImplNames) {
            Object[] item = {gitImplName};
            arguments.add(item);
        }
        return arguments;
    }

    @BeforeClass
    public static void setCliGitDefaults() throws Exception {
        /* Command line git commands fail unless certain default values are set */
        CliGitCommand gitCmd = new CliGitCommand(null);
        gitCmd.setDefaults();
    }

    @Before
    public void setGitClient() throws IOException, InterruptedException {
        gitClient = Git.with(TaskListener.NULL, new EnvVars()).in(repoFolder.getRoot()).using(gitImplName).getClient();
        File gitDir = gitClient.getRepository().getDirectory();
        assertFalse("Already found " + gitDir, gitDir.isDirectory());
        gitClient.init_().workspace(repoFolder.getRoot().getAbsolutePath()).execute();
        assertTrue("Missing " + gitDir, gitDir.isDirectory());
        gitClient.setRemoteUrl("origin", srcRepoAbsolutePath);
    }

    @After
    public void deleteTemporaryDirectories() {
        dirAllocator.disposeAsync();
    }

    private ObjectId commitOneFile() throws Exception {
        return commitOneFile("Committed one text file");
    }

    private ObjectId commitOneFile(final String commitMessage) throws Exception {
        File oneFile = new File(repoFolder.getRoot(), "One-File.txt");
        try (PrintWriter writer = new PrintWriter(oneFile, "UTF-8")) {
            writer.printf("A random UUID: %s\n", UUID.randomUUID().toString());
        } catch (FileNotFoundException | UnsupportedEncodingException ex) {
            throw new GitException(ex);
        }
        gitClient.add("One-File.txt");
        gitClient.commit(commitMessage);
        List<ObjectId> headList = gitClient.revList("HEAD");
        assertThat(headList.size(), is(greaterThan(0)));
        return headList.get(0);
    }

    private final Random random = new Random();

    private String randomName() {
        final String[] names = {
            "Cinda Bückmaster",
            "Miloš Obrenović",
            "Vojtěch Šafařík",
            "Pfalzgraf Wolfgang von Zweibrücken",
            "Johann Friedrich Konrad Carl Eduard Horst Arnold Matthias Prinz von Sachsen-Meiningen Herzog zu Sachsen",
            "Al-Mu'tamid",
            "Øresund Bridge",
            "Caterina œil Perrault",
            "Kesha Ríckel",
            "Shawnda Bœlter",
            "Hans Gǿr",
            "Thu Null"
        };
        return names[random.nextInt(names.length)];
    }

    private String randomEmail(String name) {
        return name.replaceAll(" ", ".") + "@middle.earth";
    }

    @Test
    @Issue("37794")
    public void tagWithSlashes() throws Exception {
        commitOneFile();
        gitClient.tag("has/a/slash", "This tag has a slash ('/')");
        assertThat(gitClient.getTagMessage("has/a/slash"), is("This tag has a slash ('/')"));
        assertThat(gitClient.getTagNames(null), hasItem("has/a/slash"));
    }

    private void assertLogContains(ObjectId commitA, ObjectId commitB, String prefix, String expected) throws GitException, InterruptedException {
        boolean found = false;
        StringBuilder builder = new StringBuilder();
        for (String revisionMessage : gitClient.showRevision(commitA, commitB)) {
            builder.append(revisionMessage);
            if (revisionMessage.startsWith(prefix)) {
                assertThat(revisionMessage, containsString(expected));
                found = true;
            }
        }
        assertTrue("no " + prefix + ", expected: '" + expected + "' in " + builder.toString(), found);
    }

    private void assertAuthor(ObjectId commitA, ObjectId commitB, String name, String email) throws GitException, InterruptedException {
        final String prefix = "author ";
        final String expected = prefix + name + " <" + email + ">";
        assertLogContains(commitA, commitB, prefix, expected);
    }

    private void assertCommitter(ObjectId commitA, ObjectId commitB, String name, String email) throws GitException, InterruptedException {
        final String prefix = "committer ";
        final String expected = prefix + name + " <" + email + ">";
        assertLogContains(commitA, commitB, prefix, expected);
    }

    @Test
    public void testSetAuthor_String_String() throws Exception {
        final ObjectId commitA = commitOneFile();
        final String name = randomName();
        final String email = randomEmail(name);
        gitClient.setAuthor(name, email);
        final ObjectId commitB = commitOneFile();
        assertAuthor(commitA, commitB, name, email);
    }

    @Test(expected = GitException.class)
    public void testCommitNotFoundException() throws GitException, InterruptedException {
        /* Search wrong repository for a commit */
        assertAuthor(srcGitClientCommitPredecessor, srcGitClientCommit, srcGitClientCommitAuthor, srcGitClientCommitEmail);
    }

    @Test
    public void testSetAuthor_PersonIdent() throws Exception {
        final ObjectId commitA = commitOneFile();
        final String name = randomName();
        final String email = randomEmail(name);
        gitClient.setAuthor(new PersonIdent(name, email));
        final ObjectId commitB = commitOneFile();
        assertAuthor(commitA, commitB, name, email);
    }

    @Test
    public void testGetWorkTree() {
        assertThat(gitClient.getWorkTree(), is(new FilePath(repoFolder.getRoot())));
    }

    @Test
    public void testSetCommitter_String_String() throws Exception {
        final ObjectId commitA = commitOneFile();
        final String name = randomName();
        final String email = randomEmail(name);
        gitClient.setCommitter(name, email);
        final ObjectId commitB = commitOneFile();
        assertCommitter(commitA, commitB, name, email);
    }

    @Test
    public void testSetCommitter_PersonIdent() throws Exception {
        final ObjectId commitA = commitOneFile();
        final String name = randomName();
        final String email = randomEmail(name);
        gitClient.setAuthor(new PersonIdent(name, email));
        final ObjectId commitB = commitOneFile();
        assertAuthor(commitA, commitB, name, email);
    }

    @Test
    public void testGetRepository() {
        File expectedRepo = new File(repoFolder.getRoot(), ".git");
        assertEquals(expectedRepo, gitClient.getRepository().getDirectory());
    }

    @Test
    public void testInit() throws Exception {
        File gitDir = gitClient.getRepository().getDirectory();
        gitClient.init();
        assertTrue("init did not create " + gitDir, gitDir.isDirectory());
    }

    @Test
    public void testAdd() throws Exception {
        final ObjectId commitA = commitOneFile();
        assertNotNull(commitA);
    }

    @Test
    public void testCommit_String() throws Exception {
        final ObjectId commitA = commitOneFile();
        final String name = randomName();
        final String email = randomEmail(name);
        gitClient.setAuthor(new PersonIdent(name, email));
        final String expectedCommitMessage = "This is commit B's expected message";
        final ObjectId commitB = commitOneFile(expectedCommitMessage);
        assertLogContains(commitA, commitB, "    ", expectedCommitMessage);
    }

    @Test
    public void testCommit_3args() throws Exception {
        final ObjectId commitA = commitOneFile();
        final String authorName = randomName();
        final String authorEmail = randomEmail(authorName);
        gitClient.setAuthor(new PersonIdent(authorName, authorEmail));
        final String committerName = randomName();
        final String committerEmail = randomEmail(committerName);
        gitClient.setCommitter(new PersonIdent(committerName, committerEmail));
        final String expectedCommitMessage = "This is commit B's expected message";
        final ObjectId commitB = commitOneFile(expectedCommitMessage);
        assertLogContains(commitA, commitB, "    ", expectedCommitMessage);
        assertAuthor(commitA, commitB, authorName, authorEmail);
        assertCommitter(commitA, commitB, committerName, committerEmail);
    }

    @Test
    public void testHasGitRepo() throws Exception {
        assertTrue("Test repo '" + repoFolder.getRoot().getAbsolutePath() + "' not initialized", gitClient.hasGitRepo());
        StringBuilder fileList = new StringBuilder();
        for (File file : srcRepoDir.listFiles()) {
            fileList.append(file.getAbsolutePath());
            fileList.append(" ");
        }
        assertTrue("Source repo '" + srcRepoDir.getAbsolutePath() + "' not initialized, contains " + fileList.toString(), srcGitClient.hasGitRepo());

        File emptyDir = dirAllocator.allocate();
        emptyDir.mkdirs();
        assertTrue(emptyDir.exists());
        GitClient emptyClient = Git.with(TaskListener.NULL, new EnvVars()).in(emptyDir).using(gitImplName).getClient();
        assertFalse("Empty repo '" + emptyDir.getAbsolutePath() + "' initialized", emptyClient.hasGitRepo());
    }

    @Test
    public void testIsCommitInRepo() throws Exception {
        assertTrue(srcGitClient.isCommitInRepo(srcGitClientCommit));
        assertFalse(gitClient.isCommitInRepo(srcGitClientCommit));
    }

    @Test
    public void testGetRemoteUrl() throws Exception {
        assertEquals(srcRepoAbsolutePath, gitClient.getRemoteUrl("origin"));
    }

    @Test
    public void testSetRemoteUrl() throws Exception {
        assertEquals(srcRepoAbsolutePath, gitClient.getRemoteUrl("origin"));
        gitClient.setRemoteUrl("origin", upstreamRepoURL);
        assertEquals(upstreamRepoURL, gitClient.getRemoteUrl("origin"));
    }

    @Test
    public void testAddRemoteUrl() throws Exception {
        gitClient.addRemoteUrl("upstream", upstreamRepoURL);
        assertEquals(srcRepoAbsolutePath, gitClient.getRemoteUrl("origin"));
        assertEquals(upstreamRepoURL, gitClient.getRemoteUrl("upstream"));
    }

    private void assertFileInWorkingDir(GitClient client, String fileName) {
        File fileInRepo = new File(repoFolder.getRoot(), fileName);
        assertTrue(fileInRepo.getAbsolutePath() + " not found", fileInRepo.isFile());
    }

    private void assertFileNotInWorkingDir(GitClient client, String fileName) {
        File fileInRepo = new File(repoFolder.getRoot(), fileName);
        assertFalse(fileInRepo.getAbsolutePath() + " found", fileInRepo.isFile());
    }

    private void assertDirInWorkingDir(GitClient client, String dirName) {
        File dirInRepo = new File(repoFolder.getRoot(), dirName);
        assertTrue(dirInRepo.getAbsolutePath() + " found", dirInRepo.isDirectory());
    }

    private void assertDirNotInWorkingDir(GitClient client, String dirName) {
        File dirInRepo = new File(repoFolder.getRoot(), dirName);
        assertFalse(dirInRepo.getAbsolutePath() + " found", dirInRepo.isDirectory());
    }

    private boolean removeMatchingBranches(Set<Branch> filtered, Set<Branch> toRemove) {
        Set<ObjectId> objectIds = new HashSet<>();
        for (Branch removeBranch : toRemove) {
            objectIds.add(removeBranch.getSHA1());
        }
        boolean modified = false;
        for (Iterator<Branch> i = filtered.iterator(); i.hasNext();) {
            Branch checkBranch = i.next();
            if (objectIds.contains(checkBranch.getSHA1())) {
                modified = true;
                i.remove();
            }
        }
        return modified;
    }

    private void assertEmptyWorkingDir(GitClient gitClient) throws Exception {
        assertTrue(gitClient.hasGitRepo());
        File gitDir = new File(repoFolder.getRoot(), ".git");
        File[] gitDirListing = repoFolder.getRoot().listFiles();
        assertEquals(gitDir, gitDirListing[0]);
        assertEquals(1, gitDirListing.length);
    }

    private void assertDetachedHead(GitClient client, ObjectId ref) throws Exception {
        CliGitCommand gitCmd = new CliGitCommand(client);
        gitCmd.run("status");
        if (CLI_GIT_REPORTS_DETACHED_SHA1) {
            gitCmd.assertOutputContains(".*(Not currently on any branch|HEAD detached).*",
                            ".*" + ref.getName().substring(0, 6) + ".*");
        } else {
            gitCmd.assertOutputContains(".*Not currently on any branch.*");
        }
    }

    private void assertBranch(GitClient client, String branchName) throws Exception {
        CliGitCommand gitCmd = new CliGitCommand(client);
        gitCmd.run("status");
        gitCmd.assertOutputContains(".*On branch.*" + branchName + ".*");
    }

    private int lastFetchPath = -1;

    private void fetch(GitClient client, String remote, String firstRefSpec, String... optionalRefSpecs) throws Exception {
        List<RefSpec> refSpecs = new ArrayList<>();
        RefSpec refSpec = new RefSpec(firstRefSpec);
        refSpecs.add(refSpec);
        for (String refSpecString : optionalRefSpecs) {
            refSpecs.add(new RefSpec(refSpecString));
        }
        lastFetchPath = random.nextInt(2);
        switch (lastFetchPath) {
            default:
            case 0:
                if (remote.equals("origin")) {
                    /* If remote == "origin", randomly use default remote */
                    remote = random.nextBoolean() ? remote : null;
                }
                client.fetch(remote, refSpecs.toArray(new RefSpec[0]));
                break;
            case 1:
                URIish repoURL = new URIish(client.getRepository().getConfig().getString("remote", remote, "url"));
                boolean fetchTags = random.nextBoolean();
                boolean pruneBranches = random.nextBoolean();
                if (pruneBranches) {
                    client.fetch_().from(repoURL, refSpecs).tags(fetchTags).prune().execute();
                } else {
                    client.fetch_().from(repoURL, refSpecs).tags(fetchTags).execute();
                }
                break;
        }
    }

    @Test
    public void testCheckout_String() throws Exception {
        /* Confirm files not visible in empty repo */
        assertEmptyWorkingDir(gitClient);
        /* Fetch from origin repo */
        fetch(gitClient, "origin", "+refs/heads/*:refs/remotes/origin/*");

        /* Checkout a commit after README was added, before src directory was added */
        String ref = "5a865818566c9d03738cdcd49cc0a1543613fd41";
        gitClient.checkout(ref);
        /* Confirm README.md visible, src directory not */
        assertFileInWorkingDir(gitClient, "README.md");
        assertDirNotInWorkingDir(gitClient, "src");
        assertDetachedHead(gitClient, ObjectId.fromString(ref));

        /* Checkout a commit before README was added, before src directory was added */
        String olderRef = "28f42e8d299154cd209cb1c75457fa9966a74f33";
        gitClient.checkout(olderRef);
        assertFileNotInWorkingDir(gitClient, "README.md");
        assertDirNotInWorkingDir(gitClient, "src");
        assertDetachedHead(gitClient, ObjectId.fromString(olderRef));

        /* Checkout a commit after README and src were added */
        String newestRef = "ded4597c18562fabb862f6012fb041a40d0d651a";
        gitClient.checkout(newestRef);
        assertFileInWorkingDir(gitClient, "README.md");
        assertDirInWorkingDir(gitClient, "src");
        assertDetachedHead(gitClient, ObjectId.fromString(newestRef));
    }

    @Test
    public void testCheckout_String_String() throws Exception {
        fetch(gitClient, "origin", "+refs/heads/*:refs/remotes/origin/*");
        int branchNumber = 10 + random.nextInt(80);
        String baseName = "branchA-";
        String branchName = baseName + branchNumber++;

        /* Checkout a commit after README was added, before src directory was added */
        String ref = "5a865818566c9d03738cdcd49cc0a1543613fd41";
        gitClient.checkout(ref, branchName);
        /* Confirm README.md visible, src directory not */
        assertFileInWorkingDir(gitClient, "README.md");
        assertDirNotInWorkingDir(gitClient, "src");
        assertBranch(gitClient, branchName);

        /* Checkout a commit before README was added, before src directory was added */
        branchName = baseName + branchNumber++;
        String olderRef = "28f42e8d299154cd209cb1c75457fa9966a74f33";
        gitClient.checkout(olderRef, branchName);
        assertFileNotInWorkingDir(gitClient, "README.md");
        assertDirNotInWorkingDir(gitClient, "src");
        assertBranch(gitClient, branchName);

        /* Checkout a commit after README and src were added */
        branchName = baseName + branchNumber++;
        String newestRef = "ded4597c18562fabb862f6012fb041a40d0d651a";
        gitClient.checkout(newestRef, branchName);
        assertFileInWorkingDir(gitClient, "README.md");
        assertDirInWorkingDir(gitClient, "src");
        assertBranch(gitClient, branchName);
    }

    @Test
    public void testCheckout_0args() throws Exception {
        fetch(gitClient, "origin", "+refs/heads/*:refs/remotes/origin/*");
        int branchNumber = 10 + random.nextInt(80);
        String baseName = "branchA-";
        String branchName = baseName + branchNumber++;

        /* Checkout a commit after README was added, before src directory was added */
        String ref = "5a865818566c9d03738cdcd49cc0a1543613fd41";
        gitClient.checkout().ref(ref).branch(branchName).execute();
        /* Confirm README.md visible, src directory not */
        assertFileInWorkingDir(gitClient, "README.md");
        assertDirNotInWorkingDir(gitClient, "src");
        assertBranch(gitClient, branchName);

        /* Checkout a commit before README was added, before src directory was added */
        branchName = baseName + branchNumber++;
        String olderRef = "28f42e8d299154cd209cb1c75457fa9966a74f33";
        gitClient.checkout().ref(olderRef).branch(branchName).execute();
        assertFileNotInWorkingDir(gitClient, "README.md");
        assertDirNotInWorkingDir(gitClient, "src");
        assertBranch(gitClient, branchName);

        /* Checkout a commit after README and src were added */
        branchName = baseName + branchNumber++;
        String newestRef = "ded4597c18562fabb862f6012fb041a40d0d651a";
        gitClient.checkout().ref(newestRef).branch(branchName).execute();
        assertFileInWorkingDir(gitClient, "README.md");
        assertDirInWorkingDir(gitClient, "src");
        assertBranch(gitClient, branchName);
    }

    @Test
    public void testCheckoutBranch() throws Exception {
        File src = new File(repoFolder.getRoot(), "src");
        assertFalse(src.isDirectory());
        String branch = "master";
        String remote = fetchUpstream(branch);
        gitClient.checkoutBranch(branch, remote + "/" + branch);
        assertTrue(src.isDirectory());
    }

    @Test
    public void testDeleteRef() throws Exception {
        assertThat(gitClient.getRefNames(""), is(empty()));
        if (gitImplName.startsWith("jgit")) {
            // JGit won't delete refs from a repo without local commits
            commitOneFile();
        }
        String upstream = fetchUpstream("tests/getSubmodules", "tests/notSubmodules");
        assertThat(gitClient.getRefNames("refs/remotes/upstream/"), hasItems(
                "refs/remotes/upstream/tests/getSubmodules",
                "refs/remotes/upstream/tests/notSubmodules"
        ));
        gitClient.deleteRef("refs/remotes/upstream/tests/notSubmodules");
        assertThat(gitClient.getRefNames("refs/remotes/upstream/"), hasItems(
                "refs/remotes/upstream/tests/getSubmodules"
        ));
    }

    @Test(expected = GitException.class)
    public void testDeleteRefException() throws Exception {
        /* JGit won't delete current branch, CliGit will */
        assumeThat(gitImplName, startsWith("jgit"));
        assertThat(gitClient.getRefNames(""), is(empty()));
        commitOneFile(); // Creates commit on master branch
        Set<String> refNames = gitClient.getRefNames("");
        assertThat(refNames, hasItems("refs/heads/master"));
        gitClient.deleteRef("refs/heads/master"); // Throws - JGit cannot delete current branch
    }

    @Test
    public void testGetHeadRev_String() throws Exception {
        String url = repoFolder.getRoot().getAbsolutePath();

        ObjectId commitA = commitOneFile();
        Map<String, ObjectId> headRevMapA = gitClient.getHeadRev(url);
        assertThat(headRevMapA.keySet(), hasItems("refs/heads/master"));
        assertThat(headRevMapA.get("refs/heads/master"), is(commitA));

        ObjectId commitB = commitOneFile();
        Map<String, ObjectId> headRevMapB = gitClient.getHeadRev(url);
        assertThat(headRevMapB.keySet(), hasItems("refs/heads/master"));
        assertThat(headRevMapB.get("refs/heads/master"), is(commitB));
    }

    @Test
    public void testGetHeadRev_String_String() throws Exception {
        String url = repoFolder.getRoot().getAbsolutePath();

        ObjectId commitA = commitOneFile();
        assertThat(gitClient.getHeadRev(url, "master"), is(commitA));

        ObjectId commitB = commitOneFile();
        assertThat(gitClient.getHeadRev(url, "master"), is(commitB));
    }

    @Test(expected = GitException.class)
    public void testGetHeadRev_Exception() throws Exception {
        String url = "protocol://hostname:port/not-a-URL";

        ObjectId commitA = commitOneFile();
        Map<String, ObjectId> headRevMapA = gitClient.getHeadRev(url);
    }

    @Test
    public void testRefExists() throws Exception {
        String getSubmodulesRef = "refs/remotes/origin/tests/getSubmodules";
        assertFalse(gitClient.refExists(getSubmodulesRef));
        assertTrue(srcGitClient.refExists(getSubmodulesRef));
    }

    // @Test
    public void testGetRemoteReferences() throws Exception {
        String url = repoFolder.getRoot().getAbsolutePath();
        String pattern = null;
        boolean headsOnly = false; // Need variations here
        boolean tagsOnly = false; // Need variations here
        Map<String, ObjectId> expResult = null; // Working here
        Map<String, ObjectId> result = gitClient.getRemoteReferences(url, pattern, headsOnly, tagsOnly);
        assertEquals(expResult, result);
    }

    @Test
    public void testRevParse() throws Exception {
        ObjectId commitA = commitOneFile();
        assertThat(gitClient.revParse("master"), is(commitA));
        ObjectId commitB = commitOneFile();
        assertThat(gitClient.revParse("master"), is(commitB));
    }

    @Test(expected = GitException.class)
    public void testRevParseException() throws Exception {
        ObjectId commitA = commitOneFile();
        gitClient.revParse("non-existent-ref-" + random.nextInt());
    }

    @Test
    public void testRevList_() throws Exception {
        ObjectId commitA = commitOneFile();

        List<ObjectId> resultAll = new ArrayList<>();
        gitClient.revList_().to(resultAll).all().execute();
        assertThat(resultAll, contains(commitA));

        List<ObjectId> resultRef = new ArrayList<>();
        gitClient.revList_().to(resultRef).reference("master").execute();
        assertThat(resultRef, contains(commitA));
    }

    @Test
    public void testRevListAll() throws Exception {
        ObjectId commitA = commitOneFile();
        assertThat(gitClient.revListAll(), contains(commitA));
        /* Also test RevListCommand implementation */
        List<ObjectId> resultA = new ArrayList<>();
        gitClient.revList_().to(resultA).all().execute();
        assertThat(resultA, contains(commitA));

        ObjectId commitB = commitOneFile();
        assertThat(gitClient.revListAll(), contains(commitB, commitA));
        /* Also test RevListCommand implementation */
        List<ObjectId> resultB = new ArrayList<>();
        gitClient.revList_().to(resultB).all().execute();
        assertThat(resultB, contains(commitB, commitA));
    }

    @Test
    public void testRevList() throws Exception {
        ObjectId commitA = commitOneFile();
        assertThat(gitClient.revList("master"), contains(commitA));
        /* Also test RevListCommand implementation */
        List<ObjectId> resultA = new ArrayList<>();
        gitClient.revList_().to(resultA).reference("master").execute();
        assertThat(resultA, contains(commitA));

        ObjectId commitB = commitOneFile();
        assertThat(gitClient.revList("master"), contains(commitB, commitA));
        /* Also test RevListCommand implementation */
        List<ObjectId> resultB = new ArrayList<>();
        gitClient.revList_().to(resultB).reference("master").execute();
        assertThat(resultB, contains(commitB, commitA));
    }

    // @Test
    public void testSubGit() {
        System.out.println("subGit");
        String subdir = "";
        GitClient instance = gitClient;
        GitClient expResult = null;
        GitClient result = instance.subGit(subdir);
        assertEquals(expResult, result);
        fail("The test case is a prototype.");
    }

    @Test
    public void testHasGitModulesEmptyRepo() throws Exception {
        assertFalse(gitClient.hasGitModules());
    }

    private String fetchUpstream(String firstBranch, String... branches) throws Exception {
        String remote = "upstream";
        gitClient.addRemoteUrl(remote, upstreamRepoURL);
        String firstRef = remote + "/" + firstBranch;
        String firstRefSpec = "+refs/heads/" + firstBranch + ":refs/remotes/" + firstRef;
        if (branches.length == 0) {
            fetch(gitClient, remote, firstRefSpec);
        } else {
            String[] refSpecStrings = new String[branches.length];
            int index = 0;
            for (String branch : branches) {
                String ref = remote + "/" + branch;
                refSpecStrings[index++] = "+refs/heads/" + branch + ":refs/remotes/" + ref;
            }
            fetch(gitClient, remote, firstRefSpec, refSpecStrings);
        }
        return remote;
    }

    private String checkoutAndAssertHasGitModules(String branch, boolean gitModulesExpected) throws Exception {
        assertFalse(gitClient.hasGitModules());
        String remote = fetchUpstream(branch);
        gitClient.checkoutBranch(branch, remote + "/" + branch);
        assertThat(gitClient.hasGitModules(), is(gitModulesExpected)); // After checkout
        return remote;
    }

    private void assertModulesDir(boolean modulesDirExpected) {
        File modulesDir = new File(repoFolder.getRoot(), "modules");
        assertEquals(modulesDir.isDirectory(), modulesDirExpected);
    }

    @Test
    public void testHasGitModulesFalse() throws Exception {
        assertModulesDir(false);
        checkoutAndAssertHasGitModules("master", false);
        assertModulesDir(false); // repo has no modules dir and no submodules
    }

    @Test
    public void testHasGitModulesFalseNotSubmodule() throws Exception {
        assertModulesDir(false);
        checkoutAndAssertHasGitModules("tests/notSubmodules", false);
        assertModulesDir(true);  // repo has a modules dir but no submodules
    }

    @Test
    public void testHasGitModulesTrue() throws Exception {
        String branchName = "tests/getSubmodules";
        if (!gitImplName.equals("git")) {
            branchName = branchName + "-jgit";
        }
        assertModulesDir(false);
        checkoutAndAssertHasGitModules(branchName, true);
        assertModulesDir(true); // repo has a modules dir and submodules
    }

    private void assertSubmoduleStatus(GitClient testGitClient, boolean initialized, String... expectedModules) throws Exception {
        Map<String, Boolean> submodulesFound = new HashMap<>();
        for (String submoduleName : expectedModules) {
            submodulesFound.put("modules/" + submoduleName, Boolean.FALSE);
        }
        boolean emptyStatus = true;
        CliGitCommand gitCmd = new CliGitCommand(testGitClient);
        for (String statusLine : gitCmd.run("submodule", "status")) {
            emptyStatus = false;
            if (!initialized) {
                assertThat(statusLine, startsWith("-"));
            } else {
                assertThat(statusLine + lastUpdateSubmodulePath, startsWith(" "));
            }
            assertTrue("Bad submodule status: '" + statusLine + "'", statusLine.matches("[-U+ ][0-9a-f]{40} .*"));
            String submoduleName = statusLine.substring(42).split(" ")[0];
            if (submodulesFound.containsKey(submoduleName)) {
                submodulesFound.put(submoduleName, Boolean.TRUE);
            } else {
                fail("Found unexpected submodule '" + submoduleName + "'");
            }
        }
        for (String submoduleName : submodulesFound.keySet()) {
            assertTrue("Submodule " + submoduleName + " not found", submodulesFound.get(submoduleName));
        }
        assertFalse("git submodule status reported no output", emptyStatus);
    }

    private void assertSubmoduleStatus(boolean initialized) throws Exception {
        assertSubmoduleStatus(gitClient, initialized);
    }

    @Issue("37495") // submodule update fails if path and name differ
    @Test
    public void testSubmoduleUpdateRecursiveRenameModule() throws Exception {
        assumeThat(gitImplName, is("git")); // JGit implementation doesn't handle renamed submodules
        assumeTrue(CLI_GIT_SUPPORTS_SUBMODULE_RENAME);
        String branch = "tests/getSubmodules";
        String remote = fetchUpstream(branch);
        gitClient.checkout().branch(branch).ref(remote + "/" + branch).execute();
        assertSubmoduleStatus(gitClient, false, "firewall", "ntp", "sshkeys");
        /* Perform the update, then rename the module */
        gitClient.submoduleUpdate(true);
        assertSubmoduleStatus(gitClient, true, "firewall", "ntp", "sshkeys");
        CliGitCommand gitCmd = new CliGitCommand(gitClient);
        gitCmd.run("mv", "modules/ntp", "modules/ntp-moved");
        gitCmd.assertOutputContains("^$"); // Empty string
        gitCmd.run("commit", "-a", "-m", "Moved modules/ntp to modules/ntp-moved");
        gitCmd.assertOutputContains(".*modules/ntp.*modules/ntp-moved.*");
        assertSubmoduleStatus(gitClient, true, "firewall", "ntp-moved", "sshkeys");
    }

    @Issue("37495") // submodule update fails if path and name differ
    @Test
    public void testSubmoduleRenameModuleUpdateRecursive() throws Exception {
        assumeThat(gitImplName, is("git")); // JGit implementation doesn't handle renamed submodules
        assumeTrue(CLI_GIT_SUPPORTS_SUBMODULE_RENAME);
        String branch = "tests/getSubmodules";
        String remote = fetchUpstream(branch);
        gitClient.checkout().branch(branch).ref(remote + "/" + branch).execute();
        assertSubmoduleStatus(gitClient, false, "firewall", "ntp", "sshkeys");
        /* Rename the module, then perform the update */
        CliGitCommand gitCmd = new CliGitCommand(gitClient);
        gitCmd.run("mv", "modules/ntp", "modules/ntp-moved");
        gitCmd.assertOutputContains("^$"); // Empty string
        gitCmd.run("commit", "-a", "-m", "Moved modules/ntp to modules/ntp-moved");
        gitCmd.assertOutputContains(".*modules/ntp.*modules/ntp-moved.*");
        gitClient.submoduleUpdate(true);
        assertSubmoduleStatus(gitClient, true, "firewall", "ntp-moved", "sshkeys");
    }

    @Test
    public void testModifiedTrackedFilesReset() throws Exception {
        ObjectId commitA = commitOneFile("First commit");

        /* Modify every plain file in the root of the repository */
        String randomUUID = UUID.randomUUID().toString();
        String randomString = "Added after initial file checkin " + randomUUID + "\n";
        File lastModifiedFile = null;
        for (File file : repoFolder.getRoot().listFiles()) {
            if (file.isFile()) {
                try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8"), true)) {
                    writer.print(randomString);
                }
                lastModifiedFile = file;
            }
        }
        assertTrue("No files modified " + repoFolder.getRoot(), lastModifiedFile != null);

        /* Checkout a new branch - verify no files retain modification */
        gitClient.checkout().branch("master-" + randomUUID).ref(commitA.getName()).execute();

        lastModifiedFile = null;
        for (File file : repoFolder.getRoot().listFiles()) {
            if (file.isFile()) {
                List<String> lines = Files.readAllLines(file.toPath(), Charset.forName("UTF-8"));
                for (String line : lines) {
                    if (line.contains(randomString)) {
                        lastModifiedFile = file;
                    }
                }
            }
        }
        assertNull("Checkout did not revert change in " + lastModifiedFile, lastModifiedFile);
    }

    private void assertSubmoduleDirectories(GitClient gitClient, boolean expectLicense, String... expectedDirs) {
        File repoRoot = gitClient.getRepository().getWorkTree();
        for (String expectedDir : expectedDirs) {
            File dir = new File(repoRoot, "modules/" + expectedDir);
            assertTrue("Missing " + expectedDir + " dir (path:" + lastUpdateSubmodulePath + ")", dir.isDirectory());
            File license = new File(dir, "LICENSE");
            assertEquals("Checking " + expectedDir + " LICENSE (path:" + lastUpdateSubmodulePath + ")", expectLicense, license.isFile());
        }
    }

    private void assertSubmoduleContents(GitClient client, String... directories) {
        File repoRoot = client.getRepository().getWorkTree();
        for (String directory : directories) {
            File licenseDir = new File(repoRoot, "modules/" + directory);
            File licenseFile = new File(licenseDir, "LICENSE");
            assertTrue("Missing file " + licenseFile + " (path:" + lastUpdateSubmodulePath + ")", licenseFile.isFile());
        }
        List<String> expectedDirList = Arrays.asList(directories);
        List<String> dirList = new ArrayList<>();
        File modulesDir = new File(repoRoot, "modules");
        for (File dir : modulesDir.listFiles()) {
            if (dir.isDirectory()) {
                dirList.add(dir.getName());
            }
        }
        assertThat(dirList, containsInAnyOrder(expectedDirList.toArray(new String[expectedDirList.size()])));
        assertThat(expectedDirList, containsInAnyOrder(dirList.toArray(new String[dirList.size()])));
    }

    private void assertSubmoduleContents(String... directories) {
        assertSubmoduleContents(gitClient, directories);
    }

    private int lastUpdateSubmodulePath = -1;

    private void updateSubmoduleJGit(String remote, String branch) throws Exception {
        // Choose a random submodule update command
        // These submodule update variants are equivalent for JGit
        // JGitAPIImpl does not implement either reference or remote tracking
        lastUpdateSubmodulePath = random.nextInt(5);
        switch (lastUpdateSubmodulePath) {
            default:
            case 0:
                gitClient.submoduleUpdate().execute();
                break;
            case 1:
                gitClient.submoduleUpdate(true);
                break;
            case 2:
                gitClient.submoduleUpdate(false);
                break;
            case 3:
                gitClient.submoduleUpdate(false, false);
                break;
            case 4:
                gitClient.submoduleUpdate(true, false);
                break;
        }
    }

    private void updateSubmodule(String remote, String branch, Boolean remoteTracking) throws Exception {
        if (!gitImplName.equals("git")) {
            updateSubmoduleJGit(remote, branch);
            return;
        }
        if (remoteTracking == null) {
            // Allow caller to force remoteTracking value
            remoteTracking = true;
        }
        // Choose a random submodule update command
        // These submodule update variants are equivalent
        lastUpdateSubmodulePath = random.nextInt(9);
        switch (lastUpdateSubmodulePath) {
            default:
            case 0:
                gitClient.submoduleUpdate().remoteTracking(remoteTracking).execute();
                break;
            case 1:
                gitClient.submoduleUpdate(true);
                break;
            case 2:
                gitClient.submoduleUpdate(false);
                break;
            case 3:
                gitClient.submoduleUpdate(true, remote + "/" + branch);
                break;
            case 4:
                gitClient.submoduleUpdate(false, remote + "/" + branch);
                break;
            case 5:
                gitClient.submoduleUpdate(false, false);
                break;
            case 6:
                gitClient.submoduleUpdate(true, false);
                break;
            case 7:
                // testSubModulesUsedFromOtherBranches fails if remoteTracking == true
                gitClient.submoduleUpdate(false, remoteTracking);
                break;
            case 8:
                // testSubModulesUsedFromOtherBranches fails if remoteTracking == true
                gitClient.submoduleUpdate(true, remoteTracking);
                break;
        }
    }

    // @Issue("8053")  // outdated submodules not removed by checkout
    @Issue("37419") // Git plugin checking out non-existent submodule from different branch
    @Test
    public void testOutdatedSubmodulesNotRemoved() throws Exception {
        assumeTrue(CLI_GIT_SUPPORTS_SUBMODULE_DEINIT);
        String branch = "tests/getSubmodules";
        String[] expectedDirsWithRename = {"firewall", "ntp", "sshkeys"};
        String[] expectedDirsWithoutRename = {"firewall", "ntp"};
        String[] expectedDirs = expectedDirsWithRename;
        if (!gitImplName.equals("git")) {
            branch = branch + "-jgit";
            expectedDirs = expectedDirsWithoutRename;
        }
        String remote = fetchUpstream(branch);
        if (random.nextBoolean()) {
            gitClient.checkoutBranch(branch, remote + "/" + branch);
        } else {
            gitClient.checkout().branch(branch).ref(remote + "/" + branch).deleteBranchIfExist(true).execute();
        }

        gitClient.submoduleInit();
        if (gitImplName.equals("git")) {
            /* JGit doesn't create the empty directories - CliGit does */
            assertSubmoduleDirectories(gitClient, false, expectedDirs);
        }

        // Test fails if updateSubmodule called with remoteTracking == true
        // and the remoteTracking argument is used in the updateSubmodule call
        updateSubmodule(remote, branch, false);
        assertSubmoduleDirectories(gitClient, true, expectedDirs);
        assertSubmoduleContents(expectedDirs);

        /* Clone, checkout and submodule update a repository copy before submodule deletion */
        File cloneDir = dirAllocator.allocate();
        GitClient cloneGitClient = Git.with(TaskListener.NULL, new EnvVars()).in(cloneDir).using(gitImplName).getClient();
        cloneGitClient.init();
        cloneGitClient.clone_().url(repoFolder.getRoot().getAbsolutePath()).execute();
        cloneGitClient.checkoutBranch(branch, "origin/" + branch);
        if (gitImplName.equals("git")) {
            /* JGit doesn't create the empty directories - CliGit does */
            assertSubmoduleDirectories(cloneGitClient, false, expectedDirs);
        }
        cloneGitClient.submoduleInit();
        cloneGitClient.submoduleUpdate().recursive(false).execute();
        assertSubmoduleDirectories(cloneGitClient, true, expectedDirs);
        assertSubmoduleContents(cloneGitClient, expectedDirs);

        /* Remove firewall submodule */
        CliGitCommand gitCmd = new CliGitCommand(gitClient);
        File firewallDir = new File(repoFolder.getRoot(), "modules/firewall");
        FileUtils.forceDelete(firewallDir);
        assertFalse("firewallDir not deleted " + firewallDir, firewallDir.isDirectory());
        gitCmd.run("submodule", "deinit", "modules/firewall");
        gitCmd.assertOutputContains(".*unregistered.*modules/firewall.*");
        gitCmd.run("add", "."); // gitClient.add() doesn't work in this JGit case
        gitCmd.assertOutputContains("^$");
        gitCmd.run("rm", "modules/firewall");
        gitCmd.assertOutputContains(".*modules/firewall.*");
        gitCmd.run("commit", "-m", "Remove firewall submodule");
        gitCmd.assertOutputContains(".*Remove firewall submodule.*");
        File submoduleDir = new File(repoFolder.getRoot(), ".git/modules/firewall");
        if (submoduleDir.exists()) {
            FileUtils.forceDelete(submoduleDir);
        }

        /* Assert that ntp and sshkeys unharmed */
        if (gitImplName.equals("git")) {
            assertSubmoduleContents("ntp", "sshkeys");
        } else {
            assertSubmoduleContents("ntp");
        }

        /* Update the clone with the commit that deletes the submodule */
        String refSpec = "+refs/heads/" + branch + ":refs/remotes/origin/" + branch;
        fetch(cloneGitClient, "origin", refSpec);
        cloneGitClient.checkoutBranch(branch, "origin/" + branch);
        if (gitImplName.equals("git")) {
            assertSubmoduleContents(cloneGitClient, "firewall", "ntp", "sshkeys");
        } else {
            assertSubmoduleContents(cloneGitClient, "firewall", "ntp");
        }

        /* BUG: JENKINS-8053 Cloned modules/firewall not deleted by checkoutBranch in clone */
        File cloneFirewallDir = new File(cloneDir, "modules/firewall");
        assertTrue("cloneFirewallDir missing at " + cloneFirewallDir, cloneFirewallDir.isDirectory());

        /* "Feature": clean does not remove modules/firewall (because it contains a git repo)
         * See JENKINS-26660
         */
        gitClient.clean();
        assertTrue("cloneFirewallDir unexpectedly cleaned at " + cloneFirewallDir, cloneFirewallDir.isDirectory());

        /* Fixed JENKINS-37419 - submodules only from current branch */
        if (gitImplName.equals("git")) {
            assertSubmoduleStatus(cloneGitClient, true, "ntp", "sshkeys");
        } else {
            assertSubmoduleStatus(cloneGitClient, true, "ntp");
        }

        /**
         * With extra -f argument, git clean removes submodules
         */
        CliGitCommand cloneRepoCmd = new CliGitCommand(cloneGitClient);
        cloneRepoCmd.run("clean", "-xffd");
        assertFalse("cloneFirewallDir not deleted " + cloneFirewallDir, cloneFirewallDir.isDirectory());
    }

    private void assertBranches(GitClient client, String... expectedBranchNames) throws GitException, InterruptedException {
        List<String> branchNames = new ArrayList<>(); // Arrays.asList(expectedBranchNames);
        for (Branch branch : client.getBranches()) {
            if (branch.getName().startsWith("remotes/")) {
                continue; // Ignore remote branches
            }
            branchNames.add(branch.getName());
        }
        assertThat(branchNames, containsInAnyOrder(expectedBranchNames));
    }

    @Issue("37419") // Submodules from other branches are used in checkout
    @Test
    public void testSubmodulesUsedFromOtherBranches() throws Exception {
        /* Submodules not fully supported with JGit */
        assumeThat(gitImplName, is("git")); // JGit implementation doesn't handle renamed submodules
        String oldBranchName = "tests/getSubmodules";
        String upstream = fetchUpstream(oldBranchName);
        if (random.nextBoolean()) {
            gitClient.checkoutBranch(oldBranchName, upstream + "/" + oldBranchName);
        } else {
            gitClient.checkout().branch(oldBranchName).ref(upstream + "/" + oldBranchName).deleteBranchIfExist(true).execute();
        }
        assertBranches(gitClient, oldBranchName);
        assertSubmoduleDirectories(gitClient, false, "firewall", "ntp", "sshkeys"); // No submodule init or update yet

        /* Create tests/addSubmodules branch with one more module */
        String newBranchName = "tests/addSubmodules";
        String newDirName = "git-client-plugin-" + (10 + random.nextInt(90));
        gitClient.branch(newBranchName);
        gitClient.addSubmodule(srcRepoAbsolutePath, "modules/" + newDirName);
        gitClient.add("modules/" + newDirName);
        gitClient.commit("Added git-client-plugin module at modules/" + newDirName);

        // Test fails if updateSubmodule called with remoteTracking == true
        // and the remoteTracking argument is used in the updateSubmodule call
        gitClient.submoduleInit();
        updateSubmodule(upstream, newBranchName, false);
        assertSubmoduleContents("firewall", "ntp", "sshkeys", newDirName);
        assertSubmoduleStatus(gitClient, true, "firewall", "ntp", "sshkeys", newDirName);

        /* Checkout tests/getSubmodules and its submodules */
        if (random.nextBoolean()) {
            gitClient.checkoutBranch(oldBranchName, upstream + "/" + oldBranchName);
        } else {
            gitClient.checkout().branch(oldBranchName).ref(upstream + "/" + oldBranchName).deleteBranchIfExist(true).execute();
        }

        /* Assertion is wrong! newDirName should not have contents.
         * Or rather, I think the code is wrong, newDirName shoud not have contents,
         * since the branch being checked out does not include newDirName submodule.
         * How many installations depend on that unexpected behavior?
         */
        assertSubmoduleContents("firewall", "ntp", "sshkeys", newDirName);

        /* Assert newDirName not in submodule status
         * Shouldn't a checkoutBranch reset the submodule status?
         * If checkoutBranch did reset submodule status, this would be wrong
         */
        assertSubmoduleStatus(gitClient, true, "firewall", "ntp", "sshkeys");

        gitClient.submoduleInit();

        /* submoduleInit seems to make no change in this case */
        assertSubmoduleStatus(gitClient, true, "firewall", "ntp", "sshkeys");
        // assertSubmoduleDirectories(gitClient, true, "firewall", "ntp", "sshkeys");

        /* These assertions show why a submodule aware clean is needed */
        updateSubmodule(upstream, oldBranchName, false);
        assertSubmoduleContents("firewall", "ntp", "sshkeys", newDirName); // newDirName dir will be there
        assertSubmoduleStatus(gitClient, true, "firewall", "ntp", "sshkeys"); // newDirName module won't be there
    }

    @Test
    public void testGetSubmodules() throws Exception {
        assumeThat(gitImplName, is("git")); // JGit implementation doesn't handle renamed submodules
        String branchName = "tests/getSubmodules";
        String upstream = checkoutAndAssertHasGitModules(branchName, true);
        List<IndexEntry> submodules = gitClient.getSubmodules(branchName);
        IndexEntry[] expectedSubmodules = {
            new IndexEntry("160000", "commit", "978c8b223b33e203a5c766ecf79704a5ea9b35c8", "modules/firewall"),
            new IndexEntry("160000", "commit", "b62fabbc2bb37908c44ded233e0f4bf479e45609", "modules/ntp"),
            new IndexEntry("160000", "commit", "689c45ed57f0829735f9a2b16760c14236fe21d9", "modules/sshkeys")
        };
        assertThat(submodules, hasItems(expectedSubmodules));
    }

    private void assertStatusUntrackedContent(GitClient client, boolean expectUntrackedContent) throws Exception {
        CliGitCommand gitStatus = new CliGitCommand(client);
        boolean foundUntrackedContent = false;
        StringBuilder output = new StringBuilder();
        for (String line : gitStatus.run("status")) {
            if (line.contains("untracked content")) {
                foundUntrackedContent = true;
            }
            output.append(line);
        }
        assertEquals("Untracked content: " + output.toString(), expectUntrackedContent, foundUntrackedContent);
    }

    @Test
    public void testSubmoduleClean() throws Exception {
        assumeThat(gitImplName, is("git")); // JGit implementation doesn't handle renamed submodules
        String branchName = "tests/getSubmodules";
        String upstream = checkoutAndAssertHasGitModules(branchName, true);
        gitClient.submoduleInit();
        assertSubmoduleDirectories(gitClient, false, "firewall", "ntp", "sshkeys");
        // Test may fail if updateSubmodule called with remoteTracking == true
        // and the remoteTracking argument is used in the updateSubmodule call
        updateSubmodule(upstream, branchName, null);
        assertSubmoduleDirectories(gitClient, true, "firewall", "ntp", "sshkeys");
        assertSubmoduleContents("firewall", "ntp", "sshkeys");

        final File firewallDir = new File(repoFolder.getRoot(), "modules/firewall");
        final File firewallFile = File.createTempFile("untracked-", ".txt", firewallDir);
        final File ntpDir = new File(repoFolder.getRoot(), "modules/ntp");
        final File ntpFile = File.createTempFile("untracked-", ".txt", ntpDir);
        final File sshkeysDir = new File(repoFolder.getRoot(), "modules/sshkeys");
        final File sshkeysFile = File.createTempFile("untracked-", ".txt", sshkeysDir);

        assertStatusUntrackedContent(gitClient, true);

        /* GitClient clean() not expected to modify submodules */
        gitClient.clean();
        assertStatusUntrackedContent(gitClient, true);

        /* GitClient submoduleClean expected to modify submodules */
        boolean recursive = random.nextBoolean();
        gitClient.submoduleClean(recursive);
        assertStatusUntrackedContent(gitClient, false);
    }

    // @Issue("14083") // build can't recover from broken submodule path
    // @Issue("15399") // changing submodule URL does not update repository
    // @Issue("27625") // local changes inside submodules not reset on checkout (see testModifiedTrackedFilesReset)
    // @Issue("39350") // local changes inside submodules not reset on checkout (see testModifiedTrackedFilesReset)
    // @Issue("22510") // clean after checkout fails to clean revision
    // @Issue("23727") // submodule update did not fail build on timeout
    // @Issue("28748") // submodule update --init fails on large submodule
    // @Issue("22084") // submodule update failure did not send configured e-mail
    // @Issue("31532") // pipeline snippet generator garbles certain submodule options
    // @Issue("31586") // NPE when using submodules extension
    // @Issue("39253") // notifyCommit trigger should detect changes to a submodule
    // @Issue("21521") // submodule defaults in git plugin 2.x different than git plugin 1.x
    // @Issue("31244") // submodule misconfiguration not reported clearly
    // Update submodules to latest commit
    // Recursive submodule update
    // Recursive submodule update to latest commit
    // @Test
    public void testSetupSubmoduleUrls() throws Exception {
        System.out.println("setupSubmoduleUrls");
        Revision rev = null;
        TaskListener listener = null;
        GitClient instance = gitClient;
        instance.setupSubmoduleUrls(rev, listener);
        fail("The test case is a prototype.");
    }
}
