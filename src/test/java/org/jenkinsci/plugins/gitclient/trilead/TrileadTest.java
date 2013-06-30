package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUser;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey.FileOnMasterPrivateKeySource;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.jcraft.jsch.Identity;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import hudson.util.StreamTaskListener;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.storage.file.FileRepository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig.Host;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FS;
import org.jvnet.hudson.test.HudsonTestCase;

import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * @author Kohsuke Kawaguchi
 */
public class TrileadTest extends HudsonTestCase {
    public void testTrileadSsh() throws Exception {
        SshSessionFactory.setInstance(new TrileadSessionFactory());

//        SSHUser sshCred = new BasicSSHUserPassword(
//                        CredentialsScope.SYSTEM, null, System.getProperty("user.name"), null, null);
        SSHUser sshCred = new BasicSSHUserPrivateKey(CredentialsScope.SYSTEM, null,
                "git", new FileOnMasterPrivateKeySource("/home/kohsuke/.ssh/id_rsa"), System.getenv("TRILEAD_PASSPHRASE"), null);
        // TODO: it's very common for URI to override the user name

        FileRepository b = new FileRepositoryBuilder().setWorkTree(new File("/tmp/foo")).build();
        Transport t = Transport.open(b, new URIish("ssh://git@github.com/cloudbees/ami-builder"));
        t.setCredentialsProvider(new SshCredentialsProvider(StreamTaskListener.fromStdout(),sshCred));
        t.setDryRun(true);

        FetchResult result = t.fetch(new TextProgressMonitor(new PrintWriter(System.out)),
                Arrays.asList(new RefSpec("refs/head/*:refs/remotes/origin/*")));
        System.out.println(result);
    }

        /*
            SSH connector: JSch

            JSch uses UserInfo interface to prompt the passphrase. JschSshSessionFactory
            passes CredentialProvider to it by adopting it to CredentialsProviderUserInfo

            JSCh uses UserAuth for various authentication mode succh as public key or password

            JSch Session object uses getConfig() object that controls the optonss.

            SshSessionFactory controls the creation of Session, so this is a good place to
            plug in.

            JschConfigSessionFactory.configure() can be used to tweak the setting

            JSch.identities stores the private keys (see IdentityFile class)
         */
    public static void jsch(String[] args) throws Exception {
        SshSessionFactory.setInstance(new JschConfigSessionFactory() {
            @Override
            protected void configure(Host hc, Session session) {
                session.setConfig("StrictHostKeyChecking","none");
                // to use custom UserAuth class: but this only works if it's not in another classloader
//                session.setConfig("userauth.publickey","classname");


            }

            /**
             * {@link JSch} instance owns the list of identities that it uses, so
             * to use different credentials, one needs to use a different {@link JSch} instance.
             * This method handles that.
             *
             * @see com.jcraft.jsch.IdentityFile  but this is private and not reusable
             * @see Identity
             */
            @Override
            protected JSch getJSch(Host hc, FS fs) throws JSchException {
                return super.getJSch(hc, fs);
            }
        });

        FileRepository b = new FileRepositoryBuilder().setWorkTree(new File("/tmp/foo")).build();
        Transport t = Transport.open(b, new URIish("ssh://localhost/cloudbees/ami-builder.git"));
        t.setDryRun(true);

        FetchResult result = t.fetch(new TextProgressMonitor(new PrintWriter(System.out)),
                Arrays.asList(new RefSpec("refs/head/*:refs/remotes/origin/*")));
        System.out.println(result);

    }

    /**
     * Username/password access control
     */
    public static void usernamePassword(String[] args) throws Exception {
        CredentialsProvider cp = new UsernamePasswordCredentialsProvider(args[0],args[1]);
        FileRepository b = new FileRepositoryBuilder().setWorkTree(new File("/tmp/foo")).build();
        Transport t = Transport.open(b, new URIish("https://github.com/cloudbees/ami-builder.git"));
        t.setCredentialsProvider(cp);
        t.setDryRun(true);

        FetchResult result = t.fetch(new TextProgressMonitor(new PrintWriter(System.out)),
                Arrays.asList(new RefSpec("refs/head/*:refs/remotes/origin/*")));
        System.out.println(result);
    }
}
