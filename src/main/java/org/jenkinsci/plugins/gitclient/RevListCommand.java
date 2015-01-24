package org.jenkinsci.plugins.gitclient;

import java.util.List;
import org.eclipse.jgit.lib.ObjectId;

/**
 * RevListCommand interface.
 *
 * @author <a href="mailto:m.zahnlecker@gmail.com">Marc Zahnlecker</a>
 */
public interface RevListCommand extends GitCommand {
    /**
     * all.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     */
    RevListCommand all();

    /**
     * firstParent.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     */
    RevListCommand firstParent();

    /**
     * to.
     *
     * @param revs a {@link java.util.List} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     */
    RevListCommand to(List<ObjectId> revs);

    /**
     * reference.
     *
     * @param reference a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     */
    RevListCommand reference(String reference);
}
