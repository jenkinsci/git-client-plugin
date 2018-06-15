package org.jenkinsci.plugins.gitclient;

import org.eclipse.jgit.transport.URIish;

/**
 * PushCommand interface.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public interface PushCommand extends GitCommand {

    /**
     * to.
     *
     * @param remote a {@link org.eclipse.jgit.transport.URIish} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     */
    PushCommand to(URIish remote);

    /**
     * ref.
     *
     * @param refspec a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     */
    PushCommand ref(String refspec);

    /**
     * force.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     * @deprecated favour {@link #force(boolean)}
     */
    @Deprecated
    PushCommand force();

    /**
     * force.
     *
     * @param force {@code true} if the push should be forced
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     * @since 2.5.0
     */
    PushCommand force(boolean force);

    /**
     * tags.
     *
     * @param tags if true, tags will be included in the push, otherwise they are not pushed
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     */
    PushCommand tags(boolean tags);

    /**
     * timeout.
     *
     * @param timeout a {@link java.lang.Integer} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.PushCommand} object.
     */
    PushCommand timeout(Integer timeout);
}
