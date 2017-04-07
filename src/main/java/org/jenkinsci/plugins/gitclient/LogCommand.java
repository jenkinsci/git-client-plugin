package org.jenkinsci.plugins.gitclient;

/**
 * Command builder for generating changelog in the format {@code GitSCM} expects, using git log command with revision range specification.
 * Supports excluding cherry picks from change log, which other command ChangelogCommand don't.
 * May in future support aditional advanced git log parameters.
 *
 * see https://git-scm.com/docs/git-log
 *
 */
public interface LogCommand extends AbstractLogCommand<LogCommand> {

	/**
     * range from..to.
     *
     * @param cherryPick - --cherry-pick - Omit any commit that introduces the same change as another commit  on the "other side".
     * @return a {@link org.jenkinsci.plugins.gitclient.ChangelogCommand} object.
     */
	LogCommand revisionRange(String fromRevision, String toRevision, boolean cherryPick);

}
