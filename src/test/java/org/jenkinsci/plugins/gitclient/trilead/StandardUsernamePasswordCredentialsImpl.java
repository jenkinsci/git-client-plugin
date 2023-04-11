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

    @Override
    @NonNull
    public String getDescription() {
        throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    @NonNull
    public String getId() {
        throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    public CredentialsScope getScope() {
        throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    @NonNull
    public CredentialsDescriptor getDescriptor() {
        throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    @NonNull
    public String getUsername() {
        return userName;
    }

    @Override
    @NonNull
    public Secret getPassword() {
        return password;
    }
}
