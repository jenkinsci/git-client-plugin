package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.Secret;

class StandardUsernamePasswordCredentialsImpl implements StandardUsernamePasswordCredentials {

    private final String userName;
    private final Secret password;

    public StandardUsernamePasswordCredentialsImpl(String userName, Secret password) {
        this.userName = userName;
        this.password = password;
    }

    @NonNull
    public String getDescription() {
        throw new UnsupportedOperationException("Should not be called");
    }

    @NonNull
    public String getId() {
        throw new UnsupportedOperationException("Should not be called");
    }

    public CredentialsScope getScope() {
        throw new UnsupportedOperationException("Should not be called");
    }

    @NonNull
    public CredentialsDescriptor getDescriptor() {
        throw new UnsupportedOperationException("Should not be called");
    }

    @NonNull
    public String getUsername() {
        return userName;
    }

    @NonNull
    public Secret getPassword() {
        return password;
    }

}
