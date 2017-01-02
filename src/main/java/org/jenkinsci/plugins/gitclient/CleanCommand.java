package org.jenkinsci.plugins.gitclient;

import java.util.List;

/**
 * Command to clean a repository.
 *
 */
public interface CleanCommand extends GitCommand {
    /**
     * exclude patterns.
     *
     * @param excludePatterns a {@link java.util.List} with exclude patterns.
     * @return a {@link org.jenkinsci.plugins.gitclient.CleanCommand} object.
     */
    CleanCommand excludePatterns(List<String> excludePatterns);

    /**
     * 
     */
    CleanCommand submodules(boolean removeSubmodules);
    
    /**
     * timeout.
     *
     * @param timeout a {@link java.lang.Integer} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.CleanCommand} object.
     */
    CleanCommand timeout(Integer timeout);

}
