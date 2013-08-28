package org.jenkinsci.plugins.gitclient;

import org.eclipse.jgit.lib.ObjectId;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public interface MergeCommand extends GitCommand {

    MergeCommand setRevisionToMerge(ObjectId rev);

    MergeCommand setStrategy(Strategy strategy);

    public enum Strategy {
        DEFAULT, RESOLVE, RECURSIVE, OCTOPUS, OURS, SUBTREE;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }
}
