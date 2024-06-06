package org.jenkinsci.plugins.gitclient.jgit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.collection.IsMapWithSize.aMapWithSize;
import static org.hamcrest.collection.IsMapWithSize.anEmptyMap;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.github.sparsick.testcontainers.gitserver.GitServerVersions;
import com.github.sparsick.testcontainers.gitserver.plain.GitServerContainer;
import com.github.sparsick.testcontainers.gitserver.plain.SshIdentity;
import hudson.EnvVars;
import hudson.model.TaskListener;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.verifier.NoHostKeyVerifier;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AuthzSshTest {

    private GitServerContainer containerUnderTest = new GitServerContainer(GitServerVersions.V2_45.getDockerImageName())
            .withGitRepo("someRepo")
            .withGitPassword("FrenchCheeseRocks!1234567") // very complicated password
            // .withCopyExistingGitRepoToContainer("target/test-classes/git-repository")
            .withSshKeyAuth();

    @Before
    public void setup() throws Exception {
        containerUnderTest.start();
    }

    @After
    public void cleanup() throws Exception {
        containerUnderTest.stop();
    }

    @Test
    public void sshKeyAuth() throws Exception {
        SshIdentity sshIdentity = containerUnderTest.getSshClientIdentity();
        BasicSSHUserPrivateKey sshUserPrivateKey = getBasicSSHUserPrivateKey(sshIdentity);
        testRepo(sshUserPrivateKey);
    }

    @Test
    public void sshWithPassword() throws Exception {
        SshIdentity sshIdentity = containerUnderTest.getSshClientIdentity();
        StandardCredentials standardCredentials = new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, "username-password", "description", "", containerUnderTest.getGitPassword());
        testRepo(standardCredentials);
    }

    protected void testRepo(StandardCredentials standardCredentials) throws Exception {
        Path testRepo = Files.createTempDirectory("git-client-test");
        GitClient client = buildClient(testRepo, standardCredentials);
        Map<String, ObjectId> rev =
                client.getHeadRev(containerUnderTest.getGitRepoURIAsSSH().toString());
        assertThat(rev, is(anEmptyMap()));
        client.clone(containerUnderTest.getGitRepoURIAsSSH().toString(), "master", false, null);
        client.config(GitClient.ConfigLevel.LOCAL, "user.name", "Someone");
        client.config(GitClient.ConfigLevel.LOCAL, "user.email", "someone@beer.com");
        client.config(GitClient.ConfigLevel.LOCAL, "commit.gpgsign", "false");
        client.config(GitClient.ConfigLevel.LOCAL, "tag.gpgSign", "false");
        Path testFile = testRepo.resolve("test.txt");
        Files.deleteIfExists(testFile);
        Files.createFile(testFile);
        Files.write(testFile, "Hello".getBytes(StandardCharsets.UTF_8));
        client.add("test*");
        client.commit("Very usefull commit");
        client.push()
                .to(new URIish(containerUnderTest.getGitRepoURIAsSSH().toString()))
                .execute();
        testRepo = Files.createTempDirectory("git-client-test");
        client = buildClient(testRepo, standardCredentials);
        rev = client.getHeadRev(containerUnderTest.getGitRepoURIAsSSH().toString());
        assertThat(rev, aMapWithSize(1));
    }

    protected GitClient buildClient(Path repo, StandardCredentials standardCredentials) throws Exception {
        GitClient client = Git.with(TaskListener.NULL, new EnvVars())
                .using("jgit")
                .withHostKeyVerifierFactory(new NoHostKeyVerifier())
                .in(repo.toFile())
                .getClient();
        client.addDefaultCredentials(standardCredentials);
        return client;
    }

    private static @NotNull BasicSSHUserPrivateKey getBasicSSHUserPrivateKey(SshIdentity sshIdentity) {
        BasicSSHUserPrivateKey.PrivateKeySource privateKeySource = new BasicSSHUserPrivateKey.PrivateKeySource() {
            @NotNull
            @Override
            public List<String> getPrivateKeys() {
                return List.of(new String(sshIdentity.getPrivateKey()));
            }
        };
        return new BasicSSHUserPrivateKey(
                CredentialsScope.GLOBAL,
                "some-id",
                "?",
                privateKeySource,
                new String(sshIdentity.getPassphrase()),
                "description");
    }
}
