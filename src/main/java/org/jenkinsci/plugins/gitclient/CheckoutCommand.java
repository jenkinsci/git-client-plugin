package org.jenkinsci.plugins.gitclient;

import java.util.Collections;
import java.util.List;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public abstract class CheckoutCommand implements GitCommand {

    public String ref;
    public String branch;
    public boolean deleteBranch;
    public List<String> sparseCheckoutPaths = Collections.emptyList();

    public CheckoutCommand ref(String ref) {
        this.ref = ref;
        return this;
    }

    public CheckoutCommand branch(String branch) {
        this.branch = branch;
        return this;
    }

    public CheckoutCommand deleteBranchIfExist(boolean deleteBranch) {
        this.deleteBranch = deleteBranch;
        return this;
    }

    public CheckoutCommand sparseCheckoutPaths(List<String> sparseCheckoutPaths) {
        this.sparseCheckoutPaths = sparseCheckoutPaths == null ? Collections.<String>emptyList() : sparseCheckoutPaths;
        return this;
    }
}
