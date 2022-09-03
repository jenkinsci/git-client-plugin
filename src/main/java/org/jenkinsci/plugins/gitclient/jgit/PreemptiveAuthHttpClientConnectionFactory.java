package org.jenkinsci.plugins.gitclient.jgit;

import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.jenkinsci.plugins.gitclient.trilead.SmartCredentialsProvider;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;

public class PreemptiveAuthHttpClientConnectionFactory implements HttpConnectionFactory {

    private static final String NEED_CREDENTIALS_PROVIDER = "The " + PreemptiveAuthHttpClientConnectionFactory.class.getName() + " needs to be provided a credentials provider";

    private SmartCredentialsProvider credentialsProvider;

    public HttpConnection create(final URL url) throws IOException {
        return innerCreate(url, null);
    }

    public HttpConnection create(final URL url, final Proxy proxy) throws IOException {
        return innerCreate(url, null);
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Included in interface definition")
    public SmartCredentialsProvider getCredentialsProvider() {
        return credentialsProvider;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Included in interface definition")
    public void setCredentialsProvider(final SmartCredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
    }

    protected HttpConnection innerCreate(final URL url, final Proxy proxy) {
        if (credentialsProvider == null) {
            throw new IllegalStateException(NEED_CREDENTIALS_PROVIDER);
        }

        return new PreemptiveAuthHttpClientConnection(credentialsProvider, url.toString(), proxy);
    }
}
