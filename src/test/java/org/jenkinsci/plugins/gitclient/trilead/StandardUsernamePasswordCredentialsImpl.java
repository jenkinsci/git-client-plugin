package org.jenkinsci.plugins.gitclient.trilead;

import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.util.Secret;

class StandardUsernamePasswordCredentialsImpl implements StandardUsernamePasswordCredentials {

    private final String userName;
    private final Secret password;

    public StandardUsernamePasswordCredentialsImpl(String userName, Secret password) {
        this.userName = userName;
        this.password = password;
    }

    public String getDescription() {
        throw new UnsupportedOperationException("Should not be called");
    }

    public String getId() {
        throw new UnsupportedOperationException("Should not be called");
    }

    public CredentialsScope getScope() {
        throw new UnsupportedOperationException("Should not be called");
    }

    public CredentialsDescriptor getDescriptor() {
        throw new UnsupportedOperationException("Should not be called");
    }

    public String getUsername() {
        return userName;
    }

    public Secret getPassword() {
        return password;
    }

}
