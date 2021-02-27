package org.jenkinsci.plugins.gitclient;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class CliGitAPIWindowsFilePermissionsTest {

    private CliGitAPIImpl cliGit;
    private File file;
    private AclFileAttributeView fileAttributeView;
    private UserPrincipal userPrincipal;

    @Before
    public void beforeEach() throws Exception {
        if (!isWindows()) {
            return;
        }
        cliGit = new CliGitAPIImpl("git", new File("."), null, null);
        file = cliGit.createTempFile("permission", ".suff");
        Path path = Paths.get(file.toURI());
        fileAttributeView = Files.getFileAttributeView(path, AclFileAttributeView.class);
        assertNotNull(fileAttributeView);
        userPrincipal = fileAttributeView.getOwner();
        assertNotNull(userPrincipal);
    }

    @Test
    public void test_windows_file_permission_is_set_correctly() throws Exception {
        if (!isWindows()) {
            return;
        }
        cliGit.fixSshKeyOnWindows(file);
        assertEquals(1, fileAttributeView.getAcl().size());
        AclEntry aclEntry = fileAttributeView.getAcl().get(0);
        assertTrue(aclEntry.flags().isEmpty());
        assertEquals(CliGitAPIImpl.ACL_ENTRY_PERMISSIONS, aclEntry.permissions());
        assertEquals(userPrincipal, aclEntry.principal());
        assertEquals(AclEntryType.ALLOW, aclEntry.type());
    }

    @Test
    public void test_windows_file_permission_are_incorrect() throws Exception {
        if (!isWindows()) {
            return;
        }
        // By default files include System and builtin administrators
        assertNotSame(1, fileAttributeView.getAcl().size());
        for (AclEntry entry : fileAttributeView.getAcl()) {
            if (entry.principal().equals(userPrincipal)) {
                assertNotSame(CliGitAPIImpl.ACL_ENTRY_PERMISSIONS, entry.permissions());
            }
        }
    }

    /** inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue */
    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
