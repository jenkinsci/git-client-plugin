package org.jenkinsci.plugins.gitclient;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public abstract class CheckoutCommand implements GitCommand {

    public String ref;
    public String branch;
    public boolean deleteBranch;

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
}
