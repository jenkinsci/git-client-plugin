package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.common.StandardCredentials;

import java.util.Collections;
import java.util.List;

/**
 * A command to convey unsupported features (Currently, implemented for JGit)
 *
 * All of the operations listed below are not implemented in JGit currently.
 */
public class UnsupportedCommand {

    private boolean useJGit = true;

    // From CheckoutCommand
    public UnsupportedCommand sparseCheckoutPaths(List<String> sparseCheckoutPaths) {
        List<String> sparseList = sparseCheckoutPaths == null ? Collections.<String>emptyList() : sparseCheckoutPaths;
        if (!sparseList.isEmpty()) {
            useJGit = false;
        }
        return this;
    }

    public UnsupportedCommand timeout(Integer timeout) {
        if (timeout != null) {
            useJGit = false;
        }
        return this;
    }

    public UnsupportedCommand lfsRemote(String lfsRemote) {
        if (lfsRemote != null) {
            useJGit = false;
        }
        return this;
    }

    public UnsupportedCommand lfsCredentials(StandardCredentials lfsCredentials) {
        if (lfsCredentials != null) {
            useJGit = false;
        }
        return this;
    }

    // From CloneCommand
    public UnsupportedCommand shallow(boolean shallow) {
        if (shallow) {
            useJGit = false;
        }
        return this;
    }

    public UnsupportedCommand depth(Integer depth) {
        if (depth != null) {
            useJGit = false;
        }
        return this;
    }

    // From RevListCommand
    public UnsupportedCommand firstParent(boolean firstParent) {
        if (firstParent) {
            useJGit = false;
        }
        return this;
    }

    // From SubmoduleUpdateCommand
    public UnsupportedCommand threads(int threads) {
        if (threads != 0) {
            useJGit = false;
        }
        return this;
    }

    public UnsupportedCommand remoteTracking(boolean remoteTracking) {
        if (remoteTracking) {
            useJGit = false;
        }
        return this;
    }

    public UnsupportedCommand ref(String ref) {
        if (ref != null && !ref.isEmpty()) {
            useJGit = false;
        }
        return this;
    }

    public UnsupportedCommand parentCredentials(boolean parentCredentials) {
        if (parentCredentials) {
            useJGit = false;
        }
        return this;
    }

    public UnsupportedCommand useBranch(String submodule, String branchname) {
        if (submodule != null || branchname != null) {
            useJGit = false;
        }
        return this;
    }

    public boolean determineSupportForJGit() {
        return useJGit;
    }

}
