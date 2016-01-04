package org.jenkinsci.plugins.gitclient;

/**
 * cherry pick.
 * See https://git-scm.com/docs/git-cherry-pick
 *
 * @author Jan Zajic
 */
public interface CherryPickCommand extends GitCommand {

	CherryPickCommand commit(String sha1);
	
}
