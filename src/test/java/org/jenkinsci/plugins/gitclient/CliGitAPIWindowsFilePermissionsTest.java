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
import java.nio.file.attribute.UserPrincipalLookupService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class CliGitAPIWindowsFilePermissionsTest {

    private CliGitAPIImpl cliGit;
    private File file;
    private AclFileAttributeView fileAttributeView;
    private UserPrincipal userPrincipal;
    private String username;

    @Before
    public void beforeEach() throws Exception {
        assumeTrue(isWindows());
        cliGit = new CliGitAPIImpl("git", new File("."), null, null);
        file = cliGit.createTempFile("permission", ".suff");
        Path path = Paths.get(file.toURI());
        fileAttributeView = Files.getFileAttributeView(path, AclFileAttributeView.class);
        assertNotNull(fileAttributeView);
        UserPrincipalLookupService userPrincipalLookupService = path.getFileSystem().getUserPrincipalLookupService();
        assertNotNull(userPrincipalLookupService);
        username = cliGit.getWindowsUserName(fileAttributeView);
        assertNotNull(username);
        userPrincipal = userPrincipalLookupService.lookupPrincipalByName(username);
        assertNotNull(userPrincipal);
        assertEquals(userPrincipal, fileAttributeView.getOwner());
    }

    @Test
    public void test_windows_file_permission_is_set_correctly() throws Exception {
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
        // By default files include System and builtin administrators
        assertNotSame(1, fileAttributeView.getAcl().size());
        for (AclEntry entry : fileAttributeView.getAcl()) {
            if (entry.principal().equals(userPrincipal)) {
                assertNotSame(CliGitAPIImpl.ACL_ENTRY_PERMISSIONS, entry.permissions());
            }
        }
    }

    @Test
    public void test_windows_username_lookup() {
        assertEquals(username, userPrincipal.getName());
    }

    /** inline ${@link hudson.Functions#isWindows()} to prevent a transient remote classloader issue */
    private boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
