package org.jenkinsci.plugins.gitclient.verifier;

import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.io.File;
import java.nio.file.Path;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

public abstract class SshHostKeyVerificationStrategy<T extends HostKeyVerifierFactory>
        extends AbstractDescribableImpl<SshHostKeyVerificationStrategy<T>> implements ExtensionPoint {

    public static final String KNOWN_HOSTS_DEFAULT =
            Path.of(System.getProperty("user.home"), ".ssh", "known_hosts").toString();
    private static final String JGIT_KNOWN_HOSTS_PROPERTY =
            SshHostKeyVerificationStrategy.class.getName() + ".jgit_known_hosts_file";
    private static final String JGIT_KNOWN_HOSTS_FILE_PATH =
            StringUtils.defaultIfBlank(System.getProperty(JGIT_KNOWN_HOSTS_PROPERTY), KNOWN_HOSTS_DEFAULT);
    public static final File JGIT_KNOWN_HOSTS_FILE = new File(JGIT_KNOWN_HOSTS_FILE_PATH);

    @Override
    public Descriptor<SshHostKeyVerificationStrategy<T>> getDescriptor() {
        return (Descriptor<SshHostKeyVerificationStrategy<T>>) Jenkins.get().getDescriptorOrDie(getClass());
    }

    public abstract T getVerifier();
}
