package org.jenkinsci.plugins.gitclient;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import jenkins.bouncycastle.api.PEMEncodable;
import org.bouncycastle.util.encoders.UTF8;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.credentialsbinding.BindingDescriptor;
import org.jenkinsci.plugins.credentialsbinding.MultiBinding;
import org.jenkinsci.plugins.credentialsbinding.impl.AbstractOnDiskBinding;
import org.jenkinsci.plugins.credentialsbinding.impl.UnbindableDir;
import org.kohsuke.stapler.DataBoundConstructor;
import sun.nio.cs.UTF_8;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.security.*;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class GitSSHUserPrivateKeyBind extends MultiBinding<SSHUserPrivateKey> {
    /**
     * For use with {@link DataBoundConstructor}.
     *
     * @param credentialsId
     */
    @DataBoundConstructor
    public GitSSHUserPrivateKeyBind(String credentialsId) {
        super(credentialsId);
    }

    @Override
    protected Class<SSHUserPrivateKey> type() {
        return SSHUserPrivateKey.class;
    }

    @Override
    public MultiEnvironment bind(@NonNull Run<?, ?> build, @Nullable FilePath workspace, @Nullable Launcher launcher, @NonNull TaskListener listener) throws IOException, InterruptedException {
        FilePath tempScript= null;
        UnbindableDir keyDir = null;
        Map<String, String> credMap = new LinkedHashMap();
        SSHUserPrivateKey credentials = getCredentials(build);
        SSHScript sshEcho = new SSHScript(getPrivateKey(credentials),getCredentialsId());
        //TODO Create sperate methods for Linux/Unix and Windows
        //Linux/Unix working
        if(workspace != null){
            keyDir = UnbindableDir.create(workspace);
            //TODO "FIlePath to File"
            /*package*/ CliGitAPIImpl  cliGit = new CliGitAPIImpl("git", new File(workspace.toURI()),listener,build.getEnvironment(listener));
            if(cliGit.isAtLeastVersion(2,3,0,0)){
                tempScript = sshEcho.write(credentials,keyDir.getDirPath());
                credMap.put("GIT_SSH_COMMAND",tempScript.getRemote());
                credMap.put("SSH_ASKPASS","");
            }else{
                    tempScript = sshEcho.write(credentials,keyDir.getDirPath());
                    credMap.put("GIT_SSH",tempScript.getRemote());
                    credMap.put("SSH_ASKPASS",tempScript.getRemote());
                }
            }else{
                /// NO WORKSPACE
            }
        //TODO PUT UNDER workspace != null check
        return new MultiEnvironment(credMap,keyDir.getUnbinder());
    }

    @Override
    public Set<String> variables() {
        return null;
    }

     static private String getPrivateKey(SSHUserPrivateKey credentials){
        return credentials.getPrivateKeys().get(0);
    }

    static private String getPassphrase(SSHUserPrivateKey credentials){
        if( credentials.getPassphrase() != null){
            return credentials.getPassphrase().getPlainText();
        }else{
            return null;
        }
    }
    //TODO CHECK SCOPE
    /*package*/PEMEncodable encodeSSHPrivtKey(String privateKey, char[] passphrase) {
         try {
             return PEMEncodable.decode(privateKey,passphrase);
         } catch (IOException | UnrecoverableKeyException e) {
             e.printStackTrace();
             return null;
         }
     }

    /*package*/PEMEncodable unencodeSSHPrivtKey(String privateKey) {
        try {
            return PEMEncodable.decode(privateKey);
        } catch (IOException | UnrecoverableKeyException e) {
            e.printStackTrace();
            return null;
        }
    }

    protected static final class SSHScript extends AbstractOnDiskBinding<SSHUserPrivateKey>{

        protected SSHScript(String variable, String credentialsId) {
            super(variable, credentialsId);
        }

        @Override
        protected FilePath write(SSHUserPrivateKey credentials, FilePath workspace) throws IOException, InterruptedException {
            PEMEncodable key = null;
            FilePath sshEcho = null;
            FilePath keyFile = null;
            GitSSHUserPrivateKeyBind gitSSH = new GitSSHUserPrivateKeyBind(getCredentialsId());
            if(getPassphrase(credentials)!= null){
                //SOME SFTUFF
                try {
                    NewKeySpec myKey = new NewKeySpec();
                    KeyFactory kf = KeyFactory.getInstance(null);
                    PrivateKey k = kf.generatePrivate(myKey);
                    PEMEncodable p = PEMEncodable.create(k);
                    p.encode();
                } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                    e.printStackTrace();
                }

                key = gitSSH.encodeSSHPrivtKey(getPrivateKey(credentials),getPassphrase(credentials).toCharArray());
            }else{
                key = gitSSH.unencodeSSHPrivtKey(getPrivateKey(credentials));
            }
            //TODO Formatting
            keyFile = workspace.createTempFile("keyfile",".key");
            keyFile.write(key.encode(),null);
//            OutputStream out = keyFile.write();
//            out.write(key.toPrivateKey().getEncoded());
//            out.close();
            keyFile.chmod(0400);
            sshEcho = workspace.createTempFile("sshAuth",".sh");
            sshEcho.write("#!/bin/sh\n"
                            + "ssh -i \"" +
                            keyFile.getRemote() +
                            "\" -l \"" + credentials.getUsername() +
                            "\" -o StrictHostKeyChecking=no \"$@\"",null
                    );
            sshEcho.chmod(0500);
            return sshEcho;
        }

        @Override
        protected Class<SSHUserPrivateKey> type() {
            return SSHUserPrivateKey.class;
        }
    }

    protected static final class NewKeySpec implements KeySpec{

    }

    @Symbol("GitSSHPrivateKey")
    @Extension
    public static final class DescriptorImpl extends BindingDescriptor<SSHUserPrivateKey> {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.GitSSHUserPrivateKeyBind_DisplayName();
        }

        @Override
        protected Class<SSHUserPrivateKey> type() {
            return SSHUserPrivateKey.class;
        }

        @Override
        public boolean requiresWorkspace() {
            return true;
        }
    }
}
