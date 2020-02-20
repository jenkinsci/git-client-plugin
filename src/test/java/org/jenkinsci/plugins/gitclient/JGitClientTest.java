package org.jenkinsci.plugins.gitclient;

import hudson.EnvVars;
import hudson.model.TaskListener;
import org.eclipse.jgit.api.errors.GitAPIException;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Set;

// Collides with implicit org.jenkinsci.plugins.gitclient.Git.
//import org.eclipse.jgit.api.Git;
import static org.eclipse.jgit.lib.Constants.HEAD;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.lib.ObjectId;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import org.jvnet.hudson.test.Issue;

/**
 * JGit Client-specific tests. In their own Test file to ward off bloat in 
 * GitClient and minimized parameterized and other test logic conditional 
 * on Git implementation.
 *
 * @author Brian Ray
 */
public class JGitClientTest {
    /* These tests are only for the JGit client. */
    private static final String GIT_IMPL_NAME = "jgit";

    /* Instance under test. */
    private GitClient gitClient;

    /* Lower level "porcelain" API when gitClient can't do something. */
    private org.eclipse.jgit.api.Git gitAPI;

    /* Repo garbage collector. */
    private GC gc;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private File repoRoot;

    @Before
    public void setGitClientEtc() throws IOException, InterruptedException {
        repoRoot = tempFolder.newFolder();
        gitClient = Git.with(TaskListener.NULL, new EnvVars())
                       .in(repoRoot)
                       .using(GIT_IMPL_NAME)
                       .getClient();
        FileRepository repo = (FileRepository) gitClient.getRepository();
        // See comment up in imports as to why this is fully qualified.
        gitAPI = org.eclipse.jgit.api.Git.wrap(repo);
        gc = new GC(repo);

        File gitDir = gitClient.withRepository((r, channel) -> r.getDirectory());
        gitClient.init_()
                 .workspace(repoRoot.getAbsolutePath())
                 .execute();
        assertTrue("Missing " + gitDir, gitDir.isDirectory());
    }

    private ObjectId commitFile(final String path, final String content, final String commitMessage) throws Exception {
        createFile(path, content);
        gitClient.add(path);
        gitClient.commit(commitMessage);

        List<ObjectId> headList = gitClient.revList(HEAD);
        assertThat(headList.size(), is(greaterThan(0)));
        return headList.get(0);
    }

    private void createFile(final String path, final String content) throws Exception {
        File aFile = new File(repoRoot, path);
        File parentDir = aFile.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
        try (PrintWriter writer = new PrintWriter(aFile, "UTF-8")) {
            writer.printf(content);
        } catch (FileNotFoundException | UnsupportedEncodingException ex) {
            throw new GitException(ex);
        }
    }

    private void packRefs() throws IOException {
        gc.packRefs();
    }

    // No flavor of GitClient has a tag(String) API, only tag(String,String). 
    // But sometimes we want a lightweight a.k.a. non-annotated tag.
    private void tag(String name) throws GitException {
        try {
            gitAPI.tag().setName(name).setAnnotated(false).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    @Issue("JENKINS-57205") // NPE on PreBuildMerge with packed lightweight tag
    @Test
    public void testGetTags_packedRefs() throws Exception {
        // JENKINS-57205 is triggered by lightweight tags
        ObjectId firstCommit = commitFile(
            "first.txt",
            "Great info here",
            "First commit"
        );
        String lightweightTagName = "lightweight_tag";
        tag(lightweightTagName);

        // But throw in an annotated tag for symmetry and coverage
        ObjectId secondCommit = commitFile(
            "second.txt",
            "Great info here, too",
            "Second commit"
        );
        String annotatedTagName = "annotated_tag";
        gitClient.tag(annotatedTagName, "Tag annotation");

        packRefs();

        Set<GitObject> tags = gitClient.getTags();

        assertThat(
            tags,
            hasItem(
                allOf(
                    hasProperty("name", equalTo(lightweightTagName)),
                    hasProperty("SHA1", equalTo(firstCommit))
                )
            )
        );
        assertThat(
            tags,
            hasItem(
                allOf(
                    hasProperty("name", equalTo(annotatedTagName)),
                    hasProperty("SHA1", equalTo(secondCommit))
                )
            )
        );
    }
}
