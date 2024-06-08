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
import com.github.sparsick.testcontainers.gitserver.http.BasicAuthenticationCredentials;
import com.github.sparsick.testcontainers.gitserver.http.GitHttpServerContainer;
import com.github.sparsick.testcontainers.gitserver.plain.GitServerContainer;
import com.github.sparsick.testcontainers.gitserver.plain.SshIdentity;
import hudson.EnvVars;
import hudson.model.TaskListener;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.verifier.NoHostKeyVerifier;
import org.jetbrains.annotations.NotNull;
import org.junit.Assume;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * <p>This class is testing authz using jgit implementation against</p>
 * <ul>
 *     <li>git ssh server using private key</li>
 *     <li>git ssh server using password</li>
 *     <li>git http server using password</li>
 * </ul>
 *
 */
public class AuthzTest {

    @Test
    public void sshKeyAuth() throws Exception {
        Assume.assumeTrue(DockerClientFactory.instance().isDockerAvailable());
        try (GitServerContainer containerUnderTest = new GitServerContainer(
                        GitServerVersions.V2_45.getDockerImageName())
                .withGitRepo("someRepo")
                .withGitPassword("FrenchCheeseRocks!1234567") // very complicated password
                .withSshKeyAuth()) {
            containerUnderTest.start();
            SshIdentity sshIdentity = containerUnderTest.getSshClientIdentity();
            BasicSSHUserPrivateKey sshUserPrivateKey = getBasicSSHUserPrivateKey(sshIdentity);
            testRepo(sshUserPrivateKey, containerUnderTest);
        }
    }

    @Test
    public void sshWithPassword() throws Exception {
        Assume.assumeTrue(DockerClientFactory.instance().isDockerAvailable());
        try (GitServerContainer containerUnderTest = new GitServerContainer(
                        GitServerVersions.V2_45.getDockerImageName())
                .withGitRepo("someRepo")
                .withGitPassword("FrenchCheeseRocks!1234567") // very complicated password
                .withSshKeyAuth()) {
            containerUnderTest.start();
            StandardCredentials standardCredentials = new UsernamePasswordCredentialsImpl(
                    CredentialsScope.GLOBAL,
                    "username-password",
                    "description",
                    "",
                    containerUnderTest.getGitPassword());
            testRepo(standardCredentials, containerUnderTest);
        }
    }

    @Test
    public void httpWithPassword() throws Exception {
        Assume.assumeTrue(DockerClientFactory.instance().isDockerAvailable());
        BasicAuthenticationCredentials credentials = new BasicAuthenticationCredentials("testuser", "testPassword");
        try (GitHttpServerContainer containerUnderTest =
                new GitHttpServerContainer(GitServerVersions.V2_45.getDockerImageName(), credentials)) {
            containerUnderTest.start();
            StandardCredentials standardCredentials = new UsernamePasswordCredentialsImpl(
                    CredentialsScope.GLOBAL,
                    "username-password",
                    "description",
                    containerUnderTest.getBasicAuthCredentials().getUsername(),
                    containerUnderTest.getBasicAuthCredentials().getPassword());
            testRepo(standardCredentials, containerUnderTest);
        }
    }

    protected void testRepo(StandardCredentials standardCredentials, GenericContainer<?> containerUnderTest)
            throws Exception {
        String repoUrl = null;
        if (containerUnderTest instanceof GitServerContainer) {
            repoUrl = ((GitServerContainer) containerUnderTest)
                    .getGitRepoURIAsSSH()
                    .toString();
        }
        if (containerUnderTest instanceof GitHttpServerContainer) {
            repoUrl = ((GitHttpServerContainer) containerUnderTest)
                    .getGitRepoURIAsHttp()
                    .toString();
        }
        Path testRepo = Files.createTempDirectory("git-client-test");
        try {
            GitClient client = buildClient(testRepo, standardCredentials, repoUrl);
            Map<String, ObjectId> rev = client.getHeadRev(repoUrl);
            assertThat(rev, is(anEmptyMap()));
            client.clone(repoUrl, "master", false, null);
            client.config(GitClient.ConfigLevel.LOCAL, "user.name", "Someone");
            client.config(GitClient.ConfigLevel.LOCAL, "user.email", "someone@beer.com");
            client.config(GitClient.ConfigLevel.LOCAL, "commit.gpgsign", "false");
            client.config(GitClient.ConfigLevel.LOCAL, "tag.gpgSign", "false");
            client.config(GitClient.ConfigLevel.LOCAL, "gpg.format", "openpgp");
            Path testFile = testRepo.resolve("test.txt");
            Files.deleteIfExists(testFile);
            Files.createFile(testFile);
            Files.writeString(
                    testFile,
                    "Hello",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            client.add("test*");
            client.commit("Very useful change");
            client.push().to(new URIish(repoUrl)).execute();
        } finally {
            FileUtils.deleteDirectory(testRepo.toFile());
        }
        try {
            testRepo = Files.createTempDirectory("git-client-test");
            GitClient client = buildClient(testRepo, standardCredentials, repoUrl);
            Map<String, ObjectId> rev = client.getHeadRev(repoUrl);
            // check there is now one ref remotely after the push
            assertThat(rev, aMapWithSize(1));
        } finally {
            FileUtils.deleteDirectory(testRepo.toFile());
        }
    }

    protected GitClient buildClient(Path repo, StandardCredentials standardCredentials, String url) throws Exception {
        GitClient client = Git.with(TaskListener.NULL, new EnvVars())
                .using("jgit")
                .withHostKeyVerifierFactory(new NoHostKeyVerifier())
                .in(repo.toFile())
                .getClient();
        client.addCredentials(url, standardCredentials);
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