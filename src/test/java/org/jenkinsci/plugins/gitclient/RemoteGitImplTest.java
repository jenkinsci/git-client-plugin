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
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RemoteGitImplTest {

    public RemoteGitImplTest() {
    }

    @Rule
    public TemporaryFolder temporaryFolderRule = new TemporaryFolder();

    private File localFolder;
    private GitClient defaultClient;
    private RemoteGitImpl remoteGit;
    private String gitImplName;

    private java.util.Random random = new java.util.Random();

    @Before
    public void setUp() throws IOException, InterruptedException {
        /* Use a randomly selected git implementation in hopes of detecting more issues with fewer tests */
        String[] gitImplAlternatives = { "git", "jgit", "jgitapache" };
        gitImplName = gitImplAlternatives[random.nextInt(gitImplAlternatives.length)];
        localFolder = temporaryFolderRule.newFolder();
        defaultClient = Git.with(TaskListener.NULL, new EnvVars()).in(localFolder).using(gitImplName).getClient();
        remoteGit = new RemoteGitImpl(defaultClient);
    }

    @Test
    public void testGetRepository() throws IOException, InterruptedException {
        assertThrows(UnsupportedOperationException.class, () -> {
            remoteGit.getRepository();
        });
    }

    @Test
    public void testClearCredentials() throws IOException, InterruptedException {
        remoteGit.clearCredentials();
    }

    @Test
    public void testAddCredentials() {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String password = "password";
        String url = "https://github.com/jenkinsci/git-client-plugin";
        String username = "user";
        String id = "username-" + username + "-password-" + password + "-" + random.nextInt();
        StandardCredentials credentials = new UsernamePasswordCredentialsImpl(scope, username, password, id, "Credential description");
        remoteGit.addCredentials(url, credentials);
    }

    @Test
    public void testSetCredentials() {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String password = "password";
        String url = "https://github.com/jenkinsci/git-client-plugin";
        String username = "user";
        String id = "username-" + username + "-password-" + password + "-" + random.nextInt();
        StandardUsernameCredentials credentials = new UsernamePasswordCredentialsImpl(scope, username, password, id, "Credential description");
        remoteGit.setCredentials(credentials);
    }

    @Test
    public void testAddDefaultCredentials() {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String password = "password";
        String url = "https://github.com/jenkinsci/git-client-plugin";
        String username = "user";
        String id = "username-" + username + "-password-" + password + "-" + random.nextInt();
        StandardCredentials credentials = new UsernamePasswordCredentialsImpl(scope, username, password, id, "Credential description");
        remoteGit.addDefaultCredentials(credentials);
    }

    @Test
    public void testSetAuthor_String_String() {
        String name = "charlie";
        String email = "charlie@example.com";
        remoteGit.setAuthor(name, email);
    }

    @Test
    public void testSetAuthor_PersonIdent() {
        String name = "charlie";
        String email = "charlie@example.com";
        PersonIdent p = new PersonIdent(name, email);
        remoteGit.setAuthor(p);
    }

    @Test
    public void testSetCommitter_String_String() {
        String name = "charlie";
        String email = "charlie@example.com";
        remoteGit.setCommitter(name, email);
    }

    @Test
    public void testSetCommitter_PersonIdent() {
        String name = "charlie";
        String email = "charlie@example.com";
        PersonIdent p = new PersonIdent(name, email);
        remoteGit.setCommitter(p);
    }

    @Test
    public void testGetWorkTree() {
        FilePath folderPath = new FilePath(localFolder);
        FilePath remoteFolderPath = remoteGit.getWorkTree();
        assertThat(remoteFolderPath, is(folderPath));
    }

    @Test
    public void testInit() throws Exception {
        assertFalse("defaultClient has repo before init", defaultClient.hasGitRepo());
        remoteGit.init();
        assertTrue("defaultClient missing repo after init", defaultClient.hasGitRepo());
    }

    private void firstAdd(String fileName) throws Exception {
        File localFile = new File(localFolder, fileName);
        byte [] content = ("File " + fileName).getBytes();
        Files.write(localFile.toPath(), content);
        remoteGit.init();
        remoteGit.add(fileName);
    }

    private ObjectId firstCommit(String fileName) throws Exception {
        firstAdd(fileName);
        CliGitCommand gitCmd = new CliGitCommand(defaultClient);
        gitCmd.run("config", "user.name", "Vojtěch remote Zweibrücken-Šafařík");
        gitCmd.run("config", "user.email", "email.from.git.remote.test@example.com");
        remoteGit.commit("Adding the " + fileName + " file");
        return remoteGit.revParse("HEAD");
    }

    @Test
    public void testAddAndCommit() throws Exception {
        assertThat(firstCommit("testAddAndCommit"), is(not(nullValue())));
    }

    @Test
    public void testCommit_3args() throws Exception {
        firstAdd("testCommit_3args_abc");
        String message = "Committing with authorName and commiterName";
        PersonIdent author = new PersonIdent("authorName", "authorEmail@example.com");
        PersonIdent committer = new PersonIdent("committerName", "committerEmail@example.com");
        remoteGit.commit(message, author, committer);
        ObjectId commit = remoteGit.revParse("HEAD");
        assertTrue("Commit not in repo", remoteGit.isCommitInRepo(commit));
    }

    @Test
    public void testHasGitRepo() throws Exception {
        assertFalse("remoteGit has repo before init", remoteGit.hasGitRepo());
        remoteGit.init();
        assertTrue("remoteGit missing repo after init", remoteGit.hasGitRepo());
    }

    @Test
    public void testIsCommitInRepo() throws Exception {
        ObjectId commit = firstCommit("testIsCommitInRepo-abc");
        assertTrue("Commit not in repo", remoteGit.isCommitInRepo(commit));
        ObjectId missingCommit = ObjectId.fromString("deededbeadedcededaddedbedded5ea6b842da60");
        assertFalse("Missing commit found in repo", remoteGit.isCommitInRepo(missingCommit));
    }

    @Test
    public void testGetRemoteUrl_String() throws Exception {
        String name = "originName";
        String url = "https://github.com/jenkinsci/git-client-plugin";
        remoteGit.init();
        if (gitImplName.equals("git")) { // JGit does not throw an exception for undefined remote
            assertThrows(GitException.class, () -> {
                    remoteGit.getRemoteUrl(name);
                });
        }
        remoteGit.setRemoteUrl(name, url);
        assertThat(remoteGit.getRemoteUrl(name), is(url));
        remoteGit.addRemoteUrl(name + "2", url + "-2");
        assertThat(remoteGit.getRemoteUrl(name + "2"), is(url + "-2"));
    }
}
