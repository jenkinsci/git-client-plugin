/*
 * The MIT License
 *
 * Copyright 2020 Mark Waite.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteGitImplTest {

    @TempDir
    private File temporaryFolderRule;

    private File localFolder;
    private GitClient defaultClient;
    private RemoteGitImpl remoteGit;
    private String gitImplName;

    private final Random random = new Random();

    @BeforeEach
    void setUp() throws Exception {
        /* Use a randomly selected git implementation in hopes of detecting more issues with fewer tests */
        String[] gitImplAlternatives = {"git", "jgit", "jgitapache"};
        gitImplName = gitImplAlternatives[random.nextInt(gitImplAlternatives.length)];
        localFolder = newFolder(temporaryFolderRule, "junit");
        defaultClient = Git.with(TaskListener.NULL, new EnvVars())
                .in(localFolder)
                .using(gitImplName)
                .getClient();
        remoteGit = new RemoteGitImpl(defaultClient);
    }

    @Test
    void testGetRepository() {
        assertThrows(UnsupportedOperationException.class, () -> remoteGit.getRepository());
    }

    @Test
    void testClearCredentials() {
        remoteGit.clearCredentials();
    }

    @Test
    void testAddCredentials() throws Exception {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String password = "password";
        String url = "https://github.com/jenkinsci/git-client-plugin";
        String username = "user";
        String id = "username-" + username + "-password-" + password + "-" + random.nextInt();
        StandardCredentials credentials =
                new UsernamePasswordCredentialsImpl(scope, username, password, id, "Credential description");
        remoteGit.addCredentials(url, credentials);
    }

    @Test
    void testSetCredentials() throws Exception {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String password = "password";
        String username = "user";
        String id = "username-" + username + "-password-" + password + "-" + random.nextInt();
        StandardUsernameCredentials credentials =
                new UsernamePasswordCredentialsImpl(scope, username, password, id, "Credential description");
        remoteGit.setCredentials(credentials);
    }

    @Test
    void testAddDefaultCredentials() throws Exception {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String password = "password";
        String username = "user";
        String id = "username-" + username + "-password-" + password + "-" + random.nextInt();
        StandardCredentials credentials =
                new UsernamePasswordCredentialsImpl(scope, username, password, id, "Credential description");
        remoteGit.addDefaultCredentials(credentials);
    }

    @Test
    void testSetAuthor_String_String() {
        String name = "charlie";
        String email = "charlie@example.com";
        remoteGit.setAuthor(name, email);
    }

    @Test
    void testSetAuthor_PersonIdent() {
        String name = "charlie";
        String email = "charlie@example.com";
        PersonIdent p = new PersonIdent(name, email);
        remoteGit.setAuthor(p);
    }

    @Test
    void testSetCommitter_String_String() {
        String name = "charlie";
        String email = "charlie@example.com";
        remoteGit.setCommitter(name, email);
    }

    @Test
    void testSetCommitter_PersonIdent() {
        String name = "charlie";
        String email = "charlie@example.com";
        PersonIdent p = new PersonIdent(name, email);
        remoteGit.setCommitter(p);
    }

    @Test
    void testGetWorkTree() {
        FilePath folderPath = new FilePath(localFolder);
        FilePath remoteFolderPath = remoteGit.getWorkTree();
        assertThat(remoteFolderPath, is(folderPath));
    }

    @Test
    void testInit() throws Exception {
        assertFalse(defaultClient.hasGitRepo(), "defaultClient has repo before init");
        remoteGit.init();
        assertTrue(defaultClient.hasGitRepo(), "defaultClient missing repo after init");
    }

    private void firstAdd(String fileName) throws Exception {
        File localFile = new File(localFolder, fileName);
        byte[] content = ("File " + fileName).getBytes();
        Files.write(localFile.toPath(), content);
        remoteGit.init();
        CliGitCommand gitCmd = new CliGitCommand(defaultClient);
        gitCmd.initializeRepository();
        remoteGit.add(fileName);
    }

    private ObjectId firstCommit(String fileName) throws Exception {
        firstAdd(fileName);
        CliGitCommand gitCmd = new CliGitCommand(defaultClient);
        gitCmd.run("config", "--local", "user.name", "Vojtěch remote Zweibrücken-Šafařík");
        gitCmd.run("config", "--local", "user.email", "email.from.git.remote.test@example.com");
        remoteGit.commit("Adding the " + fileName + " file");
        return remoteGit.revParse("HEAD");
    }

    @Test
    void testAddAndCommit() throws Exception {
        assertThat(firstCommit("testAddAndCommit"), is(not(nullValue())));
    }

    @Test
    void testCommit_3args() throws Exception {
        firstAdd("testCommit_3args_abc");
        String message = "Committing with authorName and commiterName";
        PersonIdent author = new PersonIdent("authorName", "authorEmail@example.com");
        PersonIdent committer = new PersonIdent("committerName", "committerEmail@example.com");
        remoteGit.commit(message, author, committer);
        ObjectId commit = remoteGit.revParse("HEAD");
        assertTrue(remoteGit.isCommitInRepo(commit), "Commit not in repo");
    }

    @Test
    void testHasGitRepo() throws Exception {
        assertFalse(remoteGit.hasGitRepo(), "remoteGit has repo before init");
        remoteGit.init();
        assertTrue(remoteGit.hasGitRepo(), "remoteGit missing repo after init");
    }

    @Test
    void testIsCommitInRepo() throws Exception {
        ObjectId commit = firstCommit("testIsCommitInRepo-abc");
        assertTrue(remoteGit.isCommitInRepo(commit), "Commit not in repo");
        ObjectId missingCommit = ObjectId.fromString("deededbeadedcededaddedbedded5ea6b842da60");
        assertFalse(remoteGit.isCommitInRepo(missingCommit), "Missing commit found in repo");
    }

    @Test
    void testGetRemoteUrl_String() throws Exception {
        String name = "originName";
        String url = "https://github.com/jenkinsci/git-client-plugin";
        remoteGit.init();
        if (gitImplName.equals("git")) { // JGit does not throw an exception for undefined remote
            assertThrows(GitException.class, () -> remoteGit.getRemoteUrl(name));
        }
        remoteGit.setRemoteUrl(name, url);
        assertThat(remoteGit.getRemoteUrl(name), is(url));
        remoteGit.addRemoteUrl(name + "2", url + "-2");
        assertThat(remoteGit.getRemoteUrl(name + "2"), is(url + "-2"));
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
