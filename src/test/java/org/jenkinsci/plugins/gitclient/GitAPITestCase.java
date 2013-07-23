package org.jenkinsci.plugins.gitclient;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.IndexEntry;
import hudson.util.IOUtils;
import hudson.util.StreamTaskListener;
import junit.framework.TestCase;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;

import java.io.*;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public abstract class GitAPITestCase extends TestCase {

    public final TemporaryDirectoryAllocator temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();
    
    protected hudson.EnvVars env = new hudson.EnvVars();
    protected TaskListener listener = StreamTaskListener.fromStdout();

    /**
     * One local workspace of a Git repository on a temporary directory
     * that gets automatically cleaned up in the end.
     * 
     * Every test case automatically gets one in {@link #w} but additional ones can be created if multi-repository
     * interactions need to be tested.
     */
    class WorkingArea {
        final File repo;
        final GitClient git;
        
        WorkingArea() throws Exception {
            repo = temporaryDirectoryAllocator.allocate();
            git = setupGitAPI(repo);
        }        

        String launchCommand(String args) throws IOException, InterruptedException {
            return launchCommand(repo, args);
        }
    
        String launchCommand(String... args) throws IOException, InterruptedException {
            return dolaunchCommand(repo, args);
        }
    
        String launchCommand(File workdir, String args) throws IOException, InterruptedException {
            return dolaunchCommand(workdir, args.split(" "));
        }
    
        String dolaunchCommand(File workdir, String ... args) throws IOException, InterruptedException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int st = new Launcher.LocalLauncher(listener).launch().pwd(workdir).cmds(args).
                    envs(env).stdout(out).join();
            String s = out.toString();
            assertEquals(s, 0, st);
            System.out.println(s);
            return s;
        }

        /**
         * Refers to a file in this workspace
         */
        File file(String path) {
            return new File(repo, path);
        }

        boolean exists(String path) {
            return file(path).exists();
        }

        /**
         * Creates a file in the workspace.
         */
        void touch(String path) throws IOException {
            file(path).createNewFile();
        }

        /**
         * Creates a file in the workspace.
         */
        void touch(String path, String content) throws IOException {
            FileUtils.writeStringToFile(file(path), content);
        }

        public String contentOf(String path) throws IOException {
            return FileUtils.readFileToString(file(path));
        }
    }
    
    private WorkingArea w;
    

    @Override
    protected void setUp() throws Exception {
        w = new WorkingArea();
    }

    /**
     * Obtains the local mirror of https://github.com/jenkinsci/git-client-plugin.git and return URLish to it.
     */
    public String localMirror() throws IOException, InterruptedException {
        File base = new File(".").getAbsoluteFile();
        for (File f=base; f!=null; f=f.getParentFile()) {
            if (new File(f,"target").exists()) {
                File clone = new File(f, "target/clone.git");
                if (!clone.exists())    // TODO: perhaps some kind of quick timestamp-based up-to-date check?
                    w.launchCommand("git clone --mirror https://github.com/jenkinsci/git-client-plugin.git "+clone.getAbsolutePath());
                return clone.getPath();
            }
        }
        throw new IllegalStateException();
    }


    protected abstract GitClient setupGitAPI(File ws) throws Exception;

    @Override
    protected void tearDown() throws Exception {
        temporaryDirectoryAllocator.dispose();
    }

    public void test_initialize_repository() throws Exception {
        w.git.init();
        String status = w.launchCommand("git status");
        assertTrue("unexpected status " + status, status.contains("On branch master"));
    }

    public void test_detect_commit_in_repo() throws Exception {
        w.launchCommand("git init");
        w.touch("file1");
        w.launchCommand("git add file1");
        w.launchCommand("git commit -m 'commit1'");
        String sha1 = w.launchCommand("git rev-parse HEAD").substring(0,40);
        assertTrue("HEAD commit not found", w.git.isCommitInRepo(ObjectId.fromString(sha1)));
        // this MAY fail if commit has this exact sha1, but please admit this would be unlucky
        assertFalse(w.git.isCommitInRepo(ObjectId.fromString("1111111111111111111111111111111111111111")));
    }


    public void test_getRemoteURL() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git remote add origin https://github.com/jenkinsci/git-client-plugin.git");
        w.launchCommand("git remote add ndeloof git@github.com:ndeloof/git-client-plugin.git");
        String remoteUrl = w.git.getRemoteUrl("origin");
        assertEquals("unexepected remote URL " + remoteUrl, "https://github.com/jenkinsci/git-client-plugin.git", remoteUrl);
    }

    public void test_setRemoteURL() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git remote add origin https://github.com/jenkinsci/git-client-plugin.git");
        w.git.setRemoteUrl("origin", "git@github.com:ndeloof/git-client-plugin.git");
        String remotes = w.launchCommand("git remote -v");
        assertTrue("remote URL has not been updated", remotes.contains("git@github.com:ndeloof/git-client-plugin.git"));
    }

    public void test_clean() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");

        w.touch("file", "content");
        w.launchCommand("git add file");
        w.launchCommand("git commit -m file");

        w.touch(".gitignore", ".test");
        w.launchCommand("git add .gitignore");
        w.launchCommand("git commit -m ignore");

        w.touch("file1");
        w.touch(".test");
        w.touch("file", "new content");

        w.git.clean();
        assertFalse(w.exists("file1"));
        assertFalse(w.exists(".test"));
        assertEquals("content", w.contentOf("file"));
        String status = w.launchCommand("git status");
        assertTrue("unexpected status " + status, status.contains("working directory clean"));
    }

    public void test_fetch() throws Exception {
        File remote = temporaryDirectoryAllocator.allocate();
        w.launchCommand(remote, "git init");
        w.launchCommand(remote, "git commit --allow-empty -m init");
        String sha1 = w.launchCommand(remote, "git rev-list --max-count=1 HEAD");

        w.launchCommand("git init");
        w.launchCommand("git remote add origin " + remote.getAbsolutePath());
        w.git.fetch("origin", null);
        assertTrue(sha1.equals(w.launchCommand(remote, "git rev-list --max-count=1 HEAD")));
    }

    public void test_fetch_with_updated_tag() throws Exception {
        File remote = temporaryDirectoryAllocator.allocate();
        w.launchCommand(remote, "git init");
        w.launchCommand(remote, "git commit --allow-empty -m init");
        w.launchCommand(remote, "git tag t");
        String sha1 = w.launchCommand(remote, "git rev-list --max-count=1 t");

        w.launchCommand("git init");
        w.launchCommand("git remote add origin " + remote.getAbsolutePath());
        w.git.fetch("origin", null);
        assertTrue(sha1.equals(w.launchCommand(remote, "git rev-list --max-count=1 t")));

        new File(remote, "file.txt").createNewFile();
        w.launchCommand(remote, "git add file.txt");
        w.launchCommand(remote, "git commit -m update");
        w.launchCommand(remote, "git tag -d t");
        w.launchCommand(remote, "git tag t");
        sha1 = w.launchCommand(remote, "git rev-list --max-count=1 t");
        w.git.fetch("origin", null);
        assertTrue(sha1.equals(w.launchCommand(remote, "git rev-list --max-count=1 t")));

    }


    public void test_create_branch() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        w.git.branch("test");
        String branches = w.launchCommand("git branch -l");
        assertTrue("master branch not listed", branches.contains("master"));
        assertTrue("test branch not listed", branches.contains("test"));
    }

    public void test_list_branches() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        w.launchCommand("git branch test");
        w.launchCommand("git branch another");
        Set<Branch> branches = w.git.getBranches();
        Collection names = Collections2.transform(branches, new Function<Branch, String>() {
            public String apply(Branch branch) {
                return branch.getName();
            }
        });
        assertEquals(3, branches.size());
        assertTrue("master branch not listed", names.contains("master"));
        assertTrue("test branch not listed", names.contains("test"));
        assertTrue("another branch not listed", names.contains("another"));
    }

    public void test_list_remote_branches() throws Exception {
        File remote = temporaryDirectoryAllocator.allocate();
        w.launchCommand(remote, "git init");
        w.launchCommand(remote, "git commit --allow-empty -m init");
        w.launchCommand(remote, "git branch test");
        w.launchCommand(remote, "git branch another");

        w.launchCommand("git init");
        w.launchCommand("git remote add origin " + remote.getAbsolutePath());
        w.launchCommand("git fetch origin");
        Set<Branch> branches = w.git.getRemoteBranches();
        Collection names = Collections2.transform(branches, new Function<Branch, String>() {
            public String apply(Branch branch) {
                return branch.getName();
            }
        });
        assertEquals(3, branches.size());
        assertTrue("origin/master branch not listed", names.contains("origin/master"));
        assertTrue("origin/test branch not listed", names.contains("origin/test"));
        assertTrue("origin/another branch not listed", names.contains("origin/another"));
    }

    public void test_list_branches_containing_ref() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        w.launchCommand("git branch test");
        w.launchCommand("git branch another");
        Set<Branch> branches = w.git.getBranches();
        Collection names = Collections2.transform(branches, new Function<Branch, String>() {
            public String apply(Branch branch) {
                return branch.getName();
            }
        });
        assertEquals(3, branches.size());
        assertTrue(names.contains("master"));
        assertTrue(names.contains("test"));
        assertTrue(names.contains("another"));
    }

    public void test_delete_branch() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        w.launchCommand("git branch test");
        w.git.deleteBranch("test");
        String branches = w.launchCommand("git branch -l");
        assertFalse("deleted test branch still present", branches.contains("test"));
    }

    public void test_create_tag() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        w.git.tag("test", "this is a tag");
        assertTrue("test tag not created", w.launchCommand("git tag").contains("test"));
        String message = w.launchCommand("git tag -l -n1");
        assertTrue("unexpected test tag message : " + message, message.contains("this is a tag"));
    }

    public void test_delete_tag() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        w.launchCommand("git tag test");
        w.launchCommand("git tag another");
        w.git.deleteTag("test");
        String tags = w.launchCommand("git tag");
        assertFalse("deleted test tag still present", tags.contains("test"));
        assertTrue("expected tag not listed", tags.contains("another"));
    }

    public void test_list_tags_with_filter() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        w.launchCommand("git tag test");
        w.launchCommand("git tag another_test");
        w.launchCommand("git tag yet_another");
        Set<String> tags = w.git.getTagNames("*test");
        assertTrue("expected tag not listed", tags.contains("test"));
        assertTrue("expected tag not listed", tags.contains("another_test"));
        assertFalse("unexpected tag listed", tags.contains("yet_another"));
    }

    public void test_tag_exists() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        w.launchCommand("git tag test");
        assertTrue(w.git.tagExists("test"));
        assertFalse(w.git.tagExists("unknown"));
    }

    public void test_get_tag_message() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        w.launchCommand("git tag test -m this-is-a-test");
        assertEquals("this-is-a-test", w.git.getTagMessage("test"));
    }

    public void test_get_tag_message_multi_line() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        w.launchCommand("git", "tag", "test", "-m", "test 123!\n* multi-line tag message\n padded ");

        // Leading four spaces from each line should be stripped,
        // but not the explicit single space before "padded",
        // and the final errant space at the end should be trimmed
        assertEquals("test 123!\n* multi-line tag message\n padded", w.git.getTagMessage("test"));
    }

    public void test_get_HEAD_revision() throws Exception {
        // TODO replace with an embedded JGit server so that test run offline ?
        String sha1 = w.launchCommand("git ls-remote --heads https://github.com/jenkinsci/git-client-plugin.git refs/heads/master").substring(0,40);
        assertEquals(sha1, w.git.getHeadRev("https://github.com/jenkinsci/git-client-plugin.git", "master").name());
    }

    public void test_revparse_sha1_HEAD_or_tag() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        w.touch("file1");
        w.launchCommand("git add file1");
        w.launchCommand("git commit -m 'commit1'");
        w.launchCommand("git tag test");
        String sha1 = w.launchCommand("git rev-parse HEAD").substring(0,40);
        assertEquals(sha1, w.git.revParse(sha1).name());
        assertEquals(sha1, w.git.revParse("HEAD").name());
        assertEquals(sha1, w.git.revParse("test").name());
    }

    public void test_hasGitRepo_without_git_directory() throws Exception
    {
        assertFalse("Empty directory has a Git repo", w.git.hasGitRepo());
    }

    public void test_hasGitRepo_with_invalid_git_repo() throws Exception
    {
        // Create an empty directory named .git - "corrupt" git repo
        w.file(".git").mkdir();
        assertFalse("Invalid Git repo reported as valid", w.git.hasGitRepo());
    }

    public void test_hasGitRepo_with_valid_git_repo() throws Exception {
        w.launchCommand("git init");
        assertTrue("Valid Git repo reported as invalid", w.git.hasGitRepo());
    }

    public void test_push() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        w.touch("file1");
        w.launchCommand("git add file1");
        w.launchCommand("git commit -m 'commit1'");
        String sha1 = w.launchCommand("git rev-parse HEAD").substring(0,40);

        File remote = temporaryDirectoryAllocator.allocate();
        w.launchCommand(remote, "git init");
        w.launchCommand(remote, "git checkout -b tmp"); // can't push on active branch
        w.launchCommand("git remote add origin " + remote.getAbsolutePath());

        w.git.push("origin", "master");
        String remoteSha1 = w.launchCommand(remote, "git rev-parse master").substring(0, 40);
        assertEquals(sha1, remoteSha1);
    }

    public void test_notes_add() throws Exception {
        w.launchCommand("git init");
        w.touch("file1");
        w.launchCommand("git add file1");
        w.launchCommand("git commit -m init");

        w.git.addNote("foo","commits");
        assertEquals("foo\n", w.launchCommand("git notes show"));
        w.git.appendNote("alpha\rbravo\r\ncharlie\r\n\r\nbar\n\n\nzot\n\n","commits");
        // cgit normalizes CR+LF aggressively
        // it appears to be collpasing CR+LF to LF, then truncating duplicate LFs down to 2
        // note that CR itself is left as is
        assertEquals("foo\n\nalpha\rbravo\ncharlie\n\nbar\n\nzot\n", w.launchCommand("git notes show"));
    }

    /**
     * A rev-parse warning message should not break revision parsing.
     */
    @Bug(11177)
    public void test_jenkins_11177() throws Exception
    {
        w.launchCommand("git init");
        w.launchCommand("git commit --allow-empty -m init");
        ObjectId base = ObjectId.fromString(w.launchCommand("git rev-parse master").substring(0,40));
        ObjectId master = w.git.revParse("master");
        assertEquals(base, master);

        /* Make reference to master ambiguous, verify it is reported ambiguous by rev-parse */
        w.launchCommand("git tag master"); // ref "master" is now ambiguous
        String revParse = w.launchCommand("git rev-parse master");
        assertTrue("'" + revParse + "' does not contain 'ambiguous'", revParse.contains("ambiguous"));

        /* Get reference to ambiguous master */
        ObjectId ambiguous = w.git.revParse("master");
        assertEquals("ambiguous != master", ambiguous.toString(), master.toString());
    }

    public void test_getSubmodules() throws Exception {
        w.launchCommand("git init");
        w.launchCommand("git","fetch",localMirror(),"tests/getSubmodules:t");
        w.launchCommand("git checkout t");
        List<IndexEntry> r = w.git.getSubmodules("HEAD");
        assertEquals(
            "[IndexEntry[mode=160000,type=commit,file=modules/firewall,object=63264ca1dcf198545b90bb6361b4575ef220dcfa], "+
             "IndexEntry[mode=160000,type=commit,file=modules/ntp,object=c5408ae4b17bc3b395b13d10c9473e15661d2d38]]",
            r.toString()
        );
    }

    public void test_hasSubmodules() throws Exception {
        w.launchCommand("git init");

        w.launchCommand("git","fetch",localMirror(),"tests/getSubmodules:t");
        w.launchCommand("git checkout t");
        assertTrue(w.git.hasGitModules());

        w.launchCommand("git","fetch",localMirror(),"master:t2");
        w.launchCommand("git checkout t2");
        assertFalse(w.git.hasGitModules());
    }

//    public void test_prune() throws Exception {
//        w.launchCommand("git init");
//        temporaryDirectoryAllocator.allocate()
//        File remote = temporaryDirectoryAllocator.allocate();
//
//    }

    private static final Logger LOGGER = Logger.getLogger(GitAPITestCase.class.getName());
}
