package org.jenkinsci.plugins.gitclient;

import com.google.common.collect.Lists;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitObject;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import java.io.File;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.io.FileUtils;

import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.lib.Constants;

import static org.hamcrest.io.FileMatchers.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.jvnet.hudson.test.Issue;

/**
 * Git Client tests, intended as an eventual replacement for CliGitAPIImplTest,
 * JGitAPIImplTest, and JGitApacheAPIImplTest.
 *
 * @author Mark Waite
 */
@RunWith(Parameterized.class)
public class GitClientTest {

    /* Git implementation name, either "git", "jgit", or "jgitapache". */
    private final String gitImplName;

    /* Git client plugin repository directory. */
    private static File srcRepoDir = null;

    /* GitClient for plugin development repository. */
    private GitClient srcGitClient;

    /* commit known to exist in upstream. */
    private final ObjectId upstreamCommit = ObjectId.fromString("f75720d5de9d79ab4be2633a21de23b3ccbf8ce3");
    private final String upstreamCommitAuthor = "Teubel György";
    private final String upstreamCommitEmail = "<tgyurci@freemail.hu>";
    private final ObjectId upstreamCommitPredecessor = ObjectId.fromString("867e5f148377fd5a6d96e5aafbdaac132a117a5a");

    /* URL of upstream (GitHub) repository. */
    private final String upstreamRepoURL = "https://github.com/jenkinsci/git-client-plugin";

    /* URL of GitHub test repository with large file support. */
    private final String lfsTestRepoURL = "https://github.com/MarkEWaite/jenkins-pipeline-utils";

    /* Instance of object under test */
    private GitClient gitClient = null;

    /* Instance of object of another test class*/
    private CliGitAPIImplTest cliGitAPIImplTest = new CliGitAPIImplTest();

    /* Capabilities of command line git in current environment */
    private final boolean CLI_GIT_HAS_GIT_LFS;
    private final boolean CLI_GIT_HAS_GIT_LFS_CONFIGURED;
    private final boolean CLI_GIT_REPORTS_DETACHED_SHA1;
    private final boolean CLI_GIT_SUPPORTS_SUBMODULE_DEINIT;
    private final boolean CLI_GIT_SUPPORTS_SUBMODULE_RENAME;
    private final boolean CLI_GIT_SUPPORTS_SYMREF;
    private final boolean CLI_GIT_SUPPORTS_REV_LIST_NO_WALK;
    private final boolean LFS_SUPPORTS_SPARSE_CHECKOUT;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private File repoRoot = null;

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
        CLI_GIT_SUPPORTS_SUBMODULE_DEINIT = cliGitClient.isAtLeastVersion(1, 9, 0, 0);
        CLI_GIT_SUPPORTS_SUBMODULE_RENAME = cliGitClient.isAtLeastVersion(1, 9, 0, 0);
        CLI_GIT_SUPPORTS_SYMREF = cliGitClient.isAtLeastVersion(2, 8, 0, 0);
        CLI_GIT_SUPPORTS_REV_LIST_NO_WALK = cliGitClient.isAtLeastVersion(1, 5, 3, 0);

        boolean gitLFSExists;
        try {
            // If git-lfs is installed then the version string should look like this:
            // git-lfs/1.5.6 (GitHub; linux amd64; go 1.7.4)
            gitLFSExists = cliGitClient.launchCommand("lfs", "version").startsWith("git-lfs");
        } catch (GitException exception) {
            // This is expected when git-lfs is not installed.
            gitLFSExists = false;
        }
        CLI_GIT_HAS_GIT_LFS = gitLFSExists;

        boolean gitLFSConfigured;
        try {
            // If git-lfs is configured then the smudge filter will not be empty
            gitLFSConfigured = cliGitClient.launchCommand("config", "filter.lfs.smudge").contains("git-lfs");
        } catch (GitException exception) {
            // This is expected when git-lfs is not installed.
            gitLFSConfigured = false;
        }
        CLI_GIT_HAS_GIT_LFS_CONFIGURED = gitLFSConfigured;
        LFS_SUPPORTS_SPARSE_CHECKOUT = CLI_GIT_HAS_GIT_LFS_CONFIGURED && cliGitClient.isAtLeastVersion(2, 0, 0, 0);
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

    /**
     * Mirror the git-client-plugin repo so that the tests have a reasonable and
     * repeatable set of commits, tags, and branches.
     */
    private static File mirrorParent = null;

    @BeforeClass
    public static void mirrorUpstreamRepositoryLocally() throws Exception {
        File currentDir = new File(".");
        CliGitAPIImpl currentDirCliGit = (CliGitAPIImpl) Git.with(TaskListener.NULL, new EnvVars()).in(currentDir).using("git").getClient();
        boolean currentDirIsShallow = currentDirCliGit.isShallowRepository();

        mirrorParent = Files.createTempDirectory("mirror").toFile();
        /* Clone mirror into mirrorParent/git-client-plugin.git as a bare repo */
        CliGitCommand mirrorParentGitCmd = new CliGitCommand(Git.with(TaskListener.NULL, new EnvVars()).in(mirrorParent).using("git").getClient());
        if (currentDirIsShallow) {
            mirrorParentGitCmd.run("clone",
                    // "--reference", currentDir.getAbsolutePath(), // --reference of shallow repo fails
                    "--mirror", "https://github.com/jenkinsci/git-client-plugin");
        } else {
            mirrorParentGitCmd.run("clone",
                    "--reference", currentDir.getAbsolutePath(),
                    "--mirror", "https://github.com/jenkinsci/git-client-plugin");
        }
        File mirrorDir = new File(mirrorParent, "git-client-plugin.git");
        assertTrue("Git client mirror repo not created at " + mirrorDir.getAbsolutePath(), mirrorDir.exists());
        GitClient mirrorClient = Git.with(TaskListener.NULL, new EnvVars()).in(mirrorDir).using("git").getClient();
        assertThat(mirrorClient.getTagNames("git-client-1.6.3"), contains("git-client-1.6.3"));

        /* Clone from bare mirrorParent/git-client-plugin.git to working mirrorParent/git-client-plugin */
        mirrorParentGitCmd.run("clone", mirrorDir.getAbsolutePath());
        srcRepoDir = new File(mirrorParent, "git-client-plugin");
    }

    @AfterClass
    public static void removeMirrorAndSrcRepos() throws Exception {
        FileUtils.deleteDirectory(mirrorParent);
    }

    @Before
    public void setGitClient() throws IOException, InterruptedException {
        repoRoot = tempFolder.newFolder();
        gitClient = Git.with(TaskListener.NULL, new EnvVars()).in(repoRoot).using(gitImplName).getClient();
        File gitDir = gitClient.withRepository((repo, channel) -> repo.getDirectory());
        assertFalse("Already found " + gitDir, gitDir.isDirectory());
        gitClient.init_().workspace(repoRoot.getAbsolutePath()).execute();
        assertTrue("Missing " + gitDir, gitDir.isDirectory());
        gitClient.setRemoteUrl("origin", srcRepoDir.getAbsolutePath());
        CliGitCommand gitCmd = new CliGitCommand(gitClient);
        gitCmd.run("config", "user.name", "Vojtěch GitClientTest Zweibrücken-Šafařík");
        gitCmd.run("config", "user.email", "email.from.git.client@example.com");
    }

    private static final String COMMITTED_ONE_TEXT_FILE = "Committed one text file";

    private ObjectId commitOneFile() throws Exception {
        return commitOneFile(COMMITTED_ONE_TEXT_FILE);
    }

    private ObjectId commitOneFile(final String commitMessage) throws Exception {
        final String content = String.format("A random UUID: %s\n", UUID.randomUUID().toString());
        return commitFile("One-File.txt", content, commitMessage);
    }

    private ObjectId commitFile(final String path, final String content, final String commitMessage) throws Exception {
        createFile(path, content);
        gitClient.add(path);
        gitClient.commit(commitMessage);
        List<ObjectId> headList = gitClient.revList(Constants.HEAD);
        assertThat(headList.size(), is(greaterThan(0)));
        return headList.get(0);
    }

    private void createFile(String path, String content) throws Exception {
        File aFile = new File(repoRoot, path);
        File parentDir = aFile.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
        try (PrintWriter writer = new PrintWriter(aFile, "UTF-8")) {
            writer.printf(content);
        } catch (FileNotFoundException | UnsupportedEncodingException ex) {
            throw new GitException(ex);
        }
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
    @Issue("JENKINS-29977")
    /**
     * Changelog was formatted on word boundary prior to
     * 72 characters with git client plugin 2.0+ when using CLI git.
     * Was not truncated by git client plugin using JGit (And Apache version).
     * Rely on caller to truncate first line if desired.
     * Matching change will be included in git plugin 4.0.0
     * to retain existing truncation behavior.
     */
    public void testChangelogVeryLong() throws Exception {

        final String gitMessage =
                        "Uno Dos Tres Cuatro Cinco Lorem ipsum dolor sit amet, consectetur adipiscing elit. Ut " +
                        "posuere tellus eu efficitur tristique. In iaculis neque in dolor vulputate" +
                        "sollicitudin eget a quam. Donec finibus sapien quis lectus euismod facilisis. Integer" +
                        "massa purus, scelerisque id iaculis ut, blandit vitae velit. Pellentesque lobortis" +
                        "aliquet felis, vel laoreet ipsum tincidunt at. Mauris tellus est, cursus vitae ex" +
                        "eget, venenatis auctor eros. Sed sagittis porta odio. Donec ut interdum massa. Aliquam" +
                        "sagittis, mi sit amet sollicitudin elementum, velit quam eleifend nisl, in rhoncus" +
                        "felis nibh eu nibh. Class aptent taciti sociosqu ad litora torquent per conubia " +
                        "nostra, per inceptos himenaeos." +
                        "\nseis\n" +
                        "\nasfasfasfasf\n"
                ;
        final String content = String.format("A random UUID: %s\n", UUID.randomUUID().toString());
        ObjectId message = commitFile("One-File.txt", content, gitMessage);

        ChangelogCommand changelog = gitClient.changelog();
        StringWriter changelogStringWriter = new StringWriter();
        changelog.includes(message).to(changelogStringWriter).execute();
        assertThat(changelogStringWriter.toString(), containsString("Ut posuere"));
        assertThat(changelogStringWriter.toString(), containsString("conubia nostra"));
    }

    @Test
    @Issue("JENKINS-39832") // Diagnostics of ChangelogCommand were insufficient
    public void testChangelogExceptionMessage() throws Exception {
        final ObjectId commitA = commitOneFile();
        ChangelogCommand changelog = gitClient.changelog();
        StringWriter changelogStringWriter = new StringWriter();
        changelog.includes(commitA).to(changelogStringWriter).execute();
        assertThat(changelogStringWriter.toString(), containsString(COMMITTED_ONE_TEXT_FILE));

        final String missingSHA1 = "ca11ab1edeededacecadebadebeaddeadcedeade";

        // Confirm includes exception is as expected
        changelog = gitClient.changelog();
        changelogStringWriter = new StringWriter();
        try {
            changelog.includes(missingSHA1).to(changelogStringWriter).execute();
            fail("Did not throw expected exception");
        } catch (GitException ge) {
            // Check that directory and SHA1 are included in exception message
            assertThat(ge.getMessage(), containsString(missingSHA1));
            assertThat(ge.getMessage(), containsString(" in " + repoRoot.getAbsolutePath()));
        }

        // Confirm excludes exception is as expected
        changelog = gitClient.changelog();
        changelogStringWriter = new StringWriter();
        try {
            changelog.excludes(missingSHA1).to(changelogStringWriter).execute();
            fail("Did not throw expected exception");
        } catch (GitException ge) {
            // Check that directory and SHA1 are included in exception message
            assertThat(ge.getMessage(), containsString(missingSHA1));
            assertThat(ge.getMessage(), containsString(" in " + repoRoot.getAbsolutePath()));
        }

        final ObjectId missingObject = ObjectId.fromString(missingSHA1);

        // Confirm includes exception is as expected
        changelog = gitClient.changelog();
        changelogStringWriter = new StringWriter();
        try {
            changelog.includes(missingObject).to(changelogStringWriter).execute();
            fail("Did not throw expected exception");
        } catch (GitException ge) {
            // Check that directory and SHA1 are included in exception message
            assertThat(ge.getMessage(), containsString(missingSHA1));
            assertThat(ge.getMessage(), containsString(" in " + repoRoot.getAbsolutePath()));
        }

        // Confirm excludes exception is as expected
        changelog = gitClient.changelog();
        changelogStringWriter = new StringWriter();
        try {
            changelog.excludes(missingObject).to(changelogStringWriter).execute();
            fail("Did not throw expected exception");
        } catch (GitException ge) {
            // Check that directory and SHA1 are included in exception message
            assertThat(ge.getMessage(), containsString(missingSHA1));
            assertThat(ge.getMessage(), containsString(" in " + repoRoot.getAbsolutePath()));
        }
    }

    @Test
    public void testNullChangelogDestinationIncludes() throws Exception {
        final ObjectId commitA = commitOneFile();
        ChangelogCommand changelog = gitClient.changelog();
        changelog.includes(commitA);
        assertThrows(IllegalStateException.class,
                     () -> {
                         changelog.execute();
                     });
    }

    @Test
    public void testNullChangelogDestinationExcludes() throws Exception {
        final ObjectId commitA = commitOneFile();
        ChangelogCommand changelog = gitClient.changelog();
        changelog.excludes(commitA);
        assertThrows(IllegalStateException.class,
                     () -> {
                         changelog.execute();
                     });
    }

    @Test
    @Issue("JENKINS-43198")
    public void testCleanSubdirGitignore() throws Exception {
        final String filename1 =  "this_is/not_ok/more/subdirs/file.txt";
        final String filename2 =  "this_is_also/not_ok_either/more/subdirs/file.txt";
        commitFile(".gitignore", "/this_is/not_ok\n/this_is_also/not_ok_either\n", "set up gitignore");
        createFile(filename1, "hi there");
        createFile(filename2, "hi there");
        assertFileInWorkingDir(gitClient, filename1);
        assertFileInWorkingDir(gitClient, filename2);
        gitClient.clean();
        assertDirNotInWorkingDir(gitClient, "this_is");
        assertDirNotInWorkingDir(gitClient, "this_is_also");
    }

    @Test
    @Issue("JENKINS-37794")
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
        assertAuthor(upstreamCommitPredecessor, upstreamCommit, upstreamCommitAuthor, upstreamCommitEmail);
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
        assertThat(gitClient.getWorkTree(), is(new FilePath(repoRoot)));
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
        File expectedRepo = new File(repoRoot, ".git");
        assertEquals(expectedRepo, gitClient.getRepository().getDirectory());
    }

    @Test
    public void testInit() throws Exception {
        File gitDir = gitClient.withRepository((repo, channel) -> repo.getDirectory());
        gitClient.init();
        assertTrue("init did not create " + gitDir, gitDir.isDirectory());
    }

    @Test
    public void testInit_Bare() throws Exception {
        File repoRootTemp = tempFolder.newFolder();
        GitClient gitClientTemp = Git.with(TaskListener.NULL, new EnvVars()).in(repoRootTemp).using(gitImplName).getClient();
        File tempDir = gitClientTemp.withRepository((repo, channel) -> repo.getDirectory());
        assertFalse("Missing", tempDir.isDirectory());
        gitClientTemp.init_().workspace(repoRootTemp.getPath()).bare(true).execute();
        // Bare git_init contains no working tree file
        tempDir = gitClientTemp.withRepository((repo, channel) -> repo.getWorkTree());
        assertFalse(".refs not found", tempDir.isFile());
    }

    @Test
    public void testInitFailureWindows() throws Exception {
        if (!isWindows()) {
            return;
        }
        String badDirName = "CON:";
        File badDir = new File(badDirName);
        GitClient badGitClient = Git.with(TaskListener.NULL, new EnvVars()).in(badDir).using(gitImplName).getClient();
        Class expectedExceptionClass = gitImplName.equals("git") ? GitException.class : InvalidPathException.class;
        assertThrows(expectedExceptionClass,
                     () -> {
                         badGitClient.init_().bare(random.nextBoolean()).workspace(badDirName).execute();
                     });
    }

    @Test
    public void testInitFailureNotWindowsNotSuperUser() throws Exception {
        if (isWindows() || (new File("/").canWrite())) { // Windows or running as root
            return;
        }
        String badDirName = "/this/directory/is/not/accessible";
        File badDir = new File(badDirName);
        GitClient badGitClient = Git.with(TaskListener.NULL, new EnvVars()).in(badDir).using(gitImplName).getClient();
        Class expectedExceptionClass = gitImplName.equals("git") ? GitException.class : JGitInternalException.class;
        assertThrows(expectedExceptionClass,
                     () -> {
                         badGitClient.init_().bare(random.nextBoolean()).workspace(badDirName).execute();
                     });
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
        assertTrue("Test repo '" + repoRoot.getAbsolutePath() + "' not initialized", gitClient.hasGitRepo());
        StringBuilder fileList = new StringBuilder();
        for (File file : srcRepoDir.listFiles()) {
            fileList.append(file.getAbsolutePath());
            fileList.append(" ");
        }
        assertTrue("Source repo '" + srcRepoDir.getAbsolutePath() + "' not initialized, contains " + fileList.toString(), srcGitClient.hasGitRepo());

        File emptyDir = tempFolder.newFolder();
        assertTrue(emptyDir.exists());
        GitClient emptyClient = Git.with(TaskListener.NULL, new EnvVars()).in(emptyDir).using(gitImplName).getClient();
        assertFalse("Empty repo '" + emptyDir.getAbsolutePath() + "' initialized", emptyClient.hasGitRepo());
    }

    @Test
    public void testHasGitRepoFalse() throws Exception {
        /* Use system temp directory so that no parent directory has a git repository */
        Path tempDir = Files.createTempDirectory("git-client-hasGitRepo");
        GitClient noRepoClient = Git.with(TaskListener.NULL, new EnvVars()).in(tempDir.toFile()).using(gitImplName).getClient();
        assertFalse("New empty temp dir has a git repo(1)", noRepoClient.hasGitRepo());
        assertFalse("New empty temp dir has a git repo(2)", noRepoClient.hasGitRepo(false));
        assertFalse("New empty temp dir has a git repo(3)", noRepoClient.hasGitRepo(true));
        tempDir.toFile().delete(); // Remove the temporary directory
    }

    @Issue("JENKINS-38699")
    @Test
    public void testHasGitRepoNestedDir() throws Exception {
        File childDir = tempFolder.newFolder("parentDir", "childDir");
        File parentDir = childDir.getParentFile();

        GitClient parentDirClient = Git.with(TaskListener.NULL, new EnvVars()).in(parentDir).using(gitImplName).getClient();
        assertFalse("Unexpected has git repo before init(1)", parentDirClient.hasGitRepo());
        assertFalse("Unexpected has git repo before init(2)", parentDirClient.hasGitRepo(true));
        assertFalse("Unexpected has git repo before init(3)", parentDirClient.hasGitRepo(false));

        parentDirClient.init();
        assertTrue("Missing git repo after init(1)", parentDirClient.hasGitRepo());
        assertTrue("Missing git repo after init(2)", parentDirClient.hasGitRepo(true));
        assertTrue("Missing git repo after init(3)", parentDirClient.hasGitRepo(false));

        GitClient childDirClient = Git.with(TaskListener.NULL, new EnvVars()).in(childDir).using(gitImplName).getClient();
        assertFalse("Unexpected has child git repo before child init(1)", childDirClient.hasGitRepo());
        assertFalse("Unexpected has child git repo before child init(2)", childDirClient.hasGitRepo(true));
        assertFalse("Unexpected has child git repo before child init(3)", childDirClient.hasGitRepo(false));

        File childGitDir = new File(childDir, ".git");
        boolean dirCreated = childGitDir.mkdir();
        assertTrue("Failed to create empty .git dir in childDir", dirCreated);
        if (gitImplName.equals("git")) {
            // JENKINS-38699 - if an empty .git directory exists, CLI git searches upwards to perform operations
            assertTrue("Missing parent git repo before child init(1)", childDirClient.hasGitRepo());
        } else {
            // JENKINS-38699 - if an empty .git directory exists, JGit does NOT search upwards to perform operations
            assertFalse("Unexpected parent git repo detected by JGit before child init(1)", childDirClient.hasGitRepo());
        }
        assertTrue("Missing parent git repo before child init(2)", childDirClient.hasGitRepo(true));
        assertFalse("Unexpected has child repo before child init(3)", childDirClient.hasGitRepo(false));

        childDirClient.init();
        assertTrue("Missing git repo after child init(1)", childDirClient.hasGitRepo());
        assertTrue("Missing git repo after child init(2)", childDirClient.hasGitRepo(true));
        assertTrue("Missing git repo after child init(3)", childDirClient.hasGitRepo(false));
    }

    @Test
    public void testIsCommitInRepo() throws Exception {
        assertTrue(srcGitClient.isCommitInRepo(upstreamCommit));
        assertFalse(gitClient.isCommitInRepo(upstreamCommit));
        assertFalse(gitClient.isCommitInRepo(null)); // NPE safety check
        // this MAY fail if commit has this exact sha1, but please admit this would be unlucky
        assertFalse(gitClient.isCommitInRepo(ObjectId.fromString("1111111111111111111111111111111111111111")));
    }

    private void assertExceptionMessageContains(GitException ge, String expectedSubstring) {
        String actual = ge.getMessage().toLowerCase();
        assertTrue("Expected '" + expectedSubstring + "' exception message, but was: " + actual, actual.contains(expectedSubstring));
    }

    private IGitAPI IGitAPIForTrueBareRepositoryTests() throws IOException, InterruptedException {
        // provides iGitAPI with bare repository initialization
        File repoRootTemp = tempFolder.newFolder();
        GitClient gitClientTemp = Git.with(TaskListener.NULL,new EnvVars()).in(repoRootTemp).using(gitImplName).getClient();
        gitClientTemp.init_().workspace(repoRootTemp.getAbsolutePath()).bare(true).execute();
        return (IGitAPI) gitClientTemp;
    }

    @Test
    @Deprecated
    public void testIsBareRepositoryBareDot() throws InterruptedException, IOException {
        IGitAPI gitAPI = IGitAPIForTrueBareRepositoryTests();
        assertTrue(". is not a bare repository", gitAPI.isBareRepository("."));
    }

    @Test
    @Deprecated
    public void testIsBareRepositoryWorkingDotGit() throws IOException, InterruptedException {
        gitClient.init_().workspace(repoRoot.getAbsolutePath()).bare(true).execute();
        IGitAPI gitAPI = (IGitAPI) gitClient;
        FilePath gitClientFilePath = gitClient.getWorkTree();
        gitClientFilePath.createTextTempFile("aPre", ".txt", "file contents");
        gitClient.add(".");
        gitClient.commit("Not-a-bare-repository-dot-git");
        assertFalse(".git is a bare repository", gitAPI.isBareRepository(".git"));
    }

    @Test
    @Deprecated
    public void testIsBareRepositoryBareDotGit() throws IOException, InterruptedException {
        IGitAPI gitAPI = IGitAPIForTrueBareRepositoryTests();
        /* Bare repository does not have a .git directory.  This is
         * another no-such-location test but is included here for
         * consistency.
         */
        try {
            /* JGit knows that w.igit() has a workspace, and asks the workspace
             * if it is bare.  That seems more correct than relying on testing
             * a specific file that the repository is bare.  JGit behaves better
             * than CliGit in this case.
             */
            assertTrue("non-existent .git is in a bare repository", gitAPI.isBareRepository(".git"));
            /* JGit will not throw an exception - it knows the repo is bare */
            /* CliGit throws an exception so should not reach the next assertion */
            assertFalse("CliGitAPIImpl did not throw expected exception", gitAPI instanceof CliGitAPIImpl);
        } catch (GitException ge) {
            /* Only enters this path for CliGit */
            assertExceptionMessageContains(ge, "not a git repository");
        }
    }

    @Test
    @Deprecated
    public void testIsBareRepositoryWorkingNoSuchLocation() throws IOException, InterruptedException {
        gitClient.init_().workspace(repoRoot.getAbsolutePath()).bare(true).execute();
        IGitAPI gitAPI = (IGitAPI) gitClient;
        FilePath gitClientFilePath = gitClient.getWorkTree();
        gitClientFilePath.createTextTempFile("aPre", ".txt", "file contents");
        gitClient.add(".");
        gitClient.commit("Not-a-bare-repository-working-no-such-location");
        try {
            assertFalse("non-existent location is in a bare repository", gitAPI.isBareRepository("no-such-location"));
            /* JGit will not throw an exception - it knows the repo is not bare */
            /* CliGit throws an exception so should not reach the next assertion */
            assertFalse("CliGitAPIImpl did not throw expected exception", gitAPI instanceof CliGitAPIImpl);
        } catch (GitException ge) {
            /* Only enters this path for CliGit */
            assertExceptionMessageContains(ge, "not a git repository");
        }
    }

    @Test
    @Deprecated
    public void testIsBareRepositoryBareNoSuchLocation() throws IOException, InterruptedException {
        IGitAPI gitAPI = IGitAPIForTrueBareRepositoryTests();
        try {
            assertTrue("non-existent location is in a bare repository", gitAPI.isBareRepository("no-such-location"));
            /* JGit will not throw an exception - it knows the repo is not bare */
            /* CliGit throws an exception so should not reach the next assertion */
            assertFalse("CliGitAPIImpl did not throw expected exception", gitAPI instanceof CliGitAPIImpl);
        } catch (GitException ge) {
            /* Only enters this path for CliGit */
            assertExceptionMessageContains(ge, "not a git repository");
        }
    }

    @Deprecated
    @Test
    public void testIsBareRepositoryBareEmptyString() throws IOException, InterruptedException {
        IGitAPI gitAPI = IGitAPIForTrueBareRepositoryTests();
        assertTrue("empty string is not a bare repository", gitAPI.isBareRepository(""));
    }

    @Deprecated
    @Test
    public void testIsBareRepositoryWorkingEmptyString() throws IOException, InterruptedException {
        gitClient.init_().workspace(repoRoot.getAbsolutePath()).bare(true).execute();
        IGitAPI gitAPI = (IGitAPI) gitClient;
        FilePath gitClientFilePath = gitClient.getWorkTree();
        gitClientFilePath.createTextTempFile("aPre", ".txt", "file contents");
        gitClient.add(".");
        gitClient.commit("Not-a-bare-repository-empty-string");
        assertFalse("empty string is a bare repository", gitAPI.isBareRepository(""));
    }

    @Deprecated
    @Test
    public void testIsBareRepositoryBareNoArg() throws IOException, InterruptedException {
        IGitAPI gitAPI = IGitAPIForTrueBareRepositoryTests();
        assertTrue("no arg is not a bare repository", gitAPI.isBareRepository());
    }

    @Deprecated
    @Test
    public void testIsBareRepositoryWorkingNoArg() throws IOException, InterruptedException {
        gitClient.init_().workspace(repoRoot.getAbsolutePath()).bare(true).execute();
        IGitAPI gitAPI = (IGitAPI) gitClient;
        FilePath gitClientFilePath = gitClient.getWorkTree();
        gitClientFilePath.createTextTempFile("aPre", ".txt", "file contents");
        gitClient.add(".");
        gitClient.commit("Not-a-bare-repository-no-arg");
        assertFalse("no arg is a bare repository", gitAPI.isBareRepository());
    }

    @Test
    public void testBareRepoInit() throws IOException, InterruptedException {
        IGitAPI gitAPI = IGitAPIForTrueBareRepositoryTests();
        File tempDir = gitAPI.withRepository((repo, channel) -> repo.getWorkTree());
        File gitFile = new File(tempDir,".git");
        File gitObjFile = new File(tempDir,".git/objects");
        File objFile = new File(tempDir,"objects");
        assertFalse(".git exists unexpectedly", gitFile.exists());
        assertFalse(".git/objects exists unexpectedly", gitObjFile.exists());
        assertTrue("objects is not a directory", objFile.isDirectory());
    }

    /* The most critical use cases of isBareRepository respond the
     * same for both the JGit implementation and the CliGit
     * implementation.  Those are asserted first in this section of
     * assertions.
     */

    @Deprecated
    @Test
    public void testIsBareRepositoryWorkingRepoPathDotGit() throws IOException, InterruptedException {
        gitClient.init_().workspace(repoRoot.getAbsolutePath()).bare(true).execute();
        IGitAPI gitAPI = (IGitAPI) gitClient;
        FilePath gitClientFilePath = gitClient.getWorkTree();
        gitClientFilePath.createTextTempFile("aPre", ".txt", "file contents");
        gitClient.add(".");
        gitClient.commit("Not-a-bare-repository-false-repoPath-dot-git");
        assertFalse("repoPath/.git is a bare repository", gitAPI.isBareRepository(repoRoot.getPath() + File.separator + ".git"));
    }

    @Deprecated
    @Test
    public void testIsBareRepositoryWorkingNull() throws IOException, InterruptedException {
        gitClient.init_().workspace(repoRoot.getAbsolutePath()).bare(true).execute();
        IGitAPI gitAPI = (IGitAPI) gitClient;
        FilePath gitClientFilePath = gitClient.getWorkTree();
        gitClientFilePath.createTextTempFile("aPre", ".txt", "file contents");
        gitClient.add(".");
        gitClient.commit("Not-a-bare-repository-working-null");
        try {
            assertFalse("null is a bare repository", gitAPI.isBareRepository(null));
            fail("Did not throw expected exception");
        } catch (GitException ge) {
            assertExceptionMessageContains(ge, "not a git repository");
        }
    }

    @Deprecated
    @Test
    public void testIsBareRepositoryBareNull() throws IOException, InterruptedException {
        IGitAPI gitAPI = IGitAPIForTrueBareRepositoryTests();
        try {
            assertTrue("null is not a bare repository", gitAPI.isBareRepository(null));
            fail("Did not throw expected exception");
        } catch (GitException ge) {
            assertExceptionMessageContains(ge, "not a git repository");
        }
    }

    @Deprecated
    @Test
    public void test_isBareRepository_bare_repoPath() throws IOException, InterruptedException {
        IGitAPI gitAPI = IGitAPIForTrueBareRepositoryTests();
        File tempRepoDir = gitAPI.withRepository((repo, channel) -> repo.getWorkTree());
        File dotFile = new File(tempRepoDir,".");
        assertTrue("repoPath is not a bare repository", gitAPI.isBareRepository(tempRepoDir.getPath()));
        assertTrue("abs(.) is not a bare repository",gitAPI.isBareRepository(dotFile.getAbsolutePath()));
    }

    @Test
    public void testGetRemoteUrl() throws Exception {
        assertEquals(srcRepoDir.getAbsolutePath(), gitClient.getRemoteUrl("origin"));
    }

    @Test
    @Deprecated
    public void testGetRemoteUrl_two_args() throws Exception {
        IGitAPI iGitAPI = (IGitAPI) gitClient;
        String originUrl = gitClient.getRemoteUrl("origin");
        assertThat("Null URL arg", iGitAPI.getRemoteUrl("origin", null), is(originUrl));
        assertThat("Empty string URL arg", iGitAPI.getRemoteUrl("origin", ""), is(originUrl));
    }

    @Test
    public void testSetRemoteUrl() throws Exception {
        assertEquals(srcRepoDir.getAbsolutePath(), gitClient.getRemoteUrl("origin"));
        gitClient.setRemoteUrl("origin", upstreamRepoURL);
        assertEquals(upstreamRepoURL, gitClient.getRemoteUrl("origin"));
    }

    @Test
    public void testAddRemoteUrl() throws Exception {
        gitClient.addRemoteUrl("upstream", upstreamRepoURL);
        assertEquals(srcRepoDir.getAbsolutePath(), gitClient.getRemoteUrl("origin"));
        assertEquals(upstreamRepoURL, gitClient.getRemoteUrl("upstream"));
    }

    @Test
    public void testAutocreateFailsOnMultipleMatchingOrigins() throws Exception {
        File repoRootTemp = tempFolder.newFolder();
        GitClient gitClientTemp = Git.with(TaskListener.NULL, new EnvVars()).in(repoRootTemp).using(gitImplName).getClient();
        gitClientTemp.init();
        CliGitCommand gitCmd = new CliGitCommand(gitClientTemp);
        gitCmd.run("config", "user.name", "Vojtěch GitClientTest Zweibrücken-Šafařík");
        gitCmd.run("config", "user.email", "email.from.git.client@example.com");
        FilePath gitClientFilePath = gitClientTemp.getWorkTree();
        FilePath gitClientTempFile = gitClientFilePath.createTextTempFile("aPre", ".txt", "file contents");
        gitClientTemp.add(".");
        gitClientTemp.commit("Added " + gitClientTempFile.toURI().toString());
        gitClient.clone_().url("file://" + repoRootTemp.getPath()).execute();
        final URIish remote = new URIish(Constants.DEFAULT_REMOTE_NAME);

        try ( // add second remote
              FileRepository repo = new FileRepository(new File(repoRoot, ".git"))) {
            StoredConfig config = repo.getConfig();
            config.setString("remote", "upstream", "url", "file://" + repoRootTemp.getPath());
            config.setString("remote", "upstream", "fetch", "+refs/heads/*:refs/remotes/upstream/*");
            config.save();
        }

        // fill both remote branches
        List<RefSpec> refspecs = Collections.singletonList(new RefSpec(
                "refs/heads/*:refs/remotes/origin/*"));
        gitClient.fetch_().from(remote, refspecs).execute();
        refspecs = Collections.singletonList(new RefSpec(
                "refs/heads/*:refs/remotes/upstream/*"));
        gitClient.fetch_().from(remote, refspecs).execute();

        // checkout will fail
        try {
            gitClient.checkout().ref(Constants.MASTER).execute();
        } catch (GitException e) {
            // expected
            Set<String> refNames = gitClient.getRefNames("refs/heads/");
            assertFalse("RefNames will not contain master", refNames.contains("refs/heads/master"));
        }

    }

    /**
     * Test case for auto local branch creation behviour.
     * This is essentially a stripped down version of {@link GitAPITestCase#test_branchContainingRemote()}
     * @throws Exception on exceptions occur
     */
    @Test
    public void testCheckoutRemoteAutocreatesLocal() throws Exception {
        File repoRootTemp = tempFolder.newFolder();
        GitClient gitClientTemp = Git.with(TaskListener.NULL, new EnvVars()).in(repoRootTemp).using(gitImplName).getClient();
        gitClientTemp.init();
        CliGitCommand gitCmd = new CliGitCommand(gitClientTemp);
        gitCmd.run("config", "user.name", "Vojtěch GitClientTest temp Zweibrücken-Šafařík");
        gitCmd.run("config", "user.email", "email.by.client@example.com");
        FilePath gitClientFilePath = gitClientTemp.getWorkTree();
        FilePath gitClientTempFile = gitClientFilePath.createTextTempFile("aPre", ".txt", "file contents");
        gitClientTemp.add(".");
        gitClientTemp.commit("Added " + gitClientTempFile.toURI().toString());
        gitClient.clone_().url("file://" + repoRootTemp.getPath()).execute();
        final URIish remote = new URIish(Constants.DEFAULT_REMOTE_NAME);
        final List<RefSpec> refspecs = Collections.singletonList(new RefSpec(
                "refs/heads/*:refs/remotes/origin/*"));
        gitClient.fetch_().from(remote, refspecs).execute();
        gitClient.checkout().ref(Constants.MASTER).execute();

        Set<String> refNames = gitClient.getRefNames("refs/heads/");
        assertThat(refNames, contains("refs/heads/master"));
    }

    private void assertFileInWorkingDir(GitClient client, String fileName) {
        File fileInRepo = new File(repoRoot, fileName);
        assertThat(fileInRepo, is(anExistingFile()));
    }

    private void assertFileNotInWorkingDir(GitClient client, String fileName) {
        File fileInRepo = new File(repoRoot, fileName);
        assertThat(fileInRepo, is(not(anExistingFile())));
    }

    private void assertFileContent(String fileName, String expectedContent) throws IOException {
        File file = new File(repoRoot, fileName);
        String actualContent = FileUtils.readFileToString(file, StandardCharsets.UTF_8).trim();
        assertEquals("Incorrect file content in " + fileName, expectedContent, actualContent);
    }

    private void assertDirInWorkingDir(GitClient client, String dirName) {
        File dirInRepo = new File(repoRoot, dirName);
        assertThat(dirInRepo, is(anExistingDirectory()));
    }

    private void assertDirNotInWorkingDir(GitClient client, String dirName) {
        File dirInRepo = new File(repoRoot, dirName);
        assertThat(dirInRepo, is(not(anExistingDirectory())));
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
        File gitDir = new File(repoRoot, ".git");
        File[] gitDirListing = repoRoot.listFiles();
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

    private void deleteTag(GitClient client, String tagName) throws Exception {
        CliGitCommand gitCmd = new CliGitCommand(client);
        gitCmd.run("tag", "-d", tagName);
    }

    private void fetch(GitClient client, String remote, boolean fetchTags, String firstRefSpec, String... optionalRefSpecs) throws Exception {
        List<RefSpec> refSpecs = new ArrayList<>();
        RefSpec refSpec = new RefSpec(firstRefSpec);
        refSpecs.add(refSpec);
        for (String refSpecString : optionalRefSpecs) {
            refSpecs.add(new RefSpec(refSpecString));
        }
        switch (random.nextInt(2)) {
            default:
            case 0:
                if (remote.equals("origin")) {
                    /* If remote == "origin", randomly use default remote */
                    remote = random.nextBoolean() ? remote : null;
                }
                client.fetch(remote, refSpecs.toArray(new RefSpec[0]));
                break;
            case 1:
                URIish repoURL = new URIish(client.withRepository((repo, channel) -> repo.getConfig()).getString("remote", remote, "url"));
                boolean pruneBranches = random.nextBoolean();
                if (pruneBranches) {
                    client.fetch_().from(repoURL, refSpecs).tags(fetchTags).prune(true).execute();
                } else {
                    client.fetch_().from(repoURL, refSpecs).tags(fetchTags).execute();
                }
                break;
        }
    }

    private void fetch(GitClient client, String remote, String firstRefSpec, String... optionalRefSpecs) throws Exception {
        boolean fetchTags = random.nextBoolean();
        fetch(client, remote, fetchTags, firstRefSpec, optionalRefSpecs);
    }

    private void fetchWithTags(GitClient client, String remote) throws Exception {
        final boolean FETCH_ALL_TAGS = true;
        fetch(client, remote, FETCH_ALL_TAGS, "+refs/heads/*:refs/remotes/origin/*");
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
        File src = new File(repoRoot, "src");
        assertFalse(src.isDirectory());
        String branch = "master";
        String remote = fetchUpstream(branch);
        gitClient.checkoutBranch(branch, remote + "/" + branch);
        assertTrue(src.isDirectory());
    }

    @Test
    public void testBranchExistsException() throws Exception {
        File src = new File(repoRoot, "src");
        assertFalse(src.isDirectory());
        String branch = "master";
        String remote = fetchUpstream(branch);
        gitClient.checkoutBranch(branch, remote + "/" + branch);
        /* Check that exception is thrown trying to create an existing branch */
        assertThrows(GitException.class,
                     () -> {
                         gitClient.branch("master");
                     });
    }

    @Test
    public void testEmptyCommitException() throws Exception {
        File src = new File(repoRoot, "src");
        assertFalse(src.isDirectory());
        String branch = "master";
        String remote = fetchUpstream(branch);
        gitClient.checkoutBranch(branch, remote + "/" + branch);
        if (gitImplName.equals("git")) {
            /* Check that exception is thrown trying to commit nothing */
            assertThrows(GitException.class,
                         () -> {
                             gitClient.commit("This commit contains no changes");
                         });
        } else {
            /* Check that JGit does not throw an exception trying to commit nothing */
            gitClient.commit("This commit contains no changes");
        }
    }

    @Test
    public void testDeleteNonExistingBranchException() throws Exception {
        File src = new File(repoRoot, "src");
        assertFalse(src.isDirectory());
        String branch = "master";
        String remote = fetchUpstream(branch);
        gitClient.checkoutBranch(branch, remote + "/" + branch);
        if (gitImplName.equals("git")) {
            /* Check that exception is thrown trying to delete non-existent branch */
            assertThrows(GitException.class,
                         () -> {
                             gitClient.deleteBranch("ThisBranchDoesNotExist");
                         });
        } else {
            /* Check that JGit does not throw an exception trying to delete non-existent branch */
            gitClient.deleteBranch("ThisBranchDoesNotExist");
        }
    }

    @Issue("JENKINS-35687") // Git LFS support
    @Test
    public void testCheckoutWithCliGitLFS() throws Exception {
        if (!gitImplName.equals("git") || !CLI_GIT_HAS_GIT_LFS) {
            return;
        }
        String branch = "tests/largeFileSupport";
        String remote = fetchLFSTestRepo(branch);
        gitClient.checkout().branch(branch).ref(remote + "/" + branch).lfsRemote(remote).execute();

        assertFileContent("uuid.txt", "5e7733d8acc94636850cb466aec524e4");
    }

    @Issue("JENKINS-43427") // Git LFS sparse checkout support
    @Test
    public void testSparseCheckoutWithCliGitLFS() throws Exception {
        if (!gitImplName.equals("git") || !CLI_GIT_HAS_GIT_LFS) {
            return;
        }

        String branch = "tests/largeFileSupport";

        String file1 = "uuid.txt";
        String file2 = "uuid2.txt";
        String file3 = "uuid,3.txt";
        String file4 = "uuid 4.txt";

        String lfsObjectFile1 = ".git/lfs/objects/75/d1/75d122e4160dc91480257ff72403e77ef276e24d7416ed2be56d4e726482d86e";
        String lfsObjectFile2 = ".git/lfs/objects/7f/0b/7f0bbf4cdb2bdf3e862c5f00285d362db0b46465ed089775743ce1ebe6c912ce";
        String lfsObjectFile3 = ".git/lfs/objects/1e/35/1e359899b05181e6a118865d47ed027b9930bc870dffbd9608c609ec3b2a3d16";
        String lfsObjectFile4 = ".git/lfs/objects/44/a0/44a0d97ff32b5ba6a28ea8a35d1ebbfd8f9a8a2db316e87abd530b4b60153b5c";

        String expectedContent1 = "5e7733d8acc94636850cb466aec524e4";
        String expectedContent2 = "c49d89a61c3411e9a5555b2af3892239";
        String expectedContent3 = "6fd3199a1cb111e9bbaf537fc996ab9b";
        String expectedContent4 = "803219bc1cb111e9abbfb7cc7b029948";

        // it should support sparse checkout of LFS files
        {
            setGitClient();
            String remote = fetchLFSTestRepo(branch);
            assertEmptyWorkingDir(gitClient);

            List<String> sparsePaths = Lists.newArrayList("uuid.txt");
            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).sparseCheckoutPaths(sparsePaths).execute();

            assertFileContent(file1, expectedContent1);
            assertFileNotInWorkingDir(gitClient, file2);
            assertFileNotInWorkingDir(gitClient, file3);
            assertFileNotInWorkingDir(gitClient, file4);

            assertFileInWorkingDir(gitClient, lfsObjectFile1);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile2);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile3);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile4);
        }

        // it should support multiple sparse checkout paths
        {
            setGitClient();
            String remote = fetchLFSTestRepo(branch);
            assertEmptyWorkingDir(gitClient);

            List<String> sparsePaths = Lists.newArrayList("uuid.txt", "uuid2.txt");
            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).sparseCheckoutPaths(sparsePaths).execute();

            assertFileContent(file1, expectedContent1);
            assertFileContent(file2, expectedContent2);
            assertFileNotInWorkingDir(gitClient, file3);
            assertFileNotInWorkingDir(gitClient, file4);

            assertFileInWorkingDir(gitClient, lfsObjectFile1);
            assertFileInWorkingDir(gitClient, lfsObjectFile2);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile3);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile4);
        }

        // it should support commas (",") in sparse checkout paths
        // (due to https://github.com/git-lfs/git-lfs/issues/2264 no sparse checkout can be used here)
        {
            setGitClient();
            String remote = fetchLFSTestRepo(branch);
            assertEmptyWorkingDir(gitClient);

            List<String> sparsePaths = Lists.newArrayList("uuid,3.txt");
            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).sparseCheckoutPaths(sparsePaths).execute();

            assertFileContent(file1, expectedContent1); // file present due to workaround
            assertFileContent(file2, expectedContent2); // file present due to workaround
            assertFileContent(file3, expectedContent3);
            assertFileContent(file4, expectedContent4); // file present due to workaround

            assertFileInWorkingDir(gitClient, lfsObjectFile1); // file present due to workaround
            assertFileInWorkingDir(gitClient, lfsObjectFile2); // file present due to workaround
            assertFileInWorkingDir(gitClient, lfsObjectFile3);
            assertFileInWorkingDir(gitClient, lfsObjectFile4); // file present due to workaround
        }

        // it should support spaces (" ") in sparse checkout paths
        {
            setGitClient();
            String remote = fetchLFSTestRepo(branch);
            assertEmptyWorkingDir(gitClient);

            List<String> sparsePaths = Lists.newArrayList("uuid 4.txt");
            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).sparseCheckoutPaths(sparsePaths).execute();

            assertFileNotInWorkingDir(gitClient, file1);
            assertFileNotInWorkingDir(gitClient, file2);
            assertFileNotInWorkingDir(gitClient, file3);
            assertFileContent(file4, expectedContent4);

            assertFileNotInWorkingDir(gitClient, lfsObjectFile1);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile2);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile3);
            assertFileInWorkingDir(gitClient, lfsObjectFile4);
        }

        // it should support pattern matching with "?" in sparse checkout paths
        {
            setGitClient();
            String remote = fetchLFSTestRepo(branch);
            assertEmptyWorkingDir(gitClient);

            List<String> sparsePaths = Lists.newArrayList("uuid.t?t");
            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).sparseCheckoutPaths(sparsePaths).execute();

            assertFileContent(file1, expectedContent1);
            assertFileNotInWorkingDir(gitClient, file2);
            assertFileNotInWorkingDir(gitClient, file3);
            assertFileNotInWorkingDir(gitClient, file4);

            assertFileInWorkingDir(gitClient, lfsObjectFile1);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile2);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile3);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile4);
        }

        // it should support pattern matching with "*" in sparse checkout paths
        {
            setGitClient();
            String remote = fetchLFSTestRepo(branch);
            assertEmptyWorkingDir(gitClient);

            List<String> sparsePaths = Lists.newArrayList("uuid.*");
            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).sparseCheckoutPaths(sparsePaths).execute();

            assertFileContent(file1, expectedContent1);
            assertFileNotInWorkingDir(gitClient, file2);
            assertFileNotInWorkingDir(gitClient, file3);
            assertFileNotInWorkingDir(gitClient, file4);

            assertFileInWorkingDir(gitClient, lfsObjectFile1);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile2);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile3);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile4);
        }

        // it should support pattern matching with "**" in sparse checkout paths
        {
            setGitClient();
            String remote = fetchLFSTestRepo(branch);
            assertEmptyWorkingDir(gitClient);

            List<String> sparsePaths = Lists.newArrayList("/**/uuid.txt");
            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).sparseCheckoutPaths(sparsePaths).execute();

            assertFileContent(file1, expectedContent1);
            assertFileNotInWorkingDir(gitClient, file2);
            assertFileNotInWorkingDir(gitClient, file3);
            assertFileNotInWorkingDir(gitClient, file4);

            assertFileInWorkingDir(gitClient, lfsObjectFile1);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile2);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile3);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile4);
        }

        // it should support negate pattern (leading "!") in sparse checkout paths
        {
            setGitClient();
            String remote = fetchLFSTestRepo(branch);
            assertEmptyWorkingDir(gitClient);

            List<String> sparsePaths = Lists.newArrayList("/*", "!uuid.txt");
            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).sparseCheckoutPaths(sparsePaths).execute();

            assertFileNotInWorkingDir(gitClient, file1);
            assertFileContent(file2, expectedContent2);
            assertFileContent(file3, expectedContent3);
            assertFileContent(file4, expectedContent4);

            assertFileNotInWorkingDir(gitClient, lfsObjectFile1);
            assertFileInWorkingDir(gitClient, lfsObjectFile2);
            assertFileInWorkingDir(gitClient, lfsObjectFile3);
            assertFileInWorkingDir(gitClient, lfsObjectFile4);
        }

        // it should remove former checked out file when changing sparse checkout paths
        {
            setGitClient();
            String remote = fetchLFSTestRepo(branch);
            assertEmptyWorkingDir(gitClient);

            List<String> sparsePaths1 = Lists.newArrayList("uuid.txt");
            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).sparseCheckoutPaths(sparsePaths1).execute();

            assertFileContent(file1, expectedContent1);
            assertFileNotInWorkingDir(gitClient, file2);
            assertFileNotInWorkingDir(gitClient, file3);
            assertFileNotInWorkingDir(gitClient, file4);

            assertFileInWorkingDir(gitClient, lfsObjectFile1);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile2);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile3);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile4);

	    if (LFS_SUPPORTS_SPARSE_CHECKOUT) {
		List<String> sparsePaths2 = Lists.newArrayList("uuid2.txt");
		gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).sparseCheckoutPaths(sparsePaths2).execute();

		assertFileNotInWorkingDir(gitClient, file1);
		assertFileContent(file2, expectedContent2);
		assertFileNotInWorkingDir(gitClient, file3);
		assertFileNotInWorkingDir(gitClient, file4);

		assertFileInWorkingDir(gitClient, lfsObjectFile1);
		assertFileInWorkingDir(gitClient, lfsObjectFile2);
		assertFileNotInWorkingDir(gitClient, lfsObjectFile3);
		assertFileNotInWorkingDir(gitClient, lfsObjectFile4);
	    }
        }

        // it should support switching from non-sparse to sparse checkout
        {
            setGitClient();
            String remote = fetchLFSTestRepo(branch);
            assertEmptyWorkingDir(gitClient);

            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).execute();

            assertFileContent(file1, expectedContent1);
            assertFileContent(file2, expectedContent2);
            assertFileContent(file3, expectedContent3);
            assertFileContent(file4, expectedContent4);

            assertFileInWorkingDir(gitClient, lfsObjectFile1);
            assertFileInWorkingDir(gitClient, lfsObjectFile2);
            assertFileInWorkingDir(gitClient, lfsObjectFile3);
            assertFileInWorkingDir(gitClient, lfsObjectFile4);

            List<String> sparsePaths = Lists.newArrayList("uuid.txt");
            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).sparseCheckoutPaths(sparsePaths).execute();

            assertFileContent(file1, expectedContent1);
            assertFileNotInWorkingDir(gitClient, file2);
            assertFileNotInWorkingDir(gitClient, file3);
            assertFileNotInWorkingDir(gitClient, file4);

            assertFileInWorkingDir(gitClient, lfsObjectFile1);
            assertFileInWorkingDir(gitClient, lfsObjectFile2);
            assertFileInWorkingDir(gitClient, lfsObjectFile3);
            assertFileInWorkingDir(gitClient, lfsObjectFile4);
        }

        // it should support switching from sparse to non-sparse checkout
        {
            setGitClient();
            String remote = fetchLFSTestRepo(branch);
            assertEmptyWorkingDir(gitClient);

            List<String> sparsePaths = Lists.newArrayList("uuid.txt");
            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).sparseCheckoutPaths(sparsePaths).execute();

            assertFileContent(file1, expectedContent1);
            assertFileNotInWorkingDir(gitClient, file2);
            assertFileNotInWorkingDir(gitClient, file3);
            assertFileNotInWorkingDir(gitClient, file4);

            assertFileInWorkingDir(gitClient, lfsObjectFile1);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile2);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile3);
            assertFileNotInWorkingDir(gitClient, lfsObjectFile4);

            gitClient.checkout().ref(remote + "/" + branch).lfsRemote(remote).execute();

            assertFileContent(file1, expectedContent1);
            assertFileContent(file2, expectedContent2);
            assertFileContent(file3, expectedContent3);
            assertFileContent(file4, expectedContent4);

            assertFileInWorkingDir(gitClient, lfsObjectFile1);
            assertFileInWorkingDir(gitClient, lfsObjectFile2);
            assertFileInWorkingDir(gitClient, lfsObjectFile3);
            assertFileInWorkingDir(gitClient, lfsObjectFile4);
        }
    }

    @Issue("JENKINS-35687") // Git LFS support - JGit not supported
    @Test(expected = org.eclipse.jgit.api.errors.JGitInternalException.class)
    public void testCheckoutWithJGitLFS() throws Exception {
        if (!gitImplName.startsWith("jgit") || !CLI_GIT_HAS_GIT_LFS) {
            throw new org.eclipse.jgit.api.errors.JGitInternalException("Skipping testCheckoutWithJGitLFS for CLI git exception");
        }
        String branch = "tests/largeFileSupport";
        String remote = fetchLFSTestRepo(branch);
        gitClient.checkout().branch(branch).ref(remote + "/" + branch).lfsRemote(remote).execute();
    }

    // If LFS installed and not enabled, throw an exception
    @Issue("JENKINS-35687") // Git LFS support
    @Test(expected = GitException.class)
    public void testCLICheckoutWithoutLFSWhenLFSAvailable() throws Exception {
        if (!gitImplName.equals("git") || !CLI_GIT_HAS_GIT_LFS) {
            throw new GitException("Test requires CLI git with LFS enabled");
        }
        String branch = "tests/largeFileSupport";
        String remote = fetchLFSTestRepo(branch);
        gitClient.checkout().branch(branch).ref(remote + "/" + branch).execute();
    }

    // If LFS installed and not enabled, throw an exception if branch includes LFS reference
    @Issue("JENKINS-35687") // Git LFS support
    @Test(expected = org.eclipse.jgit.api.errors.JGitInternalException.class)
    public void testJGitCheckoutWithoutLFSWhenLFSAvailable() throws Exception {
        if (!gitImplName.startsWith("jgit") || !CLI_GIT_HAS_GIT_LFS) {
            throw new org.eclipse.jgit.api.errors.JGitInternalException("Test requires CLI git with LFS enabled");
        }
        String branch = "tests/largeFileSupport";
        String remote = fetchLFSTestRepo(branch);
        gitClient.checkout().branch(branch).ref(remote + "/" + branch).execute();
    }

    // If LFS not installed and not enabled, checkout content without download
    @Issue("JENKINS-35687") // Git LFS support
    @Test
    public void testCheckoutWithoutLFSWhenLFSNotAvailable() throws Exception {
        if (CLI_GIT_HAS_GIT_LFS || CLI_GIT_HAS_GIT_LFS_CONFIGURED) {
            return;
        }
        String branch = "tests/largeFileSupport";
        String remote = fetchLFSTestRepo(branch);
        gitClient.checkout().branch(branch).ref(remote + "/" + branch).execute();
        File uuidFile = new File(repoRoot, "uuid.txt");
        String fileContent = FileUtils.readFileToString(uuidFile, "utf-8").trim();
        String expectedContent = "version https://git-lfs.github.com/spec/v1\n"
                + "oid sha256:75d122e4160dc91480257ff72403e77ef276e24d7416ed2be56d4e726482d86e\n"
                + "size 33";
        assertEquals("Incorrect non-LFS file contents in " + uuidFile, expectedContent, fileContent);
    }

    /*
     * JGit versions prior to 4.9.0 required a work around that the
     * tags refspec had to be passed in addition to setting the
     * FETCH_TAGS tagOpt.  JGit 4.9.0 fixed that bug.  This test would
     * throw a DuplicateRef exception with JGit 4.9.0 prior to the
     * removal of the work around (from JGitAPIImpl).
     */
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
        if (!gitImplName.startsWith("jgit")) {
            throw new GitException("Skipping JGit test in CLI git specific test testDeleteRefException");
        }
        assertThat(gitClient.getRefNames(""), is(empty()));
        commitOneFile(); // Creates commit on master branch
        Set<String> refNames = gitClient.getRefNames("");
        assertThat(refNames, hasItems("refs/heads/master"));
        gitClient.deleteRef("refs/heads/master"); // Throws - JGit cannot delete current branch
    }

    @Test
    public void testGetHeadRev_String() throws Exception {
        String url = repoRoot.getAbsolutePath();

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
        String url = repoRoot.getAbsolutePath();

        ObjectId commitA = commitOneFile();
        assertThat(gitClient.getHeadRev(url, "master"), is(commitA));

        ObjectId commitB = commitOneFile();
        assertThat(gitClient.getHeadRev(url, "master"), is(commitB));
    }

    @Test(expected = GitException.class)
    public void testGetHeadRev_Exception() throws Exception {
        gitClient.getHeadRev("protocol://hostname:port/not-a-URL");
    }

    @Test(expected = GitException.class)
    public void testGetHeadRev_String_String_URI_Exception() throws Exception {
        gitClient.getHeadRev("protocol://hostname:port/not-a-URL", "master");
    }

    @Test
    public void testGetHeadRev_String_String_Empty_Result() throws Exception {
        String url = repoRoot.getAbsolutePath();
        ObjectId nonExistent = gitClient.getHeadRev(url, "this branch doesn't exist");
        assertNull(nonExistent);
    }

    @Test
    public void testRefExists() throws Exception {
        String getSubmodulesRef = "refs/remotes/origin/tests/getSubmodules";
        assertFalse(gitClient.refExists(getSubmodulesRef));
        assertTrue(srcGitClient.refExists(getSubmodulesRef));
    }

    // @Test
    public void testGetRemoteReferences() throws Exception {
        String url = repoRoot.getAbsolutePath();
        String pattern = null;
        boolean headsOnly = false; // Need variations here
        boolean tagsOnly = false; // Need variations here
        Map<String, ObjectId> result = gitClient.getRemoteReferences(url, pattern, headsOnly, tagsOnly);
        assertNull(result);
    }

    @Issue("JENKINS-30589")
    @Test
    public void testGetRemoteReferences_ReturnsEmptyMapIfNoTags() throws Exception {
        String url = repoRoot.getAbsolutePath();
        String pattern = "**";
        boolean headsOnly = false;
        boolean tagsOnly = true;
        Map<String, ObjectId> result = gitClient.getRemoteReferences(url, pattern, headsOnly, tagsOnly);
        assertThat(result, is(Collections.EMPTY_MAP));
    }

    @Test
    public void testGetRemoteReferencesNonExistingPattern() throws Exception {
        String url = repoRoot.getAbsolutePath();
        String pattern = "non-existent-name";
        boolean headsOnly = false;
        boolean tagsOnly = false;
        Map<String, ObjectId> result = gitClient.getRemoteReferences(url, pattern, headsOnly, tagsOnly);
        assertThat(result, is(Collections.EMPTY_MAP));
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
        gitClient.revList_().to(resultAll).all(true).execute();
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
        gitClient.revList_().to(resultA).all(true).execute();
        assertThat(resultA, contains(commitA));

        ObjectId commitB = commitOneFile();
        assertThat(gitClient.revListAll(), contains(commitB, commitA));
        /* Also test RevListCommand implementation */
        List<ObjectId> resultB = new ArrayList<>();
        gitClient.revList_().to(resultB).all(true).execute();
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

    @Test
    public void testRevListNoWalk() throws Exception {
        if (!CLI_GIT_SUPPORTS_REV_LIST_NO_WALK) {
            return;
        }
        ObjectId commitA = commitOneFile();
        List<ObjectId> resultA = new ArrayList<>();
        gitClient.revList_().to(resultA).reference(commitA.name()).nowalk(true).execute();
        assertThat(resultA, contains(commitA));
        assertEquals(resultA.size(), 1);

        /* Make sure it's correct when there's more than one commit in the history */
        ObjectId commitB = commitOneFile();
        List<ObjectId> resultB = new ArrayList<>();
        gitClient.revList_().to(resultB).reference(commitB.name()).nowalk(true).execute();
        assertThat(resultB, contains(commitB));
        assertEquals(resultB.size(), 1);
    }

    // @Test
    public void testSubGit() throws Exception {
        // Tested in assertSubmoduleContents
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

    private String fetchLFSTestRepo(String firstBranch) throws Exception {
        String remote = "lfs-test-origin";
        gitClient.addRemoteUrl(remote, lfsTestRepoURL);
        String firstRef = remote + "/" + firstBranch;
        String firstRefSpec = "+refs/heads/" + firstBranch + ":refs/remotes/" + firstRef;
        fetch(gitClient, remote, firstRefSpec);
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
        File modulesDir = new File(repoRoot, "modules");
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

    @Issue("JENKINS-37495") // submodule update fails if path and name differ
    @Test
    public void testSubmoduleUpdateRecursiveRenameModule() throws Exception {
        // JGit implementation doesn't handle renamed submodules
        if (!gitImplName.equals("git") || !CLI_GIT_SUPPORTS_SUBMODULE_RENAME) {
            return;
        }
        String branch = "tests/getSubmodules";
        String remote = fetchUpstream(branch);
        gitClient.checkout().branch(branch).ref(remote + "/" + branch).execute();
        assertSubmoduleStatus(gitClient, false, "firewall", "ntp", "sshkeys");
        /* Perform the update, then rename the module */
        gitClient.submoduleUpdate().recursive(true).execute();
        assertSubmoduleStatus(gitClient, true, "firewall", "ntp", "sshkeys");
        CliGitCommand gitCmd = new CliGitCommand(gitClient);
        gitCmd.run("mv", "modules/ntp", "modules/ntp-moved");
        gitCmd.assertOutputContains("^$"); // Empty string
        gitCmd.run("commit", "-a", "-m", "Moved modules/ntp to modules/ntp-moved");
        gitCmd.assertOutputContains(".*modules/ntp.*modules/ntp-moved.*");
        assertSubmoduleStatus(gitClient, true, "firewall", "ntp-moved", "sshkeys");
    }

    @Issue("JENKINS-37495") // submodule update fails if path and name differ
    @Test
    public void testSubmoduleRenameModuleUpdateRecursive() throws Exception {
        // JGit implementation doesn't handle renamed submodules
        if (!gitImplName.equals("git") || !CLI_GIT_SUPPORTS_SUBMODULE_RENAME) {
            return;
        }
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
        gitClient.submoduleUpdate().recursive(true).execute();
        assertSubmoduleStatus(gitClient, true, "firewall", "ntp-moved", "sshkeys");
    }

    @Test
    public void testModifiedTrackedFilesReset() throws Exception {
        ObjectId commitA = commitOneFile("First commit");

        /* Modify every plain file in the root of the repository */
        String randomUUID = UUID.randomUUID().toString();
        String randomString = "Added after initial file checkin " + randomUUID + "\n";
        File lastModifiedFile = null;
        for (File file : repoRoot.listFiles()) {
            if (file.isFile()) {
                try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(Files.newOutputStream(file.toPath()), "UTF-8"), true)) {
                    writer.print(randomString);
                }
                lastModifiedFile = file;
            }
        }
        assertNotNull("No files modified " + repoRoot, lastModifiedFile);

        /* Checkout a new branch - verify no files retain modification */
        gitClient.checkout().branch("master-" + randomUUID).ref(commitA.getName()).execute();

        lastModifiedFile = null;
        for (File file : repoRoot.listFiles()) {
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

    private void assertSubmoduleDirectories(GitClient gitClient, boolean expectLicense, String... expectedDirs) throws Exception {
        File myRepoRoot = gitClient.withRepository((repo, channel) -> repo.getWorkTree());
        for (String expectedDir : expectedDirs) {
            File dir = new File(myRepoRoot, "modules/" + expectedDir);
            assertTrue("Missing " + expectedDir + " dir (path:" + lastUpdateSubmodulePath + ")", dir.isDirectory());
            File license = new File(dir, "LICENSE");
            assertEquals("Checking " + expectedDir + " LICENSE (path:" + lastUpdateSubmodulePath + ")", expectLicense, license.isFile());
        }
    }

    private void assertSubmoduleContents(GitClient client, String... directories) throws Exception {
        File myRepoRoot = client.withRepository((repo, channel) -> repo.getWorkTree());
        for (String directory : directories) {
            File licenseDir = new File(myRepoRoot, "modules/" + directory);
            File licenseFile = new File(licenseDir, "LICENSE");
            assertTrue("Missing file " + licenseFile + " (path:" + lastUpdateSubmodulePath + ")", licenseFile.isFile());
            GitClient subGitClient = client.subGit("modules/" + directory);
            assertThat(subGitClient.hasGitModules(), is(false));
            assertThat(subGitClient.getWorkTree().getName(), is(directory));
        }
        List<String> expectedDirList = Arrays.asList(directories);
        List<String> dirList = new ArrayList<>();
        File modulesDir = new File(myRepoRoot, "modules");
        for (File dir : modulesDir.listFiles()) {
            if (dir.isDirectory()) {
                dirList.add(dir.getName());
            }
        }
        assertThat(dirList, containsInAnyOrder(expectedDirList.toArray()));
    }

    private void assertSubmoduleContents(String... directories) throws Exception {
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
                gitClient.submoduleUpdate().recursive(true).execute();
                break;
            case 2:
                gitClient.submoduleUpdate().recursive(false).execute();
                break;
            case 3:
                gitClient.submoduleUpdate().recursive(false).remoteTracking(false).execute();
                break;
            case 4:
                gitClient.submoduleUpdate().recursive(true).remoteTracking(false).execute();
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
                gitClient.submoduleUpdate().recursive(true).execute();
                break;
            case 2:
                gitClient.submoduleUpdate().recursive(false).execute();
                break;
            case 3:
                gitClient.submoduleUpdate().recursive(true).ref(remote + "/" + branch).execute();
                break;
            case 4:
                gitClient.submoduleUpdate().recursive(false).ref(remote + "/" + branch).execute();
                break;
            case 5:
                gitClient.submoduleUpdate().recursive(false).remoteTracking(false).execute();
                break;
            case 6:
                gitClient.submoduleUpdate().recursive(true).remoteTracking(false).execute();
                break;
            case 7:
                // testSubModulesUsedFromOtherBranches fails if remoteTracking == true
                gitClient.submoduleUpdate().recursive(false).remoteTracking(remoteTracking).execute();
                break;
            case 8:
                // testSubModulesUsedFromOtherBranches fails if remoteTracking == true
                gitClient.submoduleUpdate().recursive(true).remoteTracking(remoteTracking).execute();
                break;
        }
    }

    // @Issue("JENKINS-8053")  // outdated submodules not removed by checkout
    @Issue("JENKINS-37419") // Git plugin checking out non-existent submodule from different branch
    @Test
    public void testOutdatedSubmodulesNotRemoved() throws Exception {
        if (!CLI_GIT_SUPPORTS_SUBMODULE_DEINIT) {
            return;
        }
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
        File cloneDir = tempFolder.newFolder();
        GitClient cloneGitClient = Git.with(TaskListener.NULL, new EnvVars()).in(cloneDir).using(gitImplName).getClient();
        cloneGitClient.init();
        cloneGitClient.clone_().url(repoRoot.getAbsolutePath()).execute();
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
        File firewallDir = new File(repoRoot, "modules/firewall");
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
        File submoduleDir = new File(repoRoot, ".git/modules/firewall");
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

    /* Enable long paths in gitClient workspace if running on Windows.
     * Assumes that the repository is at a directory path that is less
     * than 256 characters and that the checkout operation will
     * attempt to create a directory path greater than 256 characters.
     */
    private void enableLongPaths(GitClient gitClient) throws InterruptedException, IOException {
        CliGitAPIImpl cliGitClient;
        if (gitClient instanceof CliGitAPIImpl && isWindows()) {
            /* Enable core.longpaths prior to fetch on Windows -
             * testSubmodulesUsedFromOtherBranches submodule test will
             * fail with default Windows location otherwise
             */
            cliGitClient = (CliGitAPIImpl) gitClient;
            cliGitClient.launchCommand("config", "core.longpaths", "true");
        }
    }

    @Issue("JENKINS-37419") // Submodules from other branches are used in checkout
    @Test
    public void testSubmodulesUsedFromOtherBranches() throws Exception {
        /* Submodules not fully supported with JGit */
        // JGit implementation doesn't handle renamed submodules
        if (!gitImplName.equals("git")) {
            return;
        }
        String oldBranchName = "tests/getSubmodules";
        String upstream = fetchUpstream(oldBranchName);
        /* Enable long paths to prevent checkout failure on default Windows workspace with MSI installer */
        enableLongPaths(gitClient);
        if (random.nextBoolean()) {
            gitClient.checkoutBranch(oldBranchName, upstream + "/" + oldBranchName);
        } else {
            gitClient.checkout().branch(oldBranchName).ref(upstream + "/" + oldBranchName).deleteBranchIfExist(true).execute();
        }
        assertBranches(gitClient, oldBranchName);
        assertSubmoduleDirectories(gitClient, false, "firewall", "ntp", "sshkeys"); // No submodule init or update yet

        if (isWindows() && repoRoot.getAbsolutePath().length() > 120) {
            // Skip the test when long paths would break the test
            return;
        }
        /* Create tests/addSubmodules branch with one more module */
        String newBranchName = "tests/addSubmodules";
        String newDirName = "git-client-plugin-" + (10 + random.nextInt(90));
        gitClient.branch(newBranchName);
        gitClient.addSubmodule(srcRepoDir.getAbsolutePath(), "modules/" + newDirName);
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

    @Issue("JENKINS-46054")
    @Test
    public void testSubmoduleUrlEndsWithDotUrl() throws Exception {
        // Create a new repository that includes ".url" in directory name
        File baseDir = tempFolder.newFolder();
        File urlRepoDir = new File(baseDir, "my-submodule.url");
        assertTrue("Failed to create URL repo dir", urlRepoDir.mkdir());
        GitClient urlRepoClient = Git.with(TaskListener.NULL, new EnvVars()).in(urlRepoDir).using(gitImplName).getClient();
        urlRepoClient.init();
        CliGitCommand gitCmd = new CliGitCommand(urlRepoClient);
        gitCmd.run("config", "user.name", "Vojtěch GitClientTest Zweibrücken-Šafařík");
        gitCmd.run("config", "user.email", "email.from.git.client@example.com");
        File readme = new File(urlRepoDir, "readme");
        String readmeText = "This repo includes .url in its directory name (" + random.nextInt() + ")";
        Files.write(Paths.get(readme.getAbsolutePath()), readmeText.getBytes());
        urlRepoClient.add("readme");
        urlRepoClient.commit("Added README to repo used as a submodule");
        /* Enable long paths to prevent checkout failure on default Windows workspace with MSI installer */
        enableLongPaths(urlRepoClient);

        // Add new repository as submodule to repository that ends in .url
        File repoHasSubmodule = new File(baseDir, "has-submodule.url");
        assertTrue("Failed to create repo dir that will have submodule", repoHasSubmodule.mkdir());
        GitClient repoHasSubmoduleClient = Git.with(TaskListener.NULL, new EnvVars()).in(repoHasSubmodule).using(gitImplName).getClient();
        repoHasSubmoduleClient.init();
        gitCmd = new CliGitCommand(repoHasSubmoduleClient);
        gitCmd.run("config", "user.name", "Vojtěch GitClientTest repo submodule Zweibrücken-Šafařík");
        gitCmd.run("config", "user.email", "email.from.git.client@example.com");
        /* Enable long paths to prevent checkout failure on default Windows workspace with MSI installer */
        enableLongPaths(repoHasSubmoduleClient);
        File hasSubmoduleReadme = new File(repoHasSubmodule, "readme");
        String hasSubmoduleReadmeText = "Repo has a submodule that includes .url in its directory name (" + random.nextInt() + ")";
        Files.write(Paths.get(hasSubmoduleReadme.getAbsolutePath()), hasSubmoduleReadmeText.getBytes());
        repoHasSubmoduleClient.add("readme");
        repoHasSubmoduleClient.commit("Added README to repo that will include a submodule whose URL ends in '.url'");
        String moduleDirBaseName = "module.named.url";
        File modulesDir = new File(repoHasSubmodule, "modules");
        assertTrue("Failed to create modules dir in repoHasSubmodule", modulesDir.mkdir());
        repoHasSubmoduleClient.addSubmodule(repoHasSubmodule.getAbsolutePath(), "modules/" + moduleDirBaseName);
        repoHasSubmoduleClient.add(".");
        repoHasSubmoduleClient.commit("Add modules/" + moduleDirBaseName + " as submodule");
        repoHasSubmoduleClient.submoduleInit();
        repoHasSubmoduleClient.submoduleUpdate().recursive(false).execute();
        assertSubmoduleStatus(repoHasSubmoduleClient, true, moduleDirBaseName);

        // Clone repoHasSubmodule to new repository with submodule
        File cloneDir = new File(baseDir, "cloned-submodule");
        assertTrue("Failed to create clone dir", cloneDir.mkdir());
        GitClient cloneGitClient = Git.with(TaskListener.NULL, new EnvVars()).in(cloneDir).using(gitImplName).getClient();
        cloneGitClient.init();
        cloneGitClient.clone_().url(repoHasSubmodule.getAbsolutePath()).execute();
        String branch = "master";
        cloneGitClient.checkoutBranch(branch, "origin/" + branch);
        /* Enable long paths to prevent checkout failure on default Windows workspace with MSI installer */
        enableLongPaths(cloneGitClient);
        cloneGitClient.submoduleInit();
        cloneGitClient.submoduleUpdate().recursive(false).execute();
        assertSubmoduleStatus(cloneGitClient, true, moduleDirBaseName);
    }

    @Test
    public void testGetSubmodules() throws Exception {
        // JGit implementation doesn't handle renamed submodules
        if (!gitImplName.equals("git")) {
            return;
        }
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

    @Test
    public void testFixSubmoduleUrls() throws Exception {
        // CliGit
        if (!gitImplName.equals("git")) {
            return;
        }
        IGitAPI gitAPI = (IGitAPI) gitClient;
        String branchName = "tests/getSubmodules";
        String upstream = checkoutAndAssertHasGitModules(branchName, true);
        List<IndexEntry> submodules = gitClient.getSubmodules(branchName);

        gitAPI.fixSubmoduleUrls("origin", TaskListener.NULL);
    }

    @Test
    public void testFixSubmoduleUrlsInvalidRemote() throws Exception {
        // CliGit
        if (!gitImplName.equals("git")) {
            return;
        }
        IGitAPI gitAPI = (IGitAPI) gitClient;
        GitException e = assertThrows(GitException.class,
                                      () -> {
                                          gitAPI.fixSubmoduleUrls("invalid-remote", TaskListener.NULL);
                                      });
        assertThat(e.getMessage(), containsString("Could not determine remote"));
    }

    @Test
    public void testFixSubmoduleUrlsJGitUnsupported() throws Exception {
        // JGit does not support fixSubmoduleUrls
        if (gitImplName.equals("git")) {
            return;
        }
        IGitAPI gitAPI = (IGitAPI) gitClient;
        assertThrows(UnsupportedOperationException.class,
                     () -> {
                         gitAPI.fixSubmoduleUrls("origin", TaskListener.NULL);
                     });
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
        String branchName = "tests/getSubmodules";
        String upstream = checkoutAndAssertHasGitModules(branchName, true);
        gitClient.submoduleInit();
        if (gitImplName.equals("git")) {
            assertSubmoduleDirectories(gitClient, false, "firewall", "ntp", "sshkeys");
        }
        // Test may fail if updateSubmodule called with remoteTracking == true
        // and the remoteTracking argument is used in the updateSubmodule call
        updateSubmodule(upstream, branchName, null);
        assertSubmoduleDirectories(gitClient, true, "firewall", "ntp", "sshkeys");
        assertSubmoduleContents("firewall", "ntp", "sshkeys");

        final File firewallDir = new File(repoRoot, "modules/firewall");
        final File firewallFile = File.createTempFile("untracked-", ".txt", firewallDir);
        final File ntpDir = new File(repoRoot, "modules/ntp");
        final File ntpFile = File.createTempFile("untracked-", ".txt", ntpDir);
        final File sshkeysDir = new File(repoRoot, "modules/sshkeys");
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

    // @Issue("JENKINS-14083") // build can't recover from broken submodule path
    // @Issue("JENKINS-15399") // changing submodule URL does not update repository
    // @Issue("JENKINS-27625") // local changes inside submodules not reset on checkout (see testModifiedTrackedFilesReset)
    // @Issue("JENKINS-39350") // local changes inside submodules not reset on checkout (see testModifiedTrackedFilesReset)
    // @Issue("JENKINS-22510") // clean after checkout fails to clean revision
    // @Issue("JENKINS-23727") // submodule update did not fail build on timeout
    // @Issue("JENKINS-28748") // submodule update --init fails on large submodule
    // @Issue("JENKINS-22084") // submodule update failure did not send configured e-mail
    // @Issue("JENKINS-31532") // pipeline snippet generator garbles certain submodule options
    // @Issue("JENKINS-31586") // NPE when using submodules extension
    // @Issue("JENKINS-39253") // notifyCommit trigger should detect changes to a submodule
    // @Issue("JENKINS-21521") // submodule defaults in git plugin 2.x different than git plugin 1.x
    // @Issue("JENKINS-31244") // submodule misconfiguration not reported clearly
    // @Issue("JENKINS-41553") // submodule status should be used instead of reading config file
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

    /* The describe tests depend on specific tags from the
     * jenkinsci/git-client-plugin repository. If your fork does not
     * include these tags, the describe tests will fail.
     */
    @Test
    public void testDescribeSrcCommit() throws Exception {
        assertThat(srcGitClient.describe(upstreamCommit.getName()), startsWith("git-client-1.6.3-23-gf75720d"));
    }

    @Test
    public void testDescribeSrcCommitPredecessor() throws Exception {
        assertThat(srcGitClient.describe(upstreamCommitPredecessor.getName()), startsWith("git-client-1.6.3-22-g867e5f1"));
    }

    @Test
    public void testDescribeTag() throws Exception {
        assertThat(srcGitClient.describe("git-client-1.19.6"), startsWith("git-client-1.19.6"));
    }

    @Test
    public void testDescribeTagFromMerge() throws Exception {
        assertThat(srcGitClient.describe("40d44ffce5fa589605dd6b6ad92ab7235a92b330"), startsWith("git-client-1.0.7-74-g40d44ff"));
    }

    @Test
    public void testDescribeTagDeepGraph() throws Exception {
        assertThat(srcGitClient.describe("640ef19f4157d9a5508d46c3f9ad0c41d7d7ef51"), startsWith("git-client-1.19.0-38-g640ef19"));
    }

    @Test
    public void testDescribeTagDeeperGraph() throws Exception {
        assertThat(srcGitClient.describe("88ca6b449dd155a03d7142c9ad5f17fd7ca2b34e"), startsWith("git-client-1.11.0-24-g88ca6b4"));
    }

    @Test(expected = GitException.class)
    public void testDescribeNoTag() throws Exception {
        srcGitClient.describe("5a865818566c9d03738cdcd49cc0a1543613fd41");
    }

    /* A SHA1 that exists in src repo, but unlikely to be referenced from a local branch in src repo */
    private final String TESTS_NOT_SUBMODULE_SHA1 = "f04fae26f6b612c4a575314222d72c20ca4090a5";

    @Test
    public void testgetBranchesContainingTrue_existing_sha1() throws Exception {
        List<Branch> branches = srcGitClient.getBranchesContaining(TESTS_NOT_SUBMODULE_SHA1, true);
        assertThat(branches, is(not(empty())));
    }

    @Test
    public void testgetBranchesContainingFalse_existing_sha1() throws Exception {
        List<Branch> branches = srcGitClient.getBranchesContaining(TESTS_NOT_SUBMODULE_SHA1, false);
        assertThat(branches, is(empty()));
    }

    /* A SHA1 that doesn't exist in src repo */
    private final String NON_EXISTENT_SHA1 = "adbadcaddadfadba11adbeefb1abbedb1adebed5";

    @Test(expected = GitException.class)
    public void testgetBranchesContainingTrue_non_existent_sha1() throws Exception {
        srcGitClient.getBranchesContaining(NON_EXISTENT_SHA1, true);
    }

    @Test(expected = GitException.class)
    public void testgetBranchesContainingFalse_non_existent_sha1() throws Exception {
        srcGitClient.getBranchesContaining(NON_EXISTENT_SHA1, false);
    }

    @Test
    public void testgetRemoteSymbolicReferences_from_empty_repo() throws Exception {
        assertThat(gitClient.getRemoteSymbolicReferences(repoRoot.getAbsolutePath(), null).keySet(), hasSize(0));
    }

    @Test
    public void testgetRemoteSymbolicReferences_null() throws Exception {
        if (!CLI_GIT_SUPPORTS_SYMREF) {
            return;
        }
        commitOneFile("A-Single-File-Commit");
        assertThat(gitClient.getRemoteSymbolicReferences(repoRoot.getAbsolutePath(), null),
                hasEntry(Constants.HEAD, "refs/heads/master"));
    }

    @Test
    public void testgetRemoteSymbolicReferences_null_old_git() throws Exception {
        if (!gitImplName.equals("git") || CLI_GIT_SUPPORTS_SYMREF) {
            return;
        }
        commitOneFile("A-Single-File-Commit");
        assertThat(gitClient.getRemoteSymbolicReferences(repoRoot.getAbsolutePath(), null).keySet(), hasSize(0));
    }

    @Test
    public void testgetRemoteSymbolicReferences_null_old_git_use_jgit() throws Exception {
        if (gitImplName.equals("git") || CLI_GIT_SUPPORTS_SYMREF) {
            return;
        }
        commitOneFile("A-Single-File-Commit");
        assertThat(gitClient.getRemoteSymbolicReferences(repoRoot.getAbsolutePath(), null),
                hasEntry(Constants.HEAD, "refs/heads/master"));
    }

    @Test
    public void testgetRemoteSymbolicReferences_with_non_matching_pattern() throws Exception {
        commitOneFile("A-Single-File-Commit");
        assertThat(gitClient.getRemoteSymbolicReferences(repoRoot.getAbsolutePath(), "non-matching-pattern").keySet(), hasSize(0));
    }

    @Test
    public void testgetRemoteSymbolicReferences_with_matching_pattern() throws Exception {
        if (!CLI_GIT_SUPPORTS_SYMREF) {
            return;
        }
        commitOneFile("A-Single-File-Commit");
        assertThat(gitClient.getRemoteSymbolicReferences(repoRoot.getAbsolutePath(), Constants.HEAD),
                hasEntry(Constants.HEAD, "refs/heads/master"));
    }

    @Test
    public void testgetRemoteSymbolicReferences_with_matching_pattern_old_git() throws Exception {
        if (!gitImplName.equals("git") || CLI_GIT_SUPPORTS_SYMREF) {
            return;
        }
        commitOneFile("A-Single-File-Commit");
        assertThat(gitClient.getRemoteSymbolicReferences(repoRoot.getAbsolutePath(), Constants.HEAD).keySet(), hasSize(0));
    }

    @Test
    public void testgetRemoteSymbolicReferences_with_matching_pattern_old_git_with_jgit() throws Exception {
        if (gitImplName.equals("git") || CLI_GIT_SUPPORTS_SYMREF) {
            return;
        }
        commitOneFile("A-Single-File-Commit");
        assertThat(gitClient.getRemoteSymbolicReferences(repoRoot.getAbsolutePath(), Constants.HEAD),
                hasEntry(Constants.HEAD, "refs/heads/master"));
    }

    @Test
    public void testgetRemoteSymbolicReferences_with_non_default_HEAD() throws Exception {
        if (!CLI_GIT_SUPPORTS_SYMREF) {
            return;
        }
        commitOneFile("A-Single-File-Commit");

        CliGitCommand gitCmd = new CliGitCommand(gitClient);
        gitCmd.run("checkout", "-b", "new-branch");
        gitCmd.assertOutputContains(".*Switched to a new branch.*");

        commitOneFile("A-Second-File-Commit");

        gitCmd = new CliGitCommand(gitClient);
        gitCmd.run("symbolic-ref", Constants.HEAD, "refs/heads/new-branch");

        assertThat(gitClient.getRemoteSymbolicReferences(repoRoot.getAbsolutePath(), Constants.HEAD),
                hasEntry(Constants.HEAD, "refs/heads/new-branch"));
        assertThat(gitClient.getRemoteSymbolicReferences(repoRoot.getAbsolutePath(), null),
                hasEntry(Constants.HEAD, "refs/heads/new-branch"));
    }

    @Test(expected = GitException.class)
    public void testgetRemoteSymbolicReferences_URI_Syntax() throws Exception {
        if (!CLI_GIT_SUPPORTS_SYMREF) {
            throw new GitException("Skipping JGit tests in testgetRemoteSymbolicReferences_URI_Syntax");
        }
        gitClient.getRemoteSymbolicReferences("error: invalid repo URL", Constants.HEAD);
    }

    @Test(expected = GitException.class)
    public void testgetRemoteSymbolicReferences_URI_Syntax_old_jgit() throws Exception {
        if (gitImplName.equals("git") || CLI_GIT_SUPPORTS_SYMREF) {
            throw new GitException("Skipping JGit tests in testgetRemoteSymbolicReferences_URI_Syntax_old_jgit");
        }
        gitClient.getRemoteSymbolicReferences("error: invalid repo URL", Constants.HEAD);
    }

    @Test
    public void testgetRemoteSymbolicReferences_URI_Syntax_old_git() throws Exception {
        if (!gitImplName.equals("git") || CLI_GIT_SUPPORTS_SYMREF) {
            return;
        }
        assertThat(gitClient.getRemoteSymbolicReferences(repoRoot.getAbsolutePath(), Constants.HEAD).keySet(), hasSize(0));
    }

    @Test(expected = GitException.class)
    public void testgetRemoteReferences_URI_Syntax() throws Exception {
        gitClient.getRemoteReferences("error: invalid repo URL", Constants.HEAD, false, false);
    }

    @Test
    public void testGetTags() throws Exception {
        Set<GitObject> result = gitClient.getTags();
        assertThat(result, is(empty()));
    }

    @Test
    public void testGetTags_NoTags() throws Exception {
        ObjectId commitOne = commitOneFile();
        Set<GitObject> result = gitClient.getTags();
        assertThat(result, is(empty()));
    }

    @Test
    public void testGetTags_OneTag() throws Exception {
        ObjectId commitOne = commitOneFile();
        String tagName = "tag-one";
        gitClient.tag(tagName, "Comment for annotated " + tagName);
        GitObject expectedTag = new GitObject(tagName, commitOne);

        Set<GitObject> result = gitClient.getTags();
        assertThat(result, contains(expectedTag));
    }

    @Test
    public void testGetTags_ThreeTags() throws Exception {
        ObjectId commitOne = commitOneFile();
        String tagName = "tag-one-annotated";
        gitClient.tag(tagName, "Comment for annotated " + tagName);
        GitObject expectedTag = new GitObject(tagName, commitOne);

        String tagName2 = "tag-two";
        CliGitCommand gitCmd = new CliGitCommand(gitClient);
        gitCmd.run("tag", tagName2);
        GitObject expectedTag2 = new GitObject(tagName2, commitOne);

        String tagName3 = "tag-three-annotated";
        gitCmd.run("tag", "-a", tagName3, "-m", "Annotated tag " + tagName3);
        GitObject expectedTag3 = new GitObject(tagName3, commitOne);

        Set<GitObject> result = gitClient.getTags();
        assertThat(result, containsInAnyOrder(expectedTag, expectedTag2, expectedTag3));
    }

    private String truncateAtWord(String src, int maxLength) {
        java.text.BreakIterator breakIterator = java.text.BreakIterator.getWordInstance();
        breakIterator.setText(src);
        return src.substring(0, breakIterator.preceding(maxLength + 1)).trim();
    }

    private String wrapAtWord(String src, int maxLength) {
        return org.apache.commons.text.WordUtils.wrap(src, maxLength);
    }

    private String padLinesWithSpaces(String src, int spacePadCount) {
        char[] paddingArray = new char[spacePadCount];
        Arrays.fill(paddingArray, ' ');
        String padding = new String(paddingArray);
        return padding + src.replace("\n", "\n" + padding).trim();
    }

    @Test
    @Issue("JENKINS-29977")
    public void testChangelogFirstLineTruncation() throws Exception {
        //            1         2         3         4         5         6         7         8
        //   12345678901234567890123456789012345678901234567890123456789012345678901234567890
        final String longFirstLine =
            "The first line of this commit message is longer than 72 characters to show JENKINS-29977";
        final String longBody =
            "The body of this commit message is also longer than 72 characters though that is not part of JENKINS-29977";
        // Intentionally randomize whether the commit message body ends with a newline character
        final String commitMessage = longFirstLine + "\n\n" + longBody + (random.nextBoolean() ? "\n" : "");
        final ObjectId commit = commitOneFile(commitMessage);
        ChangelogCommand changelog = gitClient.changelog();
        StringWriter changelogStringWriter = new StringWriter();
        changelog.includes(commit).to(changelogStringWriter).execute();

        final String truncatedFirstLine = truncateAtWord(longFirstLine, 72) + "\n";
        final String truncatedBody = truncateAtWord(longBody, 72) + "\n";

        final String wrappedFirstLine = wrapAtWord(longFirstLine, 72);
        final String wrappedBody = wrapAtWord(longBody, 72);

        // Truncated lines are NOT included in the changelog
        assertThat(changelogStringWriter.toString(), not(containsString(truncatedFirstLine)));
        assertThat(changelogStringWriter.toString(), not(containsString(truncatedBody)));

        // Wrapped lines are NOT included in the changelog
        assertThat(changelogStringWriter.toString(), not(containsString(padLinesWithSpaces(wrappedFirstLine, 4))));
        assertThat(changelogStringWriter.toString(), not(containsString(padLinesWithSpaces(wrappedBody, 4))));

        // Unmodified lines are included in the changelog
        assertThat(changelogStringWriter.toString(), containsString(longFirstLine));
        assertThat(changelogStringWriter.toString(), containsString(longBody));

        // Entire unmodified commit message is included in the changelog
        assertThat(changelogStringWriter.toString(), containsString(padLinesWithSpaces(commitMessage, 4)));
    }

    @Test
    @Issue("JENKINS-55284") // Pull must overwrite existing tags
    public void testFetchOverwritesExistingTags() throws Exception {
        fetchWithTags(gitClient, "origin");

        /* Confirm expected tag exists */
        Set<GitObject> tags = gitClient.getTags();
        String tagName = "git-client-2.7.5";
        GitObject tag = new GitObject(tagName, ObjectId.fromString("0629421ea3ffb2b91ea0d62ffc5f97142fe137f3"));
        assertThat(tags, hasItems(tag));

        /* Delete the tag that was just confirmed to exist */
        deleteTag(gitClient, tagName);

        /* Create a new tag using the same name on a new commit */
        ObjectId commitOne = commitOneFile();
        gitClient.tag(tagName, "Moved tag " + tagName);
        GitObject updatedTag = new GitObject(tagName, commitOne);

        /* Confirm updated tag exists and old tag does not exist */
        tags = gitClient.getTags();
        assertThat(tags, hasItems(updatedTag));
        assertThat(tags, not(hasItems(tag)));

        /* Fetch to replace updated tag with original tag */
        fetchWithTags(gitClient, "origin");
        tags = gitClient.getTags();
        assertThat(tags, not(hasItems(updatedTag)));
        assertThat(tags, hasItems(tag));
    }

    // Tests ported from CliGitAPIImplTest
    @Test
    public void test_git_version_debian_wheezy() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  1, 7, 10, 4),
                cliGitAPIImplTest.new VersionTest(true,  1, 7, 10, 3),
                cliGitAPIImplTest.new VersionTest(false, 1, 7, 10, 5)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 1.7.10.4", versions);
    }

    @Test
    public void test_git_version_debian_testing() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  2, 0, 1, 0),
                cliGitAPIImplTest.new VersionTest(true,  2, 0, 0, 0),
                cliGitAPIImplTest.new VersionTest(false, 2, 0, 2, 0),
                cliGitAPIImplTest.new VersionTest(false, 2, 1, 0, 0)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 2.0.1", versions);
    }

    @Test
    public void test_git_version_debian_testing_old() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  2, 0,  0,  0),
                cliGitAPIImplTest.new VersionTest(true,  1, 9, 99, 99),
                cliGitAPIImplTest.new VersionTest(false, 2, 0,  1,  0)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 2.0.0.rc0", versions);
        cliGitAPIImplTest.assertVersionOutput("git version 2.0.0.rc2", versions);
        cliGitAPIImplTest.assertVersionOutput("git version 2.0.0", versions);
        cliGitAPIImplTest.assertVersionOutput("git version 2.0", versions); // mythical version
        cliGitAPIImplTest.assertVersionOutput("git version 2", versions);   // mythical version
    }

    @Test
    public void test_git_version_debian_testing_older() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  1, 9,  0,  0),
                cliGitAPIImplTest.new VersionTest(true,  1, 8, 99, 99),
                cliGitAPIImplTest.new VersionTest(false, 1, 9,  1,  0)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 1.9.0", versions);
    }

    @Test
    public void test_git_version_windows_1800() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  1, 8,  0, 0),
                cliGitAPIImplTest.new VersionTest(true,  1, 7, 99, 0),
                cliGitAPIImplTest.new VersionTest(false, 1, 8,  1, 0)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 1.8.0.msysgit.0", versions);
    }

    @Test
    public void test_git_version_windows_1840() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  1, 8, 4,  0),
                cliGitAPIImplTest.new VersionTest(true,  1, 8, 3, 99),
                cliGitAPIImplTest.new VersionTest(false, 1, 8, 4,  1)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 1.8.4.msysgit.0", versions);
    }

    @Test
    public void test_git_version_windows_1852() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  1, 8, 5, 2),
                cliGitAPIImplTest.new VersionTest(true,  1, 8, 5, 1),
                cliGitAPIImplTest.new VersionTest(false, 1, 8, 5, 3)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 1.8.5.2.msysgit.0", versions);
    }

    @Test
    public void test_git_version_windows_1900() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  1, 9,  0, 0),
                cliGitAPIImplTest.new VersionTest(true,  1, 8, 99, 0),
                cliGitAPIImplTest.new VersionTest(false, 1, 9,  0, 1)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 1.9.0.msysgit.0", versions);
    }

    @Test
    public void test_git_version_windows_1920() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  1, 9, 2,  0),
                cliGitAPIImplTest.new VersionTest(true,  1, 9, 1, 99),
                cliGitAPIImplTest.new VersionTest(false, 1, 9, 2,  1)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 1.9.2.msysgit.0", versions);
    }

    @Test
    public void test_git_version_windows_1940() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  1, 9, 4,  0),
                cliGitAPIImplTest.new VersionTest(true,  1, 9, 3, 99),
                cliGitAPIImplTest.new VersionTest(false, 1, 9, 4,  1)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 1.9.4.msysgit.0", versions);
    }

    @Test
    public void test_git_version_windows_2501() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  2, 5, 0, 1),
                cliGitAPIImplTest.new VersionTest(true,  2, 5, 0, 0),
                cliGitAPIImplTest.new VersionTest(false, 2, 5, 0, 2)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 2.5.0.windows.1", versions);
    }

    @Test
    public void test_git_version_windows_2_10_1_1() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  2, 10, 1, 1),
                cliGitAPIImplTest.new VersionTest(true,  2, 10, 1, 0),
                cliGitAPIImplTest.new VersionTest(true,  2, 10, 0, 1),
                cliGitAPIImplTest.new VersionTest(false, 2, 10, 1, 2),
                cliGitAPIImplTest.new VersionTest(false, 2, 10, 2, 0)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 2.10.1.windows.1", versions);
    }

    @Test
    public void test_git_version_redhat_5() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  1, 8, 2, 1),
                cliGitAPIImplTest.new VersionTest(true,  1, 8, 2, 0),
                cliGitAPIImplTest.new VersionTest(false, 1, 8, 2, 2)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 1.8.2.1", versions);
    }

    @Test
    public void test_git_version_redhat_65() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  1, 7, 1,  0),
                cliGitAPIImplTest.new VersionTest(true,  1, 7, 0, 99),
                cliGitAPIImplTest.new VersionTest(false, 1, 7, 1,  1),
                cliGitAPIImplTest.new VersionTest(false, 1, 7, 2,  0)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 1.7.1", versions);
    }

    @Test
    public void test_git_version_opensuse_13() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  1, 8, 4, 5),
                cliGitAPIImplTest.new VersionTest(true,  1, 8, 4, 4),
                cliGitAPIImplTest.new VersionTest(false, 1, 8, 4, 6)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 1.8.4.5", versions);
    }

    @Test
    public void test_git_version_ubuntu_13() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  1, 8, 3, 2),
                cliGitAPIImplTest.new VersionTest(true,  1, 8, 3, 1),
                cliGitAPIImplTest.new VersionTest(false, 1, 8, 3, 3)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 1.8.3.2", versions);
    }

    @Test
    public void test_git_version_ubuntu_14_04_ppa() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  2, 2, 2, 0),
                cliGitAPIImplTest.new VersionTest(true,  2, 2, 1, 0),
                cliGitAPIImplTest.new VersionTest(false, 2, 2, 3, 0)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 2.2.2", versions);
    }

    @Test
    public void test_git_version_ubuntu_14_04_ppa_2_3_0() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  2, 3, 0, 0),
                cliGitAPIImplTest.new VersionTest(true,  2, 2, 9, 0),
                cliGitAPIImplTest.new VersionTest(false, 2, 3, 1, 0)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 2.3.0", versions);
    }

    @Test
    public void test_git_version_ubuntu_14_04_ppa_2_3_5() {
        if (!gitImplName.equals("git")) {
            return;
        }
        CliGitAPIImplTest.VersionTest[] versions = {
                cliGitAPIImplTest.new VersionTest(true,  2, 3, 5, 0),
                cliGitAPIImplTest.new VersionTest(true,  2, 2, 9, 9),
                cliGitAPIImplTest.new VersionTest(false, 2, 3, 5, 1),
                cliGitAPIImplTest.new VersionTest(false, 2, 4, 0, 0),
                cliGitAPIImplTest.new VersionTest(false, 3, 0, 0, 0)
        };
        cliGitAPIImplTest.assertVersionOutput("git version 2.3.5", versions);
    }

    @Test
    public void test_git_ssh_executable_found_on_windows() {
        if (!gitImplName.equals("git") || !isWindows()) {
            return;
        }
        cliGitAPIImplTest.setTimeoutVisibleInCurrentTest(false);

        CliGitAPIImpl git = new CliGitAPIImpl("git", new File("."), cliGitAPIImplTest.listener, cliGitAPIImplTest.env);
        assertTrue("ssh.exe not found", git.getSSHExecutable().exists());
    }

    @Test
    public void test_git_branch_with_line_breaks_and_long_strings() {
        if (!gitImplName.equals("git")) {
            return;
        }
        String gitBranchOutput =
                "* (HEAD detached at b297853)  b297853e667d5989801937beea30fcec7d1d2595 Commit message with line breaks\r very-long-string-with-more-than-44-characters\n" +
                        "  remotes/origin/master       e0d3f46c4fdb8acd068b6b127356931411d16e23 Commit message with line breaks\r very-long-string-with-more-than-44-characters and some more text\n" +
                        "  remotes/origin/develop      fc8996efc1066d9dae529e5187800f84995ca56f Single-line commit message\n";

        cliGitAPIImplTest.setTimeoutVisibleInCurrentTest(false);
        CliGitAPIImpl git = new CliGitAPIImpl("git", new File("."), cliGitAPIImplTest.listener, cliGitAPIImplTest.env);
        Set<Branch> branches = git.parseBranches(gitBranchOutput);
        assertEquals("\"git branch -a -v --no-abbrev\" output correctly parsed", 2, branches.size());
    }

    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
