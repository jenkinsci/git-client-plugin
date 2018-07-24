package org.jenkinsci.plugins.gitclient;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.common.io.Files;
import hudson.model.Fingerprint;
import hudson.plugins.git.GitException;
import hudson.util.LogTaskListener;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import static org.hamcrest.Matchers.*;
import org.junit.After;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.JenkinsRule;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.junit.ClassRule;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

/**
 *
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
    private final char specialCharacter;

    private GitClient git;
    private File repo;
    private StandardCredentials testedCredential;

    private final Random random = new Random();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Rule
    public TestRule timeout = new DisableOnDebug(Timeout.seconds(17));

    private int logCount;
    private LogHandler handler;
    private LogTaskListener listener;
    private static final String LOGGING_STARTED = "*** Logging started ***";

    private final static File HOME_DIR = new File(System.getProperty("user.home"));
    private final static File SSH_DIR = new File(HOME_DIR, ".ssh");
    private final static File DEFAULT_PRIVATE_KEY = new File(SSH_DIR, "id_rsa");

    /* Directory containing local private keys for tests */
    private final static File AUTH_DATA_DIR = new File(SSH_DIR, "auth-data");

    private final static File CURR_DIR = new File(".");

    private static long firstTestStartTime = 0;

    private static PrintStream log() {
        return StreamTaskListener.fromStdout().getLogger();
    }

    /* Windows refuses directory names with '*', '<', '>', '|', '?', and ':' */
    private final String SPECIALS_TO_CHECK = "%()`$&{}[]"
            + (isWindows() ? "" : "*<>:|?");
    private static int specialsIndex = 0;

    public CredentialsTest(String gitImpl, String gitRepoUrl, String username, String password, File privateKey, String passphrase, String fileToCheck, Boolean submodules, Boolean useParentCreds) {
        this.gitImpl = gitImpl;
        this.gitRepoURL = gitRepoUrl;
        this.privateKey = privateKey;
        this.passphrase = passphrase;
        this.username = username;
        this.password = password;
        this.fileToCheck = fileToCheck;
        this.submodules = submodules;
        this.useParentCreds = useParentCreds;
        this.specialCharacter = SPECIALS_TO_CHECK.charAt(specialsIndex);
        specialsIndex = specialsIndex + 1;
        if (specialsIndex >= SPECIALS_TO_CHECK.length()) {
            specialsIndex = 0;
        }
        if (firstTestStartTime == 0) {
            firstTestStartTime = System.currentTimeMillis();
        }
        log().println(show("Repo", gitRepoUrl)
                + show("spec", specialCharacter)
                + show("impl", gitImpl)
                + show("user", username)
                + show("pass", password)
                + show("key", privateKey));
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
        listener.getLogger().println(LOGGING_STARTED);
        git = Git.with(listener, new hudson.EnvVars()).in(repo).using(gitImpl).getClient();

        assertTrue("Bad username, password, privateKey combo: '" + username + "', '" + password + "'",
                (password == null || password.isEmpty()) ^ (privateKey == null || !privateKey.exists()));
        if (password != null && !password.isEmpty()) {
            testedCredential = newUsernamePasswordCredential(username, password);
        }
        if (privateKey != null && privateKey.exists()) {
            testedCredential = newPrivateKeyCredential(username, privateKey);
        }
        assertThat(testedCredential, notNullValue());
        Fingerprint fingerprint = CredentialsProvider.getFingerprintOf(testedCredential);
        assertThat("Fingerprint should not be set", fingerprint, nullValue());
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

    @Parameterized.Parameters(name = "{2}-{1}-{0}-{5}")
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
                    Object[] masterRepo = {implementation, url, username, null, DEFAULT_PRIVATE_KEY, null, "README.md", false, false};
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
                        // JGit 4.5 as used in git client plugin prior to 3.0.0 does not handle
                        // github.com:MarkEWaite/tasks.git correctly (no username in the URL), while it handles
                        // git@github.com:MarkEWaite/tasks.git correctly.
                        // This special case should be removed when JGit 4.11 is used.
                        // JGit 4.11 as used in git client plugin prior to 3.0.0 handles
                        // github.com:MarkEWaite/tasks.git correctly (no username in the URL), and handles
                        // git@github.com:MarkEWaite/tasks.git correctly.
                        if (implementation.startsWith("jgit") && skipIf.startsWith("jgit-4.5")) { // Treat jgitapache like jgit
                            continue;
                        }
                    }

                    if (fileToCheck == null) {
                        fileToCheck = "README.md";
                    }

                    Boolean submodules = (Boolean) entry.get("submodules");
                    if (submodules == null) {
                        submodules = false;
                    }

                    Boolean useParentCreds = (Boolean) entry.get("parentcreds");
                    if (useParentCreds == null) {
                        useParentCreds = false;
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
                        Object[] repo = {implementation, repoURL, username, password, privateKey, passphrase, fileToCheck, submodules, useParentCreds};
                        repos.add(repo);
                    }
                }
            }
        }
        Collections.shuffle(repos); // randomize test order
        // If we're not testing all credentials, take 6 or less
        return TEST_ALL_CREDENTIALS ? repos : repos.subList(0, Math.min(repos.size(), 6));
    }

    private void doFetch(String source) throws InterruptedException, URISyntaxException {
        /* Save some bandwidth with shallow clone for CliGit, not yet available for JGit */
        URIish sourceURI = new URIish(source);
        List<RefSpec> refSpecs = new ArrayList<>();
        refSpecs.add(new RefSpec("+refs/heads/master:refs/remotes/origin/master"));
        FetchCommand cmd = git.fetch_().from(sourceURI, refSpecs).tags(false);
        if (gitImpl.equals("git")) {
            // Reduce network transfer by using shallow clone
            // JGit does not support shallow clone
            cmd.shallow(true).depth(1);
        }
        cmd.execute();
    }

    private String listDir(File dir) {
        File[] files = repo.listFiles();
        StringBuilder fileList = new StringBuilder();
        for (File file : files) {
            fileList.append(file.getName());
            fileList.append(',');
        }
        fileList.deleteCharAt(fileList.length() - 1);
        return fileList.toString();
    }

    private void addCredential() throws IOException {
        // Always use addDefaultCredentials
        git.addDefaultCredentials(testedCredential);
        // addCredential stops tests to prompt for passphrase
        // addCredentials fails some github username / password tests
        // git.addCredentials(gitRepoURL, testedCredential);
    }

    /**
     * Returns true if another test should be allowed to start.
     * JenkinsRule test timeout defaults to 180 seconds.
     *
     * @return true if another test should be allowed to start
     */
    private boolean testPeriodNotExpired() {
        return (System.currentTimeMillis() - firstTestStartTime) < ((180 - 60) * 1000L);
    }

    @Test
    public void testFetchWithCredentials() throws URISyntaxException, GitException, InterruptedException, MalformedURLException, IOException {
        assumeTrue(testPeriodNotExpired());
        File clonedFile = new File(repo, fileToCheck);
        git.init_().workspace(repo.getAbsolutePath()).execute();
        assertFalse("file " + fileToCheck + " in " + repo + ", has " + listDir(repo), clonedFile.exists());
        addCredential();
        /* Fetch with remote URL */
        doFetch(gitRepoURL);
        git.setRemoteUrl("origin", gitRepoURL);
        /* Fetch with remote name "origin" instead of remote URL */
        doFetch("origin");
        ObjectId master = git.getHeadRev(gitRepoURL, "master");
        log().println("Checking out " + master.getName().substring(0, 8) + " from " + gitRepoURL);
        git.checkout().branch("master").ref(master.getName()).deleteBranchIfExist(true).execute();
        if (submodules) {
            log().println("Initializing submodules from " + gitRepoURL);
            git.submoduleInit();
            SubmoduleUpdateCommand subcmd = git.submoduleUpdate().parentCredentials(useParentCreds);
            subcmd.execute();
        }
        assertTrue("master: " + master + " not in repo", git.isCommitInRepo(master));
        assertEquals("Master != HEAD", master, git.getRepository().getRef("master").getObjectId());
        assertEquals("Wrong branch", "master", git.getRepository().getBranch());
        assertTrue("No file " + fileToCheck + ", has " + listDir(repo), clonedFile.exists());
        /* prune opens a remote connection to list remote branches */
        git.prune(new RemoteConfig(git.getRepository().getConfig(), "origin"));
    }

    @Test
    public void testRemoteReferencesWithCredentials() throws Exception {
        assumeTrue(testPeriodNotExpired());
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

    private String show(String name, String value) {
        if (value != null && !value.isEmpty()) {
            return " " + name + ": '" + value + "'";
        }
        return "";
    }

    private String show(String name, File file) {
        if (file != null) {
            String homePath = HOME_DIR.getAbsolutePath();
            String filePath = file.getAbsolutePath();
            if (filePath.startsWith(homePath)) {
                filePath = filePath.replace(homePath, "~");
            }
            return " " + name + ": '" + filePath + "'";
        }
        return "";
    }

    private String show(String name, char value) {
        return " " + name + ": '" + value + "'";
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
