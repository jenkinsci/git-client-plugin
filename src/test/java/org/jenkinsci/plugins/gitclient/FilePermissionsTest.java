package org.jenkinsci.plugins.gitclient;

import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.*;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class FilePermissionsTest {

    private final int permission;

    /**
     * Test that command line git preserves execute permission across clone. Git
     * does not preserve all file permission bits, only the execute bit for the
     * owner.
     */
    public FilePermissionsTest(Integer filePermission) {
        this.permission = filePermission;
    }

    private static final TaskListener listener = StreamTaskListener.fromStdout();

    private static File repo;

    @BeforeClass
    public static void createTestRepo() throws IOException, InterruptedException {
        if (isWindows()) return;
        repo = Files.createTempDirectory(null).toFile();
        Git.with(listener, new hudson.EnvVars()).in(repo).getClient().init();
    }

    /**
     * Tests that need the default branch name can use this variable.
     */
    private static String defaultBranchName = "mast" + "er"; // Intentionally separated string

    /**
     * Determine the global default branch name.
     * Command line git is moving towards more inclusive naming.
     * Git 2.32.0 honors the configuration variable `init.defaultBranch` and uses it for the name of the initial branch.
     * This method reads the global configuration and uses it to set the value of `defaultBranchName`.
     */
    @BeforeClass
    public static void computeDefaultBranchName() throws Exception {
        File configDir = Files.createTempDirectory("readGitConfig").toFile();
        CliGitCommand getDefaultBranchNameCmd = new CliGitCommand(Git.with(TaskListener.NULL, new hudson.EnvVars()).in(configDir).using("git").getClient());
        String[] output = getDefaultBranchNameCmd.runWithoutAssert("config", "--get", "init.defaultBranch");
        for (String s : output) {
            String result = s.trim();
            if (result != null && !result.isEmpty()) {
                defaultBranchName = result;
            }
        }
        assertTrue("Failed to delete temporary readGitConfig directory", configDir.delete());
    }

    @AfterClass
    public static void verifyTestRepo() throws IOException, InterruptedException {
        if (isWindows()) return;
        File newRepo = null;
        try {
            newRepo = cloneTestRepo(repo);
            List<Integer[]> permissions = permissionBits();
            for (Integer[] permArray : permissions) {
                int filePermission = permArray[0];
                verifyFile(repo, filePermission);
                verifyFile(newRepo, filePermission);
            }
        } finally {
            FileUtils.deleteDirectory(repo); // Remove the original test repo
            if (newRepo != null) {
                FileUtils.deleteDirectory(newRepo); // Remove the verification repo
            }
        }
    }

    private static File cloneTestRepo(File repo) throws IOException, InterruptedException {
        File newRepo = Files.createTempDirectory(null).toFile();
        GitClient git = Git.with(listener, new hudson.EnvVars()).in(newRepo).using("git").getClient();
        String repoURL = repo.toURI().toURL().toString();
        git.clone_().repositoryName("origin").url(repoURL).execute();
        git.checkoutBranch(defaultBranchName, "origin/" + defaultBranchName);
        return newRepo;
    }

    private static void verifyFile(File repo, int staticPerm) throws IOException {
        String fileName = String.format("git-%03o.txt", staticPerm);
        File file = new File(repo, fileName);
        assertTrue("Missing " + file.getAbsolutePath(), file.exists());
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(fileName + " wrong content: '" + content + "'", content.contains(fileName));
        String rwx = permString(staticPerm);
        Set<PosixFilePermission> expected = PosixFilePermissions.fromString(rwx);
        Path path = FileSystems.getDefault().getPath(file.getPath());
        PosixFileAttributes attrs = Files.getFileAttributeView(path, PosixFileAttributeView.class).readAttributes();
        assertEquals(fileName + " OWNER_EXECUTE (execute) perm mismatch, expected: " + expected + ", was actually: " + attrs.permissions(),
                     expected.contains(PosixFilePermission.OWNER_EXECUTE),
                     attrs.permissions().contains(PosixFilePermission.OWNER_EXECUTE)
                     );
    }

    @After
    public void checkListenerLoggedNoErrors() {
        assertFalse("Error logged by listener", listener.getLogger().checkError());
    }

    @Parameterized.Parameters
    public static List<Integer[]> permissionBits() {
        List<Integer[]> permissions = new ArrayList<>();
        /* 0640 and 0740 are the only permissions to be tested */
        Integer[] permissionArray0640 = {0640};
        permissions.add(permissionArray0640);
        /* When running as root, execute permission is NOT preserved, don't test it */
        if (!(new File("/").canWrite())) {
            Integer[] permissionArray0740 = {0740};
            permissions.add(permissionArray0740);
        }
        return permissions;
    }

    @Test
    public void posixPermissionTest() throws IOException, GitException, InterruptedException {
        if (isWindows()) {
            return;
        }
        addFile();
        modifyFile();
    }

    private String getFileName() {
        return String.format("git-%03o.txt", permission);
    }

    private void addFile() throws IOException, GitException, InterruptedException {
        String fileName = getFileName();
        String content = fileName + " and UUID " + UUID.randomUUID();
        File added = new File(repo, fileName);
        assertFalse(fileName + " already exists", added.exists());
        Files.writeString(added.toPath(), content, StandardCharsets.UTF_8);
        assertTrue(fileName + " doesn't exist", added.exists());

        GitClient git = Git.with(listener, new hudson.EnvVars()).in(repo).getClient();
        git.add(fileName);
        git.commit("Added " + fileName);
        Path path = FileSystems.getDefault().getPath(added.getPath());
        assertEquals(path, Files.setPosixFilePermissions(path, filePerms(permission)));
        git.add(fileName);
        git.commit(String.format("Perms %03o %s", permission, fileName));
    }

    private void modifyFile() throws IOException, GitException, InterruptedException {
        String fileName = getFileName();
        String content = fileName + " chg UUID " + UUID.randomUUID();
        File modified = new File(repo, fileName);
        assertTrue(fileName + " doesn't exist", modified.exists());
        Files.writeString(modified.toPath(), content, StandardCharsets.UTF_8);

        GitClient git = Git.with(listener, new hudson.EnvVars()).in(repo).getClient();
        git.add(fileName);
        git.commit("Modified " + fileName);
        assertTrue(fileName + " doesn't exist", modified.exists());
    }

    private static String permString(int filePermission) {
        return String.valueOf((filePermission & 0400) != 0 ? 'r' : '-') +
                ((filePermission & 0200) != 0 ? 'w' : '-') +
                ((filePermission & 0100) != 0 ? 'x' : '-') +
                ((filePermission & 0040) != 0 ? 'r' : '-') +
                ((filePermission & 0020) != 0 ? 'w' : '-') +
                ((filePermission & 0010) != 0 ? 'x' : '-') +
                ((filePermission & 0004) != 0 ? 'r' : '-') +
                ((filePermission & 0002) != 0 ? 'w' : '-') +
                ((filePermission & 0001) != 0 ? 'x' : '-');
    }

    private Set<PosixFilePermission> filePerms(int filePermission) {
        return PosixFilePermissions.fromString(permString(filePermission));
    }

    private static boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
