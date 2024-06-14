package org.jenkinsci.plugins.gitclient.trilead;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.TaskListener;

/**
 * @deprecated just use the one with a better package name {@link org.jenkinsci.plugins.gitclient.jgit.SmartCredentialsProvider}
 */
@Deprecated(since = "4.8.0", forRemoval = true)
@SuppressFBWarnings(
        value = "NM_SAME_SIMPLE_NAME_AS_SUPERCLASS",
        justification = "only here because to keep backward compat")
public class SmartCredentialsProvider extends org.jenkinsci.plugins.gitclient.jgit.SmartCredentialsProvider {
    public SmartCredentialsProvider(TaskListener listener) {
        super(listener);
    }
}
