package org.jenkinsci.plugins.gitclient;

import java.util.Locale;
import org.eclipse.jgit.lib.ObjectId;

/**
 * MergeCommand interface.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public interface MergeCommand extends GitCommand {

    /**
     * Sets the revision to include in the merge.
     *
     * @param rev revision to include in the merge
     * @return MergeCommand to be used in fluent calls
     */
    MergeCommand setRevisionToMerge(ObjectId rev);

    /**
     * setMessage.
     *
     * @param message the desired comment for the merge command.
     * @return MergeCommand to be used in fluent calls
     */
    MergeCommand setMessage(String message);

    /**
     * setStrategy.
     *
     * @param strategy a {@link org.jenkinsci.plugins.gitclient.MergeCommand.Strategy} object.
     * @return MergeCommand to be used in fluent calls
     */
    MergeCommand setStrategy(Strategy strategy);

    enum Strategy {
        DEFAULT, RESOLVE, RECURSIVE, OCTOPUS, OURS, SUBTREE, RECURSIVE_THEIRS;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ENGLISH); // Avoid Turkish 'i' conversion
        }
    }

    /**
     * Select the fast forward mode.
     * The name FastForwardMode collides with org.eclipse.jgit.api.MergeCommand.FastForwardMode
     * so we have to choose a different name.
     *
     * @param fastForwardMode mode to be used in this merge
     * @return MergeCommand to be used in fluent calls
     */
    MergeCommand setGitPluginFastForwardMode(GitPluginFastForwardMode fastForwardMode);

    enum GitPluginFastForwardMode {
        FF,        // Default option, fast forward update the branch pointer only
        FF_ONLY,   // Create a merge commit even for a fast forward
        NO_FF;     // Abort unless the merge is a fast forward

        @Override
        public String toString() {
            return "--"+name().toLowerCase(Locale.ENGLISH).replace("_","-"); // Avoid Turkish 'i' issue
        }
    }

    /**
     * setSquash
     *
     * @param squash - whether to squash commits or not
     * @return MergeCommand to be used in fluent calls
     */
    MergeCommand setSquash(boolean squash);

    /**
     * setCommit
     *
     * @param commit - whether or not to commit the result after a successful merge.
     * @return MergeCommand to be used in fluent calls
     */
    MergeCommand setCommit(boolean commit);
}
