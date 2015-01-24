package org.jenkinsci.plugins.gitclient;

import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import java.util.List;

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
     */
    PushCommand force();

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
