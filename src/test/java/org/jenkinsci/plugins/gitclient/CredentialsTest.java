package org.jenkinsci.plugins.gitclient;

import au.com.bytecode.opencsv.CSVReader;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.google.common.io.Files;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import static junit.framework.TestCase.assertTrue;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;

/**
 *
 * @author Mark Waite
 */
@RunWith(Parameterized.class)
public class CredentialsTest {

    // Required for credentials use
    @Rule
    public JenkinsRule j = new JenkinsRule();

    private final String gitRepoURL;
    private final String username;
    private final File privateKey;

    private GitClient git;
    private File repo;
    private BasicSSHUserPrivateKey credential;

    protected String gitImpl; /* either "git" or "jgit" */

    private String expectedLogSubstring = null;

    private final TemporaryDirectoryAllocator temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();

    private int logCount;
    private LogHandler handler;
    private LogTaskListener listener;
    private static final String LOGGING_STARTED = "*** Logging started ***";

    private final static File homeDir = new File(System.getProperty("user.home"));
    private final static File sshDir = new File(homeDir, ".ssh");
    private final static File defaultPrivateKey = new File(sshDir, "id_rsa");

    /* Directory containing local private keys for tests */
    private final static File authDataDir = new File(sshDir, "auth-data");

    private final static File currDir = new File(".");

    private static PrintStream log() {
        return StreamTaskListener.fromStdout().getLogger();
    }

    public CredentialsTest(String gitRepoUrl, String username, File privateKey) {
        gitImpl = "git";
        this.gitRepoURL = gitRepoUrl;
        if (privateKey == null && defaultPrivateKey.exists()) {
            privateKey = defaultPrivateKey;
        }
        this.privateKey = privateKey;
        this.username = username;
        log().println("Repo: " + gitRepoUrl);
    }

    @Before
    public void setUp() throws IOException, InterruptedException {
        repo = temporaryDirectoryAllocator.allocate();
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        listener.getLogger().println(LOGGING_STARTED);
        git = Git.with(listener, new hudson.EnvVars()).in(repo).using(gitImpl).getClient();
        setExpectedLogSubstring("> git fetch ");
    }

    @After
    public void tearDown() {
        temporaryDirectoryAllocator.disposeAsync();
        try {
            String messages = StringUtils.join(handler.getMessages(), ";");
            assertTrue("Logging not started: " + messages, handler.containsMessageSubstring(LOGGING_STARTED));
            if (expectedLogSubstring != null) {
                assertTrue("No '" + expectedLogSubstring + "' in " + messages,
                        handler.containsMessageSubstring(expectedLogSubstring));
            }
        } finally {
            setExpectedLogSubstring(null);
            handler.close();
        }
    }

    protected void setExpectedLogSubstring(String expectedLogSubstring) {
        this.expectedLogSubstring = expectedLogSubstring;
    }

    private BasicSSHUserPrivateKey newCredential(File privateKey, String username) throws IOException {
        CredentialsScope scope = CredentialsScope.GLOBAL;
        String id = "private-key-" + privateKey.getPath();
        String privateKeyData = Files.toString(privateKey, Charset.forName("UTF-8"));
        BasicSSHUserPrivateKey.PrivateKeySource privateKeySource = new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(privateKeyData);
        String passphrase = null;
        String description = "private key from " + privateKey.getPath();
        return new BasicSSHUserPrivateKey(scope, id, username, privateKeySource, passphrase, description);
    }

    @Parameterized.Parameters(name = "{1}-{0}")
    public static Collection gitRepoUrls() throws MalformedURLException, FileNotFoundException, IOException {
        List<Object[]> repos = new ArrayList<Object[]>();
        /* Add master repository as authentication test with private
         * key of current user.  Try to test at least one
         * authentication case, even if there is no repos.csv file in
         * the external directory.
         */
        if (defaultPrivateKey.exists()) {
            String username = System.getProperty("user.name");
            String url = "https://github.com/jenkinsci/git-client-plugin.git";
            Object[] masterRepo = {url, username, defaultPrivateKey};
            repos.add(masterRepo);
        }

        /* Add additional repositories if the ~/.ssh/auth-data directory
         * contains a repos.csv file defining the repositories to test and the
         * private key files to use for those tests.
         */
        File authDataDefinitions = new File(authDataDir, "repos.csv");
        if (authDataDefinitions.exists()) {
            CSVReader reader = new CSVReader(new FileReader(authDataDefinitions));
            List<String[]> myEntries = reader.readAll();
            for (String[] entry : myEntries) {
                String repoURL = entry[0];
                String username = entry[1];
                File privateKey = new File(authDataDir, entry[2]);
                if (privateKey.exists()) {
                    Object[] repo = {repoURL, username, privateKey};
                    repos.add(repo);
                } else {
                    Object[] repo = {repoURL, username, null};
                    repos.add(repo);
                }
            }
        }
        Collections.shuffle(repos); // randomize test order
        int toIndex = repos.size() < 3 ? repos.size() : 3;
        if (TEST_ALL_CREDENTIALS) {
            toIndex = repos.size();
        }
        return repos.subList(0, toIndex);
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

    @Test
    public void testFetchWithCredentials() throws URISyntaxException, GitException, InterruptedException, MalformedURLException, IOException {
        File readme = new File(repo, "README.md");
        String origin = "origin";
        List<RefSpec> refSpecs = new ArrayList<RefSpec>();
        refSpecs.add(new RefSpec("+refs/heads/*:refs/remotes/" + origin + "/*"));
        git.init_().workspace(repo.getAbsolutePath()).execute();
        assertFalse("readme in " + repo + ", has " + listDir(repo), readme.exists());
        git.addDefaultCredentials(newCredential(privateKey, username));
        git.fetch_().from(new URIish(gitRepoURL), refSpecs).execute();
        git.setRemoteUrl(origin, gitRepoURL);
        ObjectId master = git.getHeadRev(gitRepoURL, "master");
        log().println("Checking out " + master + " from " + gitRepoURL);
        git.checkout().branch("master").ref(master.getName()).deleteBranchIfExist(true).execute();
        assertTrue("master: " + master + " not in repo", git.isCommitInRepo(master));
        assertEquals("Master != HEAD", master, git.getRepository().getRef("master").getObjectId());
        assertEquals("Wrong branch", "master", git.getRepository().getBranch());
        if (gitImpl == "git") {
            /* The checkout command for JGit fails to checkout the files in this
             * test.  I am still working to understand why it fails, since it
             * works in the typical use case with the plugin, and it works with
             * the other unit tests in GitAPITestCase.
             */
            assertTrue("No readme in " + repo + ", has " + listDir(repo), readme.exists());
        }
    }

    @Test
    public void testCloneWithCredentials() throws URISyntaxException, GitException, InterruptedException, MalformedURLException, IOException {
        File readme = new File(repo, "README.md");
        String origin = "origin";
        git.addCredentials(gitRepoURL, newCredential(privateKey, username));
        CloneCommand cmd = git.clone_().url(gitRepoURL).repositoryName(origin);
        if (gitImpl.equals("git")) {
            // Reduce network transfer by using a local reference repository
            // JGit does not support reference repositories
            cmd.reference(currDir.getAbsolutePath());
        }
        cmd.execute();
        ObjectId master = git.getHeadRev(gitRepoURL, "master");
        log().println("Checking out " + master + " from " + gitRepoURL);
        git.checkout().branch("master").ref(origin + "/master").deleteBranchIfExist(true).execute();
        assertTrue("master: " + master + " not in repo", git.isCommitInRepo(master));
        assertEquals("Master != HEAD", master, git.getRepository().getRef("master").getObjectId());
        assertEquals("Wrong branch", "master", git.getRepository().getBranch());
        assertTrue("No readme in " + repo + ", has " + listDir(repo), readme.exists());
        git.clearCredentials();
    }

    private static final boolean TEST_ALL_CREDENTIALS = Boolean.valueOf(System.getProperty("TEST_ALL_CREDENTIALS", "false"));
}
