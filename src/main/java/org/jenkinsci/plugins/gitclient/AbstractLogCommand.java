package org.jenkinsci.plugins.gitclient;

import java.io.Writer;

public interface AbstractLogCommand<C extends AbstractLogCommand<?>> extends GitCommand {

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
    C to(Writer w);

    /**
     * Limit the number of changelog entries up to n.
     *
     * @param n a int.
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
    C max(int n);

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
