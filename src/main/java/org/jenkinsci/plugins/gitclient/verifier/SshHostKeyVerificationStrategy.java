package org.jenkinsci.plugins.gitclient.verifier;

import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.io.File;
import java.nio.file.Path;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

/**
 * Secure shell host key verification strategy extension point for SSH connections from the git client plugin.
 * Secure shell (ssh) host key verification protects an SSH client from a
 * <a href="https://superuser.com/questions/1657387/how-does-host-key-checking-prevent-man-in-the-middle-attack">man in the middle attack</a>.
 *
 * <p>Host key verifications strategies allow the Jenkins administrator
 * to choose the level of host key verification that will be
 * performed.
 *
 * <p>Host key verification strategies include:
 *
 * <dl>
 * <dt>{@link AcceptFirstConnectionStrategy Accept first connection}</dt>
 * <dd>
 * Remembers the first host key encountered for each git server and requires that the same host key must be used for later access.
 * This is usually the most convenient setting for administrators while still providing ssh host key verification
 * </dd>
 *
 * <dt>{@link KnownHostsFileVerificationStrategy Known hosts file}</dt>
 * <dd>
 * Uses the existing 'known_hosts' file on the controller and on the agent.
 * This assumes the administrator has already configured this file on the controller and on all agents
 * </dd>
 *
 * <dt>{@link ManuallyProvidedKeyVerifier Manually provided keys}</dt>
 * <dd>
 * Provides a form field where the administrator inserts the host keys for the git repository servers.
 * This works well when a small set of repository servers meet the needs of most users
 * </dd>
 *
 * <dt>{@link NoHostKeyVerificationStrategy No verification}</dt>
 * <dd>
 * Disables all verification of ssh host keys.
 * <strong>Not recommended</strong> because it provides no protection from "man-in-the-middle" attacks
 * </dd>
 * </dl>
 *
 * Configure the host key verification strategy from "Manage Jenkins" / "Security" / "Git Host Key Verification Configuration".
 * More details are available in the <a href="https://plugins.jenkins.io/git-client/#plugin-content-ssh-host-key-verification">plugin documentation</a>.
 */
public abstract class SshHostKeyVerificationStrategy<T extends HostKeyVerifierFactory>
        extends AbstractDescribableImpl<SshHostKeyVerificationStrategy<T>> implements ExtensionPoint {

    /** Default path to the known hosts file for the current user. */
    public static final String KNOWN_HOSTS_DEFAULT =
            Path.of(System.getProperty("user.home"), ".ssh", "known_hosts").toString();

    private static final String JGIT_KNOWN_HOSTS_PROPERTY =
            SshHostKeyVerificationStrategy.class.getName() + ".jgit_known_hosts_file";
    private static final String JGIT_KNOWN_HOSTS_FILE_PATH =
            StringUtils.defaultIfBlank(System.getProperty(JGIT_KNOWN_HOSTS_PROPERTY), KNOWN_HOSTS_DEFAULT);
    /** JGit known hosts file path for the current user.
     * Uses the {@link #KNOWN_HOSTS_DEFAULT default path} to the known hosts file for the current user
     * unless the <code>JGIT_KNOWN_HOSTS_PROPERTY</code> property is
     * set.
     */
    public static final File JGIT_KNOWN_HOSTS_FILE = new File(JGIT_KNOWN_HOSTS_FILE_PATH);

    @Override
    public Descriptor<SshHostKeyVerificationStrategy<T>> getDescriptor() {
        return (Descriptor<SshHostKeyVerificationStrategy<T>>) Jenkins.get().getDescriptorOrDie(getClass());
    }

    /**
     * Returns the ssh host key verifier for this strategy.
     * @return ssh host key verifier for this strategy.
     */
    public abstract T getVerifier();
}
