package org.jenkinsci.plugins.gitclient;

import java.util.Collections;
import java.util.List;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public interface CheckoutCommand extends GitCommand {

    CheckoutCommand ref(String ref);

    CheckoutCommand branch(String branch);

    CheckoutCommand deleteBranchIfExist(boolean deleteBranch);

    CheckoutCommand sparseCheckoutPaths(List<String> sparseCheckoutPaths);
}
