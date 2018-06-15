package org.jenkinsci.plugins.gitclient;

import org.eclipse.jgit.lib.ObjectId;

import java.io.OutputStream;
import java.io.Writer;

/**
 * Command builder for generating changelog in the format {@code GitSCM} expects.
 *
 * <p>
 * The output format is that of <code>git-whatchanged</code>, which looks something like this:
 *
 * <pre>
 * commit dadaf808d99c4c23c53476b0c48e25a181016300
 * tree 1f5d7a5576bb74962aa6674a01ecf89f4a9da8af
 * parent 05970c030621c23e54aa3ae66bbcf5bb495455cd
 * author Kohsuke Kawaguchi &lt;kk@kohsuke.org&gt; 1371370465 +0200
 * committer Kohsuke Kawaguchi &lt;kk@kohsuke.org&gt; 1371370465 +0200
 *
 *     Fixed compilation problems
 *
 * :100644 100644 2b179b9036b8552c4a6d335656e0af14aa995079 7bee1589120233339818fea5f422f20c7fc1cbfb M	src/test/java/org/jenkinsci/plugins/gitclient/CliGitAPIImplTest.java
 * :100644 100644 1a4dfa565b440c56d5a5891710f4dcfbad674287 88a734f95412f8d5fc295e2596d77e0d86714df5 M	src/test/java/org/jenkinsci/plugins/gitclient/GitAPITestCase.java
 * :100644 100644 224de0e0633a2cf488c89bf9add3112c6d87c793 88af4823d198c7fddc30a51ebc6f4e83c2086089 M	src/test/java/org/jenkinsci/plugins/gitclient/JGitAPIImplTest.java
 *
 * commit 05970c030621c23e54aa3ae66bbcf5bb495455cd
 * tree cdbd88f2b8d729101a7b9b12bd518bd7fdfa976c
 * parent a1a30140beb3b9d30767863e901fa5424932ccfc
 * author Kohsuke Kawaguchi &lt;kk@kohsuke.org&gt; 1371369131 +0200
 * committer Kohsuke Kawaguchi &lt;kk@kohsuke.org&gt; 1371369131 +0200
 *
 *     Take author/committer separately
 *
 *     This works better than the previous design for several reasons:
 *
 *     - in typical usage (such as GitSCM), the part of the code that configures these things is very different from the parts of the code that make git operations like commit/merge.
 *     - git-commit is not the only only operation where the committer/author settings matter. For example, git-merge creates commits, too.
 *
 * :100644 100644 b1108ade5c173d836330eb237eb3af7a81225863 3fcf799908a2016e6ee06a6c83b3646dc96f221f M	src/main/java/org/jenkinsci/plugins/gitclient/AbstractGitAPIImpl.java
 * :100644 100644 8c07705c17074bdcb409d27bf2d258118b8b1771 47a7dcad1dfa6db5dc5fc0f4c7bc50c4f0a2df32 M	src/main/java/org/jenkinsci/plugins/gitclient/CliGitAPIImpl.java
 * :100644 100644 5826de835c1f40d3eef9d2e1ef8c27d0ec39c3c4 edb233af6e5512a0fa30fa5086d6ce034085664a M	src/main/java/org/jenkinsci/plugins/gitclient/Git.java
 * :100644 100644 5828ff74da8b6ad239d31ae258de2cf624f1a863 e7b224dedf864107cb7027fb3a5391470364d5cd M	src/main/java/org/jenkinsci/plugins/gitclient/GitClient.java
 * :100644 100644 acc6136f171fc8a2c0d21ccb3503d4ce522191db 440134ff44e3c692591c4a42ef6d3e3c7783f356 M	src/main/java/org/jenkinsci/plugins/gitclient/JGitAPIImpl.java
 *
 * </pre>
 *
 * @author Kohsuke Kawaguchi
 */
public interface ChangelogCommand extends GitCommand {
    /**
     * Adds the revision to exclude from the log.
     * Equivalent of {@code ^rev} on the command line.
     *
     * This method can be invoked multiple times.
     *
     * @param rev a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    ChangelogCommand excludes(String rev);

    /**
     * excludes.
     *
     * @param rev a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    ChangelogCommand excludes(ObjectId rev);

    /**
     * Adds the revision to include in the log.
     *
     * This method can be invoked multiple times.
     *
     * @param rev a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    ChangelogCommand includes(String rev);

    /**
     * includes.
     *
     * @param rev a {@link org.eclipse.jgit.lib.ObjectId} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    ChangelogCommand includes(ObjectId rev);

    /**
     * Sets the {@link java.io.OutputStream} that receives the changelog.
     *
     * This takes {@link java.io.Writer} and not {@link java.io.OutputStream} because the changelog is a textual format,
     * and therefore it is a stream of chars, not bytes. (If the latter, then we'd be unable to handle
     * multiple encodings correctly)
     *
     * According to man git-commit, the "encoding" header specifies the encoding of the commit message,
     * and git CLIs will try to translate encoding back to UTF-8. In any case, it is the implementation's
     * responsibility to correctly handle the encoding
     *
     * @param w a {@link java.io.Writer} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    ChangelogCommand to(Writer w);

    /**
     * Limit the number of changelog entries up to n.
     *
     * @param n a int.
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    ChangelogCommand max(int n);

    /**
     * Abort this ChangelogCommand without executing it, close any
     * open resources.  The JGit implementation of changelog
     * calculation opens the git repository and will close it when the
     * changelog.execute() is processed.  However, there are cases
     * (like GitSCM.computeChangeLog) which create a changelog and
     * never call execute().
     *
     * Either execute() or abort() must be called for each
     * ChangelogCommand instance or files will be left open.
     */
    void abort();
}
