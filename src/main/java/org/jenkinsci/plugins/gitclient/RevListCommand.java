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
     * @deprecated favour {@link #all(boolean)}
     */
    @Deprecated
    RevListCommand all();

    /**
     * all.
     *
     * @param all {@code true} to list all.
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     * @since 2.5.0
     */
    RevListCommand all(boolean all);

    /**
     * nowalk.
     *
     * @param nowalk {@code true} to skip revision walk.
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     */
    RevListCommand nowalk(boolean nowalk);

    /**
     * firstParent.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     * @deprecated favour {@link #firstParent(boolean)}
     */
    @Deprecated
    RevListCommand firstParent();

    /**
     * firstParent.
     *
     * @param firstParent {@code true} to list first parent
     * @return a {@link org.jenkinsci.plugins.gitclient.RevListCommand} object.
     * @since 2.5.0
     */
    RevListCommand firstParent(boolean firstParent);

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
