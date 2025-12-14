package org.jenkinsci.plugins.gitclient;

/**
 * InitCommand interface.
 */
public interface InitCommand extends GitCommand {

    /**
     * workspace.
     *
     * @param workspace a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.InitCommand} object.
     */
    InitCommand workspace(String workspace);

    /**
     * bare.
     *
     * @param bare a boolean.
     * @return a {@link org.jenkinsci.plugins.gitclient.InitCommand} object.
     */
    InitCommand bare(boolean bare);

    /**
     * objectFormat.
     *
     * @param objectFormat object format (e.g., "sha1" or "sha256").
     * @return a {@link org.jenkinsci.plugins.gitclient.InitCommand} object.
     */
    InitCommand objectFormat(String objectFormat);
}
