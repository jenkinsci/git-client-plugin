package org.jenkinsci.plugins.gitclient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.Fingerprint;
import hudson.util.LogTaskListener;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Test authenticated operations with the git implementations.
 * Uses contents of ~/.ssh/auth-data for parameterized tests.
 * @author Mark Waite
 */
@ParameterizedClass(name = "Impl:{0} User:{2} Pass:{3} Embed:{9} Phrase:{5} URL:{1}", allowZeroInvocations = true)
@MethodSource("gitRepoUrls")
@WithJenkins
class CredentialsTest {

    // Required for credentials use
    private static JenkinsRule r;

    @Parameter(0)
    private String gitImpl;

    @Parameter(1)
    private String gitRepoURL;

    @Parameter(2)
    private String username;

    @Parameter(3)
    private String password;

    @Parameter(4)
    private File privateKey;

    @Parameter(5)
    private String passphrase;

    @Parameter(6)
    private String fileToCheck;

    @Parameter(7)
    private Boolean submodules;

    @Parameter(8)
    private Boolean useParentCreds;

    @Parameter(9)
    private Boolean lfsSpecificTest;

    @Parameter(10)
    private Boolean credentialsEmbeddedInURL;

    private char specialCharacter;

    private GitClient git;
    private File repo;
    private StandardCredentials testedCredential;

    private final Random random = new Random();

    @TempDir
    private File tempFolder;

    private int logCount;
    private LogHandler handler;
    private LogTaskListener listener;

    private static final File HOME_DIR = new File(System.getProperty("user.home"));
    private static final File SSH_DIR = new File(HOME_DIR, ".ssh");
    private static final File DEFAULT_PRIVATE_KEY = new File(SSH_DIR, "id_rsa");

    /* Directory containing local private keys for tests */
    private static final File AUTH_DATA_DIR = new File(SSH_DIR, "auth-data");

    private static long firstTestStartTime = 0;

    /* Windows refuses directory names with '*', '<', '>', '|', '?', and ':' */
    private static final String SPECIALS_TO_CHECK = "%()`$&{}[]" + (isWindows() ? "" : "*<>:|?");
    private static int specialsIndex = 0;

    @BeforeAll
    static void setUp(JenkinsRule rule) {
        r = rule;
    }

    @BeforeEach
    void setUp() throws Exception {
        specialCharacter = SPECIALS_TO_CHECK.charAt(specialsIndex);
        specialsIndex = specialsIndex + 1;
        if (specialsIndex >= SPECIALS_TO_CHECK.length()) {
            specialsIndex = 0;
        }
        if (firstTestStartTime == 0) {
            firstTestStartTime = System.currentTimeMillis();
        }

        git = null;
        repo = newFolder(tempFolder, "junit");
        /* Use a repo with a special character in name - JENKINS-43931 */
        String newDirName = "use " + specialCharacter + " dir";
        File repoParent = repo;
        repo = new File(repoParent, newDirName);
        boolean dirCreated = repo.mkdirs();
        assertTrue(dirCreated, "Failed to create " + repo.getAbsolutePath());
        File repoTemp = new File(repoParent, newDirName + "@tmp"); // use adjacent temp directory
        dirCreated = repoTemp.mkdirs();
        assertTrue(dirCreated, "Failed to create " + repoTemp.getAbsolutePath());
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        git = Git.with(listener, new hudson.EnvVars()).in(repo).using(gitImpl).getClient();

        assertTrue(
                (password == null || password.isEmpty()) ^ (privateKey == null || !privateKey.exists()),
                "Bad username, password, privateKey combo: '" + username + "', '" + password + "'");
        if (password != null && !password.isEmpty()) {
            testedCredential = newUsernamePasswordCredential(username, password);
        }
        if (privateKey != null && privateKey.exists()) {
            testedCredential = newPrivateKeyCredential(username, privateKey);
        }
        if (!credentialsEmbeddedInURL) {
            assertThat(testedCredential, notNullValue());
            Fingerprint fingerprint = CredentialsProvider.getFingerprintOf(testedCredential);
            assertThat("Fingerprint should not be set", fingerprint, nullValue());
        }
    }

    @BeforeEach
    void enableSETSID() {
        CliGitAPIImpl.CALL_SETSID = gitImpl.equals("git") && privateKey != null && passphrase != null;
    }

    @AfterEach
    void checkFingerprintNotSet() throws Exception {
        /* Since these are API level tests, they should not track credential usage */
        /* Credential usage is tracked at the job / project level */
        Fingerprint fingerprint = CredentialsProvider.getFingerprintOf(testedCredential);
        assertThat("Fingerprint should not be set after API level use", fingerprint, nullValue());
    }

    @AfterEach
    void clearCredentials() {
        if (git != null) {
            git.clearCredentials();
        }
    }

    @AfterEach
    void disableSETSID() {
        org.jenkinsci.plugins.gitclient.CliGitAPIImpl.CALL_SETSID = false;
    }

    private BasicSSHUserPrivateKey newPrivateKeyCredential(String username, File privateKey) throws Exception {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String id = "private-key-" + privateKey.getPath() + random.nextInt();
        String privateKeyData = Files.readString(privateKey.toPath(), StandardCharsets.UTF_8);
        BasicSSHUserPrivateKey.PrivateKeySource privateKeySource =
                new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(privateKeyData);
        String description = "private key from " + privateKey.getPath();
        if (this.passphrase != null) {
            description = description + " passphrase '" + this.passphrase + "'";
        }
        return new BasicSSHUserPrivateKey(scope, id, username, privateKeySource, this.passphrase, description);
    }

    private StandardUsernamePasswordCredentials newUsernamePasswordCredential(String username, String password)
            throws Exception {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String id = "username-" + username + "-password-" + password + random.nextInt();
        return new UsernamePasswordCredentialsImpl(scope, id, "desc: " + id, username, password);
    }

    static List<Arguments> gitRepoUrls() throws Exception {
        List<Arguments> repos = new ArrayList<>();
        String[] implementations = new String[] {"git", "jgit", "jgitapache"};
        for (String implementation : implementations) {
            /* Add upstream repository as authentication test with private
             * key of current user.  Try to test at least one
             * authentication case, even if there is no repos.json file in
             * the external directory.
             */
            if (DEFAULT_PRIVATE_KEY.exists()) {
                String username = System.getProperty("user.name");
                String url = "https://github.com/jenkinsci/git-client-plugin.git";
                /* Add URL if it matches the pattern */
                if (URL_MUST_MATCH_PATTERN.matcher(url).matches()) {
                    Arguments masterRepo = Arguments.of(
                            implementation,
                            url,
                            username,
                            null,
                            DEFAULT_PRIVATE_KEY,
                            null,
                            "README.adoc",
                            false,
                            false,
                            false,
                            false);
                    repos.add(masterRepo);
                }
            }

            /* Add additional repositories if the ~/.ssh/auth-data directory
             * contains a repos.json file defining the repositories to test and the
             * authentication data to use for those tests.
             */
            File authDataDefinitions = new File(AUTH_DATA_DIR, "repos.json");
            if (authDataDefinitions.exists()) {
                JSONParser parser = new JSONParser();
                Object obj = parser.parse(new FileReader(authDataDefinitions));
                JSONArray authEntries = (JSONArray) obj;

                for (Object entryObj : authEntries) {
                    JSONObject entry = (JSONObject) entryObj;
                    String skipIf = (String) entry.get("skipif");
                    String repoURL = (String) entry.get("url");
                    String username = (String) entry.get("username");
                    String password = (String) entry.get("password");
                    String fileToCheck = (String) entry.get("file");
                    if (skipIf != null) {
                        if (skipIf.equals(implementation)) {
                            continue;
                        }
                        if (implementation.startsWith("jgit") && skipIf.equals("jgit")) { // Treat jgitapache like jgit
                            continue;
                        }
                    }

                    if (fileToCheck == null) {
                        fileToCheck = "README.adoc";
                    }

                    Boolean submodules = (Boolean) entry.get("submodules");
                    if (submodules == null) {
                        submodules = false;
                    }

                    Boolean useParentCreds = (Boolean) entry.get("parentcreds");
                    if (useParentCreds == null) {
                        useParentCreds = false;
                    }

                    Boolean lfsSpecificTest = (Boolean) entry.get("lfsSpecificTest");
                    if (lfsSpecificTest == null) {
                        lfsSpecificTest = false;
                    }

                    String keyfile = (String) entry.get("keyfile");
                    File privateKey = null;

                    if (keyfile != null) {
                        privateKey = new File(AUTH_DATA_DIR, keyfile);
                        if (!privateKey.exists()) {
                            privateKey = null;
                        }
                    }

                    String passphrase = (String) entry.get("passphrase");
                    if (passphrase != null && passphrase.trim().isEmpty()) {
                        passphrase = null;
                    }

                    if (passphrase != null && privateKey == null) {
                        System.out.println("Non-empty passphrase, private key file '" + keyfile + "' not found");
                        continue;
                    }

                    if (repoURL == null) {
                        System.out.println("No repository URL provided.");
                        continue;
                    }

                    /* Add URL if it matches the pattern */
                    if (URL_MUST_MATCH_PATTERN.matcher(repoURL).matches()) {
                        Arguments repo = Arguments.of(
                                implementation,
                                repoURL,
                                username,
                                password,
                                privateKey,
                                passphrase,
                                fileToCheck,
                                submodules,
                                useParentCreds,
                                false,
                                lfsSpecificTest);
                        repos.add(repo);
                        /* Add embedded credentials test case if valid username, valid password, CLI git, and http protocol */
                        if (username != null
                                && !username.matches(".*[@:].*")
                                && // Skip special cases of username
                                password != null
                                && !password.matches(".*[@:].*")
                                && // Skip special cases of password
                                implementation.equals("git")
                                && // Embedded credentials only implemented for CLI git
                                repoURL.startsWith("http")) {
                            /* Use existing username and password to create an embedded credentials test case */
                            String repoURLwithCredentials = repoURL.replaceAll(
                                    "(https?://)(.*@)?(.*)", "$1" + username + ":" + password + "@$3");
                            Arguments repoWithCredentials = Arguments.of(
                                    implementation,
                                    repoURLwithCredentials,
                                    username,
                                    password,
                                    privateKey,
                                    passphrase,
                                    fileToCheck,
                                    submodules,
                                    useParentCreds,
                                    true,
                                    lfsSpecificTest);
                            repos.add(0, repoWithCredentials);
                        }
                    }
                }
            }
        }
        Collections.shuffle(repos); // randomize test order
        // If we're not testing all credentials, take 6 or less
        return TEST_ALL_CREDENTIALS ? repos : repos.subList(0, Math.min(repos.size(), 6));
    }

    private void gitFetch(String source) throws Exception {
        gitFetch(source, "master", true);
    }

    private void gitFetch(String source, String branch, Boolean allowShallowClone) throws Exception {
        URIish sourceURI = new URIish(source);
        List<RefSpec> refSpecs = new ArrayList<>();
        refSpecs.add(new RefSpec("+refs/heads/" + branch + ":refs/remotes/origin/" + branch));
        FetchCommand cmd = git.fetch_().from(sourceURI, refSpecs).tags(false);
        // Reduce network transfer by using shallow clone
        cmd.shallow(true).depth(1);
        cmd.execute();
    }

    private String listDir(File dir) {
        File[] files = repo.listFiles();
        StringJoiner joiner = new StringJoiner(",");
        for (File file : files) {
            joiner.add(file.getName());
        }
        return joiner.toString();
    }

    private void addCredential() {
        // Begin - JENKINS-56257
        // Credential need not be added when supplied in the URL
        if (this.credentialsEmbeddedInURL) {
            return;
        }
        // End - JENKINS-56257
        // Always use addDefaultCredentials
        git.addDefaultCredentials(testedCredential);
        // addCredential stops tests to prompt for passphrase
        // addCredentials fails some github username / password tests
        // git.addCredentials(gitRepoURL, testedCredential);
    }

    /**
     * Returns true if another test should not be allowed to start.
     * JenkinsRule test timeout defaults to 180 seconds.
     *
     * @return true if another test should not be allowed to start
     */
    private boolean testPeriodExpired() {
        return (System.currentTimeMillis() - firstTestStartTime) > ((180 - 70) * 1000L);
    }

    @Test
    @Issue("JENKINS-50573")
    void testFetchWithCredentials() throws Exception {
        if (testPeriodExpired() || lfsSpecificTest) {
            return;
        }
        File clonedFile = new File(repo, fileToCheck);
        git.init_().workspace(repo.getAbsolutePath()).execute();
        assertFalse(clonedFile.exists(), "file " + fileToCheck + " in " + repo + ", has " + listDir(repo));
        addCredential();
        /* Fetch with remote URL */
        gitFetch(gitRepoURL);
        git.setRemoteUrl("origin", gitRepoURL);
        /* Fetch with remote name "origin" instead of remote URL */
        gitFetch("origin");
        ObjectId master = git.getHeadRev(gitRepoURL, "master");
        git.checkout()
                .branch("master")
                .ref(master.getName())
                .deleteBranchIfExist(true)
                .execute();
        if (submodules) {
            git.submoduleInit();
            SubmoduleUpdateCommand subcmd = git.submoduleUpdate().parentCredentials(useParentCreds);
            subcmd.execute();
        }
        assertTrue(git.isCommitInRepo(master), "master: " + master + " not in repo");
        assertEquals(
                master,
                git.withRepository(
                        (gitRepo, unusedChannel) -> gitRepo.findRef("master").getObjectId()),
                "Master != HEAD");
        assertEquals("master", git.withRepository((gitRepo, unusedChanel) -> gitRepo.getBranch()), "Wrong branch");
        assertTrue(clonedFile.exists(), "No file " + fileToCheck + ", has " + listDir(repo));
        /* prune opens a remote connection to list remote branches */
        git.prune(new RemoteConfig(git.withRepository((gitRepo, unusedChannel) -> gitRepo.getConfig()), "origin"));
    }

    @Test
    void testRemoteReferencesWithCredentials() throws Exception {
        if (testPeriodExpired()) {
            return;
        }
        addCredential();
        Map<String, ObjectId> remoteReferences =
                switch (random.nextInt(4)) {
                    case 1 -> git.getRemoteReferences(gitRepoURL, null, true, true);
                    case 2 -> git.getRemoteReferences(gitRepoURL, "master", true, false);
                    case 3 -> git.getRemoteReferences(gitRepoURL, "master", true, true);
                    default -> git.getRemoteReferences(gitRepoURL, null, true, false);
                };
        assertThat(remoteReferences.keySet(), hasItems("refs/heads/master"));
    }

    @Test
    @Issue("JENKINS-50573")
    void isURIishRemote() throws Exception {
        URIish uri = new URIish(gitRepoURL);
        assertTrue(uri.isRemote(), "Should be remote but isn't: " + uri);
    }

    @Test
    @Issue("JENKINS-45228")
    void testLfsMergeWithCredentials() throws Exception {
        if (testPeriodExpired() || !lfsSpecificTest) {
            return;
        }
        File clonedFile = new File(repo, fileToCheck);
        git.init_().workspace(repo.getAbsolutePath()).execute();
        assertFalse(clonedFile.exists(), "file " + fileToCheck + " in " + repo + ", has " + listDir(repo));
        addCredential();

        /* Fetch with remote name "origin" instead of remote URL */
        git.setRemoteUrl("origin", gitRepoURL);
        gitFetch("origin", "*", false);
        ObjectId master = git.getHeadRev(gitRepoURL, "master");
        git.checkout()
                .branch("master")
                .ref(master.getName())
                .lfsRemote("origin")
                .deleteBranchIfExist(true)
                .execute();
        assertTrue(git.isCommitInRepo(master), "master: " + master + " not in repo");
        assertEquals(master, git.getRepository().findRef("master").getObjectId(), "Master != HEAD");
        assertEquals("master", git.getRepository().getBranch(), "Wrong branch");
        assertTrue(clonedFile.exists(), "No file " + fileToCheck + ", has " + listDir(repo));

        ObjectId modified_lfs = git.getHeadRev(gitRepoURL, "modified_lfs");
        git.merge()
                .setStrategy(MergeCommand.Strategy.DEFAULT)
                .setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF)
                .setRevisionToMerge(modified_lfs)
                .execute();
        assertEquals(
                git.revParse("HEAD"),
                modified_lfs,
                "Fast-forward merge failed. master and modified_lfs should be the same.");
    }

    private static boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }

    /* If not in a Jenkins job, then default to run all credentials tests.
     *
     * Developers without ~/.ssh/auth-data/repos.json will see no difference
     * since minimal credentials tests are used for them.
     *
     * Developers with ~/.ssh/auth-data/repos.json will test all credentials by default.
     */
    private static final String NOT_JENKINS = System.getProperty("JOB_NAME") == null ? "true" : "false";
    private static final boolean TEST_ALL_CREDENTIALS =
            Boolean.parseBoolean(System.getProperty("TEST_ALL_CREDENTIALS", NOT_JENKINS));
    private static final Pattern URL_MUST_MATCH_PATTERN =
            Pattern.compile(System.getProperty("URL_MUST_MATCH_PATTERN", ".*"));

    private static File newFolder(File root, String... subDirs) throws Exception {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + result);
        }
        return result;
    }
}
