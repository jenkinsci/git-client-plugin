package org.jenkinsci.plugins.gitclient;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.common.io.Files;
import hudson.model.Fingerprint;
import hudson.util.LogTaskListener;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
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
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.junit.ClassRule;

/**
 * Test authenticated operations with the git implementations.
 * Uses contents of ~/.ssh/auth-data for parameterized tests.
 * @author Mark Waite
 */
@RunWith(Parameterized.class)
public class CredentialsTest {

    // Required for credentials use
    @ClassRule
    public static final JenkinsRule j = new JenkinsRule();

    private final String gitImpl;
    private final String gitRepoURL;
    private final String username;
    private final String password;
    private final File privateKey;
    private final String passphrase;
    private final String fileToCheck;
    private final Boolean submodules;
    private final Boolean useParentCreds;
    private final Boolean lfsSpecificTest;
    private final char specialCharacter;
    private final Boolean credentialsEmbeddedInURL;

    private GitClient git;
    private File repo;
    private StandardCredentials testedCredential;

    private final Random random = new Random();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private int logCount;
    private LogHandler handler;
    private LogTaskListener listener;

    private final static File HOME_DIR = new File(System.getProperty("user.home"));
    private final static File SSH_DIR = new File(HOME_DIR, ".ssh");
    private final static File DEFAULT_PRIVATE_KEY = new File(SSH_DIR, "id_rsa");

    /* Directory containing local private keys for tests */
    private final static File AUTH_DATA_DIR = new File(SSH_DIR, "auth-data");

    private final static File CURR_DIR = new File(".");

    private static long firstTestStartTime = 0;

    /* Windows refuses directory names with '*', '<', '>', '|', '?', and ':' */
    private final String SPECIALS_TO_CHECK = "%()`$&{}[]"
            + (isWindows() ? "" : "*<>:|?");
    private static int specialsIndex = 0;

    public CredentialsTest(String gitImpl, String gitRepoUrl, String username, String password, File privateKey, String passphrase, String fileToCheck, Boolean submodules, Boolean useParentCreds, Boolean credentialsEmbeddedInURL, Boolean lfsSpecificTest) {
        this.gitImpl = gitImpl;
        this.gitRepoURL = gitRepoUrl;
        this.privateKey = privateKey;
        this.passphrase = passphrase;
        this.username = username;
        this.password = password;
        this.fileToCheck = fileToCheck;
        this.submodules = submodules;
        this.useParentCreds = useParentCreds;
        this.lfsSpecificTest = lfsSpecificTest;
        this.specialCharacter = SPECIALS_TO_CHECK.charAt(specialsIndex);
        this.credentialsEmbeddedInURL = credentialsEmbeddedInURL;
        specialsIndex = specialsIndex + 1;
        if (specialsIndex >= SPECIALS_TO_CHECK.length()) {
            specialsIndex = 0;
        }
        if (firstTestStartTime == 0) {
            firstTestStartTime = System.currentTimeMillis();
        }
    }

    @Before
    public void setUp() throws IOException, InterruptedException {
        git = null;
        repo = tempFolder.newFolder();
        /* Use a repo with a special character in name - JENKINS-43931 */
        String newDirName = "use " + specialCharacter + " dir";
        File repoParent = repo;
        repo = new File(repoParent, newDirName);
        boolean dirCreated = repo.mkdirs();
        assertTrue("Failed to create " + repo.getAbsolutePath(), dirCreated);
        File repoTemp = new File(repoParent, newDirName + "@tmp"); // use adjacent temp directory
        dirCreated = repoTemp.mkdirs();
        assertTrue("Failed to create " + repoTemp.getAbsolutePath(), dirCreated);
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        git = Git.with(listener, new hudson.EnvVars()).in(repo).using(gitImpl).getClient();

        assertTrue("Bad username, password, privateKey combo: '" + username + "', '" + password + "'",
                (password == null || password.isEmpty()) ^ (privateKey == null || !privateKey.exists()));
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

    @Before
    public void enableSETSID() throws IOException, InterruptedException {
        if (gitImpl.equals("git") && privateKey != null && passphrase != null) {
            org.jenkinsci.plugins.gitclient.CliGitAPIImpl.CALL_SETSID = true;
        } else {
            org.jenkinsci.plugins.gitclient.CliGitAPIImpl.CALL_SETSID = false;
        }
    }

    @After
    public void checkFingerprintNotSet() throws Exception {
        /* Since these are API level tests, they should not track credential usage */
        /* Credential usage is tracked at the job / project level */
        Fingerprint fingerprint = CredentialsProvider.getFingerprintOf(testedCredential);
        assertThat("Fingerprint should not be set after API level use", fingerprint, nullValue());
    }

    @After
    public void clearCredentials() {
        if (git != null) {
            git.clearCredentials();
        }
    }

    @After
    public void disableSETSID() throws IOException, InterruptedException {
        org.jenkinsci.plugins.gitclient.CliGitAPIImpl.CALL_SETSID = false;
    }

    private BasicSSHUserPrivateKey newPrivateKeyCredential(String username, File privateKey) throws IOException {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String id = "private-key-" + privateKey.getPath() + random.nextInt();
        String privateKeyData = Files.toString(privateKey, Charset.forName("UTF-8"));
        BasicSSHUserPrivateKey.PrivateKeySource privateKeySource = new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(privateKeyData);
        String description = "private key from " + privateKey.getPath();
        if (this.passphrase != null) {
            description = description + " passphrase '" + this.passphrase + "'";
        }
        return new BasicSSHUserPrivateKey(scope, id, username, privateKeySource, this.passphrase, description);
    }

    private StandardUsernamePasswordCredentials newUsernamePasswordCredential(String username, String password) {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String id = "username-" + username + "-password-" + password + random.nextInt();
        return new UsernamePasswordCredentialsImpl(scope, id, "desc: " + id, username, password);
    }

    private static boolean isCredentialsSupported() throws IOException, InterruptedException {
        CliGitAPIImpl cli = (CliGitAPIImpl) Git.with(null, new hudson.EnvVars()).in(CURR_DIR).using("git").getClient();
        return cli.isAtLeastVersion(1, 7, 9, 0);
    }

    private boolean isShallowCloneSupported(String implementation, GitClient gitClient) throws IOException, InterruptedException {
        if (!implementation.equals("git")) {
            return false;
        }
        CliGitAPIImpl cli = (CliGitAPIImpl) gitClient;
        return cli.isAtLeastVersion(1, 9, 0, 0);
    }

    @Parameterized.Parameters(name = "Impl:{0} User:{2} Pass:{3} Embed:{9} Phrase:{5} URL:{1}")
    public static Collection gitRepoUrls() throws MalformedURLException, FileNotFoundException, IOException, InterruptedException, ParseException {
        List<Object[]> repos = new ArrayList<>();
        String[] implementations = isCredentialsSupported() ? new String[]{"git", "jgit", "jgitapache"} : new String[]{"jgit", "jgitapache"};
        for (String implementation : implementations) {
            /* Add master repository as authentication test with private
             * key of current user.  Try to test at least one
             * authentication case, even if there is no repos.json file in
             * the external directory.
             */
            if (DEFAULT_PRIVATE_KEY.exists()) {
                String username = System.getProperty("user.name");
                String url = "https://github.com/jenkinsci/git-client-plugin.git";
                /* Add URL if it matches the pattern */
                if (URL_MUST_MATCH_PATTERN.matcher(url).matches()) {
                    Object[] masterRepo = {implementation, url, username, null, DEFAULT_PRIVATE_KEY, null, "README.adoc", false, false, false, false};
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
                        Object[] repo = {implementation, repoURL, username, password, privateKey, passphrase, fileToCheck, submodules, useParentCreds, false, lfsSpecificTest};
                        repos.add(repo);
                        /* Add embedded credentials test case if valid username, valid password, CLI git, and http protocol */
                        if (username != null && !username.matches(".*[@:].*") && // Skip special cases of username
                            password != null && !password.matches(".*[@:].*") && // Skip special cases of password
                            implementation.equals("git")                      && // Embedded credentials only implemented for CLI git
                            repoURL.startsWith("http")) {
                            /* Use existing username and password to create an embedded credentials test case */
                            String repoURLwithCredentials = repoURL.replaceAll("(https?://)(.*@)?(.*)", "$1" + username + ":" + password + "@$3");
                            Object[] repoWithCredentials = {implementation, repoURLwithCredentials, username, password, privateKey, passphrase, fileToCheck, submodules, useParentCreds, true, lfsSpecificTest};
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
        /* Save some bandwidth with shallow clone for CliGit, not yet available for JGit */
        URIish sourceURI = new URIish(source);
        List<RefSpec> refSpecs = new ArrayList<>();
        refSpecs.add(new RefSpec("+refs/heads/"+branch+":refs/remotes/origin/"+branch+""));
        FetchCommand cmd = git.fetch_().from(sourceURI, refSpecs).tags(false);
        if (isShallowCloneSupported(gitImpl, git)) {
            // Reduce network transfer by using shallow clone
            // JGit does not support shallow clone
            cmd.shallow(true).depth(1);
        }
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

    private void addCredential() throws IOException {
        //Begin - JENKINS-56257
        //Credential need not be added when supplied in the URL
        if (this.credentialsEmbeddedInURL) {
            return;
        }
        //End - JENKINS-56257
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
    public void testFetchWithCredentials() throws Exception {
        if (testPeriodExpired() || lfsSpecificTest) {
            return;
        }
        File clonedFile = new File(repo, fileToCheck);
        git.init_().workspace(repo.getAbsolutePath()).execute();
        assertFalse("file " + fileToCheck + " in " + repo + ", has " + listDir(repo), clonedFile.exists());
        addCredential();
        /* Fetch with remote URL */
        gitFetch(gitRepoURL);
        git.setRemoteUrl("origin", gitRepoURL);
        /* Fetch with remote name "origin" instead of remote URL */
        gitFetch("origin");
        ObjectId master = git.getHeadRev(gitRepoURL, "master");
        git.checkout().branch("master").ref(master.getName()).deleteBranchIfExist(true).execute();
        if (submodules) {
            git.submoduleInit();
            SubmoduleUpdateCommand subcmd = git.submoduleUpdate().parentCredentials(useParentCreds);
            subcmd.execute();
        }
        assertTrue("master: " + master + " not in repo", git.isCommitInRepo(master));
        assertEquals("Master != HEAD", master, git.withRepository((gitRepo, unusedChannel)-> gitRepo.findRef("master").getObjectId()));
        assertEquals("Wrong branch", "master", git.withRepository((gitRepo, unusedChanel) -> gitRepo.getBranch()));
        assertTrue("No file " + fileToCheck + ", has " + listDir(repo), clonedFile.exists());
        /* prune opens a remote connection to list remote branches */
        git.prune(new RemoteConfig(git.withRepository((gitRepo, unusedChannel) -> gitRepo.getConfig()), "origin"));
    }

    @Test
    public void testRemoteReferencesWithCredentials() throws Exception {
        if (testPeriodExpired()) {
            return;
        }
        addCredential();
        Map<String, ObjectId> remoteReferences;
        switch (random.nextInt(4)) {
            default:
            case 0:
                remoteReferences = git.getRemoteReferences(gitRepoURL, null, true, false);
                break;
            case 1:
                remoteReferences = git.getRemoteReferences(gitRepoURL, null, true, true);
                break;
            case 2:
                remoteReferences = git.getRemoteReferences(gitRepoURL, "master", true, false);
                break;
            case 3:
                remoteReferences = git.getRemoteReferences(gitRepoURL, "master", true, true);
                break;
        }
        assertThat(remoteReferences.keySet(), hasItems("refs/heads/master"));
    }

    @Test
    @Issue("JENKINS-50573")
    public void isURIishRemote() throws Exception {
        URIish uri = new URIish(gitRepoURL);
        assertTrue("Should be remote but isn't: " + uri, uri.isRemote());
    }

    @Test
    @Issue("JENKINS-45228")
    public void testLfsMergeWithCredentials() throws Exception {
        if (testPeriodExpired() || !lfsSpecificTest) {
            return;
        }
        File clonedFile = new File(repo, fileToCheck);
        git.init_().workspace(repo.getAbsolutePath()).execute();
        assertFalse("file " + fileToCheck + " in " + repo + ", has " + listDir(repo), clonedFile.exists());
        addCredential();

        /* Fetch with remote name "origin" instead of remote URL */
        git.setRemoteUrl("origin", gitRepoURL);
        gitFetch("origin", "*", false);
        ObjectId master = git.getHeadRev(gitRepoURL, "master");
        git.checkout().branch("master").ref(master.getName()).lfsRemote("origin").deleteBranchIfExist(true).execute();
        assertTrue("master: " + master + " not in repo", git.isCommitInRepo(master));
        assertEquals("Master != HEAD", master, git.getRepository().findRef("master").getObjectId());
        assertEquals("Wrong branch", "master", git.getRepository().getBranch());
        assertTrue("No file " + fileToCheck + ", has " + listDir(repo), clonedFile.exists());

        ObjectId modified_lfs = git.getHeadRev(gitRepoURL, "modified_lfs");
        git.merge().setStrategy(MergeCommand.Strategy.DEFAULT).setGitPluginFastForwardMode(MergeCommand.GitPluginFastForwardMode.FF).setRevisionToMerge(modified_lfs).execute();
        assertEquals("Fast-forward merge failed. master and modified_lfs should be the same.", git.revParse("HEAD"), modified_lfs);
    }

    private boolean isWindows() {
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
    private static final boolean TEST_ALL_CREDENTIALS = Boolean.valueOf(System.getProperty("TEST_ALL_CREDENTIALS", NOT_JENKINS));
    private static final Pattern URL_MUST_MATCH_PATTERN = Pattern.compile(System.getProperty("URL_MUST_MATCH_PATTERN", ".*"));
}
