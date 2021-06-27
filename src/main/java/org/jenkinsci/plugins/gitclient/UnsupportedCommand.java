package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.common.StandardCredentials;

import java.util.Collections;
import java.util.List;

/**
 * A command to convey unsupported features. Currently, implemented for JGit.
 *
 * All of the operations listed below are not implemented in JGit currently.
 */
public class UnsupportedCommand {

    private boolean useJGit = true;

    // From CheckoutCommand
    /**
     * JGit is unsupported if sparseCheckoutPaths is non-empty.
     *
     * @param sparseCheckoutPaths list of paths to be included in the checkout
     * @return this for chaining
     */
    public UnsupportedCommand sparseCheckoutPaths(List<String> sparseCheckoutPaths) {
        List<String> sparseList = sparseCheckoutPaths == null ? Collections.emptyList() : sparseCheckoutPaths;
        if (!sparseList.isEmpty()) {
            useJGit = false;
        }
        return this;
    }

    /**
     * JGit is unsupported if timeout is non-null. Could be supported if timeout
     * is 0, since that means no timeout.
     *
     * @param timeout maximum time git operation is allowed before it is interrupted
     * @return this for chaining
     */
    public UnsupportedCommand timeout(Integer timeout) {
        if (timeout != null) {
            useJGit = false;
        }
        return this;
    }

    /**
     * JGit is unsupported if lfsRemote is non-null.
     *
     * @param lfsRemote URL of large file support server
     * @return this for chaining
     */
    public UnsupportedCommand lfsRemote(String lfsRemote) {
        if (lfsRemote != null) {
            useJGit = false;
        }
        return this;
    }

    /**
     * JGit is unsupported if lfsCredentials is non-null.
     *
     * @param lfsCredentials credential used for large file support
     * @return this for chaining
     */
    public UnsupportedCommand lfsCredentials(StandardCredentials lfsCredentials) {
        if (lfsCredentials != null) {
            useJGit = false;
        }
        return this;
    }

    // From CloneCommand
    /**
     * JGit is unsupported if shallow is true.
     *
     * @param shallow if true then shallow clone and fetch are enabled
     * @return this for chaining
     */
    public UnsupportedCommand shallow(boolean shallow) {
        if (shallow) {
            useJGit = false;
        }
        return this;
    }

    /**
     * JGit is unsupported if depth is non-null. Could also be supported if
     * depth is 0, since that means unlimited depth.
     *
     * @param depth depth of commits to be fetched into workspace
     * @return this for chaining
     */
    public UnsupportedCommand depth(Integer depth) {
        if (depth != null) {
            useJGit = false;
        }
        return this;
    }

    // From RevListCommand
    /**
     * JGit is unsupported if firstParent is true.
     *
     * @param firstParent if true, only consider the first parents of a merge in revision list
     * @return this for chaining
     */
    public UnsupportedCommand firstParent(boolean firstParent) {
        if (firstParent) {
            useJGit = false;
        }
        return this;
    }

    // From SubmoduleUpdateCommand
    /**
     * JGit is unsupported if threads is non-zero.
     *
     * @param threads count of threads to use for parallel submodule update
     * @return this for chaining
     */
    public UnsupportedCommand threads(Integer threads) {
        if (threads != null && threads != 0) {
            useJGit = false;
        }
        return this;
    }

    /**
     * JGit is unsupported if remoteTracking is true.
     *
     * @param remoteTracking submodule should use a remote tracking branch if true
     * @return this for chaining
     */
    public UnsupportedCommand remoteTracking(boolean remoteTracking) {
        if (remoteTracking) {
            useJGit = false;
        }
        return this;
    }

    /**
     * JGit is unsupported if ref is non-empty.
     *
     * @param ref location of submodule reference repository
     * @return this for chaining
     */
    public UnsupportedCommand ref(String ref) {
        if (ref != null && !ref.isEmpty()) {
            useJGit = false;
        }
        return this;
    }

    /**
     * JGit is unsupported if parentCredentials is true.
     *
     * @param parentCredentials submodule update uses credentials from parent repository if true
     * @return this for chaining
     */
    public UnsupportedCommand parentCredentials(boolean parentCredentials) {
        if (parentCredentials) {
            useJGit = false;
        }
        return this;
    }

    /**
     * JGit is unsupported if submodule or branchName are non-null.
     *
     * @param submodule name of submodule that should checkout a specific branch
     * @param branchname name of branch to be checked out for submodule
     * @return this for chaining
     */
    public UnsupportedCommand useBranch(String submodule, String branchname) {
        if (submodule != null || branchname != null) {
            useJGit = false;
        }
        return this;
    }

    /**
     * JGit doesn't support Git Publisher.
     * @param isEnabled if true, then git publisher post-build action is enabled in this context
     * @return this for chaining
     */
    public UnsupportedCommand gitPublisher(boolean isEnabled) {
        if (isEnabled) {
            useJGit = false;
        }
        return this;
    }

    /**
     * Returns true if JGit is supported based on previously passed values.
     *
     * @return true if JGit is supported based on previously passed values
     */
    public boolean determineSupportForJGit() {
        return useJGit;
    }
}
