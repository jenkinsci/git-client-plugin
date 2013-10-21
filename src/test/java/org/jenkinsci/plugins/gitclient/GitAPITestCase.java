package org.jenkinsci.plugins.gitclient;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import hudson.Launcher;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.IndexEntry;
import hudson.util.StreamTaskListener;
import junit.framework.TestCase;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;

import java.io.*;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
            this(temporaryDirectoryAllocator.allocate());
        }

        WorkingArea(File repo) throws Exception {
            this.repo = repo;
            git = setupGitAPI(repo);
        }

        String cmd(String args) throws IOException, InterruptedException {
            return launchCommand(args.split(" "));
        }
    
        String launchCommand(String... args) throws IOException, InterruptedException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int st = new Launcher.LocalLauncher(listener).launch().pwd(repo).cmds(args).
                    envs(env).stdout(out).join();
            String s = out.toString();
            assertEquals(s, 0, st);
            System.out.println(s);
            return s;
        }

        String repoPath() {
            return repo.getAbsolutePath();
        }
        
        WorkingArea init() throws IOException, InterruptedException {
            cmd("git init");
            return this;
        }

        void add(String path) throws IOException, InterruptedException {
            cmd("git add " + path);
        }

        void tag(String tag) throws IOException, InterruptedException {
            cmd("git tag " + tag);
        }

        void commit(String msg) throws IOException, InterruptedException {
            cmd("git commit --allow-empty -m " + msg);
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
        File touch(String path, String content) throws IOException {
            File f = file(path);
            FileUtils.writeStringToFile(f, content);
            return f;
        }

        public void rm(String path) {
            file(path).delete();
        }

        public String contentOf(String path) throws IOException {
            return FileUtils.readFileToString(file(path));
        }

        /**
         * Creates a CGit implementation. Sometimes we need this for testing JGit impl.
         */
        protected CliGitAPIImpl cgit() throws Exception {
            return (CliGitAPIImpl)Git.with(listener, env).in(repo).using("git").getClient();
        }

        /**
         * Creates a {@link Repository} object out of it.
         */
        protected FileRepository repo() throws IOException {
            return new FileRepository(new File(repo,".git"));
        }

        public void checkout(String branch) throws IOException, InterruptedException {
            cmd("git checkout " + branch);
        }

        /**
         * Obtain the current HEAD revision
         */
        ObjectId head() throws IOException, InterruptedException {
            return revParse("HEAD");
        }

        ObjectId revParse(String commit) throws IOException, InterruptedException {
            return ObjectId.fromString(w.cmd("git rev-parse "+commit).substring(0,40));
        }

        /**
         * Casts the {@link #git} to {@link IGitAPI}
         */
        public IGitAPI igit() {
            return (IGitAPI)git;
        }
    }
    
    private WorkingArea w;

    WorkingArea clone(String src) throws Exception {
        WorkingArea x = new WorkingArea();
        x.cmd("git clone " + src + " " + x.repoPath());
        return new WorkingArea(x.repo);
    }



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
                    w.cmd("git clone --mirror https://github.com/jenkinsci/git-client-plugin.git " + clone.getAbsolutePath());
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
        String status = w.cmd("git status");
        assertTrue("unexpected status " + status, status.contains("On branch master"));
    }

    public void test_detect_commit_in_repo() throws Exception {
        w.init();
        w.touch("file1");
        w.add("file1");
        w.commit("commit1");
        assertTrue("HEAD commit not found", w.git.isCommitInRepo(w.head()));
        // this MAY fail if commit has this exact sha1, but please admit this would be unlucky
        assertFalse(w.git.isCommitInRepo(ObjectId.fromString("1111111111111111111111111111111111111111")));
    }


    public void test_getRemoteURL() throws Exception {
        w.init();
        w.cmd("git remote add origin https://github.com/jenkinsci/git-client-plugin.git");
        w.cmd("git remote add ndeloof git@github.com:ndeloof/git-client-plugin.git");
        String remoteUrl = w.git.getRemoteUrl("origin");
        assertEquals("unexepected remote URL " + remoteUrl, "https://github.com/jenkinsci/git-client-plugin.git", remoteUrl);
    }

    public void test_setRemoteURL() throws Exception {
        w.init();
        w.cmd("git remote add origin https://github.com/jenkinsci/git-client-plugin.git");
        w.git.setRemoteUrl("origin", "git@github.com:ndeloof/git-client-plugin.git");
        String remotes = w.cmd("git remote -v");
        assertTrue("remote URL has not been updated", remotes.contains("git@github.com:ndeloof/git-client-plugin.git"));
    }

    public void test_clean() throws Exception {
        w.init();
        w.commit("init");

        w.touch("file", "content");
        w.add("file");
        w.commit("file");

        w.touch(".gitignore", ".test");
        w.add(".gitignore");
        w.commit("ignore");

        w.touch("file1");
        w.touch(".test");
        w.touch("file", "new content");

        w.git.clean();
        assertFalse(w.exists("file1"));
        assertFalse(w.exists(".test"));
        assertEquals("content", w.contentOf("file"));
        String status = w.cmd("git status");
        assertTrue("unexpected status " + status, status.contains("working directory clean"));
    }

    public void test_fetch() throws Exception {
        WorkingArea r = new WorkingArea();
        r.init();
        r.commit("init");
        String sha1 = r.cmd("git rev-list --max-count=1 HEAD");

        w.init();
        w.cmd("git remote add origin " + r.repoPath());
        w.git.fetch("origin", new RefSpec[] {null});
        assertTrue(sha1.equals(r.cmd("git rev-list --max-count=1 HEAD")));
    }

    public void test_fetch_from_url() throws Exception {
        WorkingArea r = new WorkingArea();
        r.init();
        r.commit("init");
        String sha1 = r.cmd("git rev-list --max-count=1 HEAD");

        w.init();
        w.cmd("git remote add origin " + r.repoPath());
        w.git.fetch(new URIish(r.repo.toString()), Collections.EMPTY_LIST);
        assertTrue(sha1.equals(r.cmd("git rev-list --max-count=1 HEAD")));
    }

    public void test_fetch_with_updated_tag() throws Exception {
        WorkingArea r = new WorkingArea();
        r.init();
        r.commit("init");
        r.tag("t");
        String sha1 = r.cmd("git rev-list --max-count=1 t");

        w.init();
        w.cmd("git remote add origin " + r.repoPath());
        w.git.fetch("origin", new RefSpec[] {null});
        assertTrue(sha1.equals(r.cmd("git rev-list --max-count=1 t")));

        r.touch("file.txt");
        r.add("file.txt");
        r.commit("update");
        r.tag("-d t");
        r.tag("t");
        sha1 = r.cmd("git rev-list --max-count=1 t");
        w.git.fetch("origin", new RefSpec[] {null});
        assertTrue(sha1.equals(r.cmd("git rev-list --max-count=1 t")));

    }


    public void test_create_branch() throws Exception {
        w.init();
        w.commit("init");
        w.git.branch("test");
        String branches = w.cmd("git branch -l");
        assertTrue("master branch not listed", branches.contains("master"));
        assertTrue("test branch not listed", branches.contains("test"));
    }

    public void test_list_branches() throws Exception {
        w.init();
        w.commit("init");
        w.cmd("git branch test");
        w.cmd("git branch another");
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
        WorkingArea r = new WorkingArea();
        r.init();
        r.commit("init");
        r.cmd("git branch test");
        r.cmd("git branch another");

        w.init();
        w.cmd("git remote add origin " + r.repoPath());
        w.cmd("git fetch origin");
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
        w.init();
        w.commit("init");
        w.cmd("git branch test");
        w.cmd("git branch another");
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
        w.init();
        w.commit("init");
        w.cmd("git branch test");
        w.git.deleteBranch("test");
        String branches = w.cmd("git branch -l");
        assertFalse("deleted test branch still present", branches.contains("test"));
    }

    public void test_create_tag() throws Exception {
        w.init();
        w.commit("init");
        w.git.tag("test", "this is a tag");
        assertTrue("test tag not created", w.cmd("git tag").contains("test"));
        String message = w.cmd("git tag -l -n1");
        assertTrue("unexpected test tag message : " + message, message.contains("this is a tag"));
    }

    public void test_delete_tag() throws Exception {
        w.init();
        w.commit("init");
        w.tag("test");
        w.tag("another");
        w.git.deleteTag("test");
        String tags = w.cmd("git tag");
        assertFalse("deleted test tag still present", tags.contains("test"));
        assertTrue("expected tag not listed", tags.contains("another"));
    }

    public void test_list_tags_with_filter() throws Exception {
        w.init();
        w.commit("init");
        w.tag("test");
        w.tag("another_test");
        w.tag("yet_another");
        Set<String> tags = w.git.getTagNames("*test");
        assertTrue("expected tag not listed", tags.contains("test"));
        assertTrue("expected tag not listed", tags.contains("another_test"));
        assertFalse("unexpected tag listed", tags.contains("yet_another"));
    }

    public void test_tag_exists() throws Exception {
        w.init();
        w.commit("init");
        w.tag("test");
        assertTrue(w.git.tagExists("test"));
        assertFalse(w.git.tagExists("unknown"));
    }

    public void test_get_tag_message() throws Exception {
        w.init();
        w.commit("init");
        w.tag("test -m this-is-a-test");
        assertEquals("this-is-a-test", w.git.getTagMessage("test"));
    }

    public void test_get_tag_message_multi_line() throws Exception {
        w.init();
        w.commit("init");
        w.launchCommand("git", "tag", "test", "-m", "test 123!\n* multi-line tag message\n padded ");

        // Leading four spaces from each line should be stripped,
        // but not the explicit single space before "padded",
        // and the final errant space at the end should be trimmed
        assertEquals("test 123!\n* multi-line tag message\n padded", w.git.getTagMessage("test"));
    }

    public void test_get_HEAD_revision() throws Exception {
        // TODO replace with an embedded JGit server so that test run offline ?
        String sha1 = w.cmd("git ls-remote --heads https://github.com/jenkinsci/git-client-plugin.git refs/heads/master").substring(0,40);
        assertEquals(sha1, w.git.getHeadRev("https://github.com/jenkinsci/git-client-plugin.git", "master").name());
    }

    public void test_revparse_sha1_HEAD_or_tag() throws Exception {
        w.init();
        w.commit("init");
        w.touch("file1");
        w.add("file1");
        w.commit("commit1");
        w.tag("test");
        String sha1 = w.cmd("git rev-parse HEAD").substring(0,40);
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
        w.init();
        assertTrue("Valid Git repo reported as invalid", w.git.hasGitRepo());
    }

    public void test_push() throws Exception {
        w.init();
        w.commit("init");
        w.touch("file1");
        w.add("file1");
        w.commit("commit1");
        ObjectId sha1 = w.head();

        WorkingArea r = new WorkingArea();
        r.init();
        r.cmd("git checkout -b tmp"); // can't push on active branch
        w.cmd("git remote add origin " + r.repoPath());

        w.git.push("origin", "master");
        String remoteSha1 = r.cmd("git rev-parse master").substring(0, 40);
        assertEquals(sha1.name(), remoteSha1);
    }

    public void test_notes_add() throws Exception {
        w.init();
        w.touch("file1");
        w.add("file1");
        w.commit("init");

        w.git.addNote("foo", "commits");
        assertEquals("foo\n", w.cmd("git notes show"));
        w.git.appendNote("alpha\rbravo\r\ncharlie\r\n\r\nbar\n\n\nzot\n\n", "commits");
        // cgit normalizes CR+LF aggressively
        // it appears to be collpasing CR+LF to LF, then truncating duplicate LFs down to 2
        // note that CR itself is left as is
        assertEquals("foo\n\nalpha\rbravo\ncharlie\n\nbar\n\nzot\n", w.cmd("git notes show"));
    }

    /**
     * A rev-parse warning message should not break revision parsing.
     */
    @Bug(11177)
    public void test_jenkins_11177() throws Exception
    {
        w.init();
        w.commit("init");
        ObjectId base = w.head();
        ObjectId master = w.git.revParse("master");
        assertEquals(base, master);

        /* Make reference to master ambiguous, verify it is reported ambiguous by rev-parse */
        w.tag("master"); // ref "master" is now ambiguous
        String revParse = w.cmd("git rev-parse master");
        assertTrue("'" + revParse + "' does not contain 'ambiguous'", revParse.contains("ambiguous"));

        /* Get reference to ambiguous master */
        ObjectId ambiguous = w.git.revParse("master");
        assertEquals("ambiguous != master", ambiguous.toString(), master.toString());
    }

    public void test_getSubmodules() throws Exception {
        w.init();
        w.launchCommand("git","fetch",localMirror(),"tests/getSubmodules:t");
        w.cmd("git checkout t");
        List<IndexEntry> r = w.git.getSubmodules("HEAD");
        assertEquals(
                "[IndexEntry[mode=160000,type=commit,file=modules/firewall,object=63264ca1dcf198545b90bb6361b4575ef220dcfa], " +
                        "IndexEntry[mode=160000,type=commit,file=modules/ntp,object=c5408ae4b17bc3b395b13d10c9473e15661d2d38]]",
                r.toString()
        );
    }

    @NotImplementedInJGit
    public void test_sparse_checkout() throws Exception {
        // Create a repo for cloning purpose
        w.init();
        w.commit("init");
        w.file("dir1").mkdir();
        w.touch("dir1/file1");
        w.file("dir2").mkdir();
        w.touch("dir2/file2");
        w.file("dir3").mkdir();
        w.touch("dir3/file3");
        w.add("dir1/file1");
        w.add("dir2/file2");
        w.add("dir3/file3");
        w.commit("commit");

        // Clone it
        WorkingArea workingArea = clone(w.repoPath());

        workingArea.git.checkout().ref("origin/master").branch("master").deleteBranchIfExist(true).sparseCheckoutPaths(Lists.newArrayList("dir1")).execute();
        assertTrue(workingArea.exists("dir1"));
        assertFalse(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea.git.checkout().ref("origin/master").branch("master").deleteBranchIfExist(true).sparseCheckoutPaths(Lists.newArrayList("dir2")).execute();
        assertFalse(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea.git.checkout().ref("origin/master").branch("master").deleteBranchIfExist(true).sparseCheckoutPaths(Lists.newArrayList("dir1", "dir2")).execute();
        assertTrue(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertFalse(workingArea.exists("dir3"));

        workingArea.git.checkout().ref("origin/master").branch("master").deleteBranchIfExist(true).sparseCheckoutPaths(Collections.<String>emptyList()).execute();
        assertTrue(workingArea.exists("dir1"));
        assertTrue(workingArea.exists("dir2"));
        assertTrue(workingArea.exists("dir3"));
    }

    public void test_clone_no_checkout() throws Exception {
        // Create a repo for cloning purpose
        WorkingArea repoToClone = new WorkingArea();
        repoToClone.init();
        repoToClone.commit("init");
        repoToClone.touch("file1");
        repoToClone.add("file1");
        repoToClone.commit("commit");

        // Clone it with no checkout
        w.git.clone_().url(repoToClone.repoPath()).repositoryName("origin").noCheckout().execute();
        assertFalse(w.exists("file1"));
    }

    public void test_hasSubmodules() throws Exception {
        w.init();

        w.launchCommand("git", "fetch", localMirror(), "tests/getSubmodules:t");
        w.cmd("git checkout t");
        assertTrue(w.git.hasGitModules());

        w.launchCommand("git", "fetch", localMirror(), "master:t2");
        w.cmd("git checkout t2");
        assertFalse(w.git.hasGitModules());
    }

    public void test_getSubmoduleUrl() throws Exception {
        w = clone(localMirror());
        w.checkout("tests/getSubmodules");
        w.git.submoduleInit();

        assertEquals("git://github.com/puppetlabs/puppetlabs-firewall.git", w.igit().getSubmoduleUrl("modules/firewall"));

        try {
            w.igit().getSubmoduleUrl("bogus");
            fail();
        } catch (GitException e) {
            // expected
        }
    }

    public void test_setSubmoduleUrl() throws Exception {
        w = clone(localMirror());
        w.checkout("tests/getSubmodules");
        w.git.submoduleInit();

        String DUMMY = "/dummy";
        w.igit().setSubmoduleUrl("modules/firewall", DUMMY);

        // create a brand new Git object to make sure it's persisted
        w = new WorkingArea(w.repo);
        assertEquals(DUMMY, w.igit().getSubmoduleUrl("modules/firewall"));
    }

    public void test_prune() throws Exception {
        // pretend that 'r' is a team repository and ws1 and ws2 are team members
        WorkingArea r = new WorkingArea();
        r.cmd("git init --bare");

        WorkingArea ws1 = new WorkingArea().init();
        WorkingArea ws2 = w.init();

        ws1.commit("c");
        ws1.cmd("git remote add origin " + r.repoPath());

        ws1.cmd("git push origin master:b1");
        ws1.cmd("git push origin master:b2");
        ws1.cmd("git push origin master");

        ws2.cmd("git remote add origin " + r.repoPath());
        ws2.cmd("git fetch origin");

        // at this point both ws1&ws2 have several remote tracking branches

        ws1.cmd("git push origin :b1");
        ws1.cmd("git push origin master:b3");

        ws2.git.prune(new RemoteConfig(new Config(),"origin"));

        assertFalse(ws2.exists(".git/refs/remotes/origin/b1"));
        assertTrue( ws2.exists(".git/refs/remotes/origin/b2"));
        assertFalse(ws2.exists(".git/refs/remotes/origin/b3"));
    }

    public void test_revListAll() throws Exception {
        WorkingArea w = new WorkingArea().init();
        w.cmd("git pull " + localMirror());

        StringBuilder out = new StringBuilder();
        for (ObjectId id : w.git.revListAll()) {
            out.append(id.name()).append('\n');
        }
        String all = w.cmd("git rev-list --all");
        assertEquals(all,out.toString());
    }

    public void test_revList() throws Exception {
        WorkingArea w = new WorkingArea().init();
        w.cmd("git pull " + localMirror());

        for (Branch b : w.git.getRemoteBranches()) {
            StringBuilder out = new StringBuilder();
            for (ObjectId id : w.git.revList(b.getName())) {
                out.append(id.name()).append('\n');
            }
            String all = w.cmd("git rev-list " + b.getName());
            assertEquals(all,out.toString());
        }
    }

    public void test_merge_strategy() throws Exception {
        w.init();
        w.commit("init");
        w.cmd("git branch branch1");
        w.cmd("git checkout branch1");
        w.touch("file", "content1");
        w.add("file");
        w.commit("commit1");
        w.cmd("git checkout master");
        w.cmd("git branch branch2");
        w.cmd("git checkout branch2");
        File f = w.touch("file", "content2");
        w.add("file");
        w.commit("commit2");
        w.git.merge().setStrategy(MergeCommand.Strategy.OURS).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch1")).execute();
        assertEquals("merge didn't selected OURS content", "content2", FileUtils.readFileToString(f));
    }

    public void test_merge_strategy_correct_fail() throws Exception {
        w.init();
        w.commit("init");
        w.cmd("git branch branch1");
        w.cmd("git checkout branch1");
        w.touch("file", "content1");
        w.add("file");
        w.commit("commit1");
        w.cmd("git checkout master");
        w.cmd("git branch branch2");
        w.cmd("git checkout branch2");
        w.touch("file", "content2");
        w.add("file");
        w.commit("commit2");
        try {
            w.git.merge().setStrategy(MergeCommand.Strategy.RESOLVE).setRevisionToMerge(w.git.getHeadRev(w.repoPath(), "branch1")).execute();
            fail();
        }
        catch (GitException e) {
            // expected
        }
    }

    public void test_show_revision_for_merge() throws Exception {
        w = clone(localMirror());
        ObjectId from = ObjectId.fromString("45e76942914664ee19f31d90e6f2edbfe0d13a46");
        ObjectId to = ObjectId.fromString("b53374617e85537ec46f86911b5efe3e4e2fa54b");

        List<String> revisionDetails = w.git.showRevision(from, to);

        Collection<String> commits = Collections2.filter(revisionDetails, new Predicate<String>() {
            public boolean apply(String detail) {
                return detail.startsWith("commit ");
            }
        });
        assertEquals(3, commits.size());
        assertTrue(commits.contains("commit 4f2964e476776cf59be3e033310f9177bedbf6a8"));
        // Merge commit is duplicated as have to capture changes that may have been made as part of merge
        assertTrue(commits.contains("commit b53374617e85537ec46f86911b5efe3e4e2fa54b (from 4f2964e476776cf59be3e033310f9177bedbf6a8)"));
        assertTrue(commits.contains("commit b53374617e85537ec46f86911b5efe3e4e2fa54b (from 45e76942914664ee19f31d90e6f2edbfe0d13a46)"));

        Collection<String> diffs = Collections2.filter(revisionDetails, new Predicate<String>() {
            public boolean apply(String detail) {
                return detail.startsWith(":");
            }
        });
        Collection<String> paths = Collections2.transform(diffs, new Function<String, String>() {
            public String apply(String diff) {
                return diff.substring(diff.indexOf('\t')+1);
            }
        });

        assertTrue(paths.contains(".gitignore"));
        // Some irrelevant changes will be listed due to merge commit
        assertTrue(paths.contains("pom.xml"));
        assertTrue(paths.contains("src/main/java/hudson/plugins/git/GitAPI.java"));
        assertTrue(paths.contains("src/main/java/org/jenkinsci/plugins/gitclient/CliGitAPIImpl.java"));
        assertTrue(paths.contains("src/main/java/org/jenkinsci/plugins/gitclient/Git.java"));
        assertTrue(paths.contains("src/main/java/org/jenkinsci/plugins/gitclient/GitClient.java"));
        assertTrue(paths.contains("src/main/java/org/jenkinsci/plugins/gitclient/JGitAPIImpl.java"));
        assertTrue(paths.contains("src/test/java/org/jenkinsci/plugins/gitclient/GitAPITestCase.java"));
        assertTrue(paths.contains("src/test/java/org/jenkinsci/plugins/gitclient/JGitAPIImplTest.java"));
        // Previous implementation included other commits, and listed irrelevant changes
        assertFalse(paths.contains("README.md"));
    }

    public void test_show_revision_for_single_commit() throws Exception {
        w = clone(localMirror());
        ObjectId to = ObjectId.fromString("51de9eda47ca8dcf03b2af58dfff7355585f0d0c");
        List<String> revisionDetails = w.git.showRevision(null, to);
        Collection<String> commits = Collections2.filter(revisionDetails, new Predicate<String>() {
            public boolean apply(String detail) {
                return detail.startsWith("commit ");
            }
        });
        assertEquals(1, commits.size());
        assertTrue(commits.contains("commit 51de9eda47ca8dcf03b2af58dfff7355585f0d0c"));
    }

    public void test_describe() throws Exception {
        WorkingArea w = new WorkingArea().init();
        w.commit("first");
        w.tag("-m test t1");
        w.touch("a");
        w.add("a");
        w.commit("second");
        assertEquals(w.cmd("git describe").trim(), w.git.describe("HEAD"));

        w.tag("-m test2 t2");
        assertEquals(w.cmd("git describe").trim(), w.git.describe("HEAD"));
    }

    public void test_getAllLogEntries() throws Exception {
        w = clone(localMirror());

        assertEquals(
                w.cgit().getAllLogEntries("origin/master"),
                w.igit().getAllLogEntries("origin/master"));
    }

    public void test_branchContaining() throws Exception {
        /*
         OLD                                    NEW
                   -> X
                  /
                c1 -> T -> c2 -> Z
                  \            \
                   -> c3 --------> Y
         */
        w.init();

        w.commit("c1");
        ObjectId c1 = w.head();

        w.cmd("git branch Z "+c1.name());
        w.checkout("Z");
        w.commit("T");
        ObjectId t = w.head();
        w.commit("c2");
        ObjectId c2 = w.head();
        w.commit("Z");

        w.cmd("git branch X "+c1.name());
        w.checkout("X");
        w.commit("X");

        w.cmd("git branch Y "+c1.name());
        w.checkout("Y");
        w.commit("c3");
        ObjectId c3 = w.head();
        w.cmd("git merge --no-ff -m Y "+c2.name());

        w.git.deleteBranch("master");
        assertEquals(3,w.git.getBranches().size());     // X, Y, and Z

        assertEquals("X,Y,Z",formatBranches(w.igit().getBranchesContaining(c1.name())));
        assertEquals("Y,Z",formatBranches(w.igit().getBranchesContaining(t.name())));
        assertEquals("Y",formatBranches(w.igit().getBranchesContaining(c3.name())));
        assertEquals("X",formatBranches(w.igit().getBranchesContaining("X")));
    }

    @Bug(19108)
    public void test_checkoutBranch() throws Exception {
        w.init();
        w.commit("c1");
        w.tag("t1");
        w.commit("c2");

        w.git.checkoutBranch("foo", "t1");

        assertEquals(w.head(),w.revParse("t1"));
        assertEquals(w.head(),w.revParse("foo"));

        Ref head = w.repo().getRef("HEAD");
        assertTrue(head.isSymbolic());
        assertEquals("refs/heads/foo",head.getTarget().getName());
    }

    private String formatBranches(List<Branch> branches) {
        Set<String> names = new TreeSet<String>();
        for (Branch b : branches) {
            names.add(b.getName());
        }
        return Util.join(names,",");
    }

    @Bug(18988)
    public void test_localCheckoutConflict() throws Exception {
        w.init();
        w.touch("foo","old");
        w.add("foo");
        w.commit("c1");
        w.tag("t1");

        // delete the file from git
        w.cmd("git rm foo");
        w.commit("c2");
        assertFalse(w.file("foo").exists());

        // now create an untracked local file
        w.touch("foo","new");

        // this should overwrite foo
        w.git.checkout("t1");

        assertEquals("old",FileUtils.readFileToString(w.file("foo")));
    }
}
