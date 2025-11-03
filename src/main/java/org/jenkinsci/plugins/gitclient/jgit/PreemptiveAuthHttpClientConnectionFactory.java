package org.jenkinsci.plugins.gitclient.jgit;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;

public class PreemptiveAuthHttpClientConnectionFactory implements HttpConnectionFactory {

    private static final String NEED_CREDENTIALS_PROVIDER =
            "The " + PreemptiveAuthHttpClientConnectionFactory.class.getName()
                    + " needs to be provided a credentials provider";

    private SmartCredentialsProvider provider;

    public PreemptiveAuthHttpClientConnectionFactory(SmartCredentialsProvider provider) {
        this.provider = provider;
    }

    @Override
    public HttpConnection create(final URL url) throws IOException {
        return innerCreate(url, null);
    }

    @Override
    public HttpConnection create(final URL url, final Proxy proxy) throws IOException {
        return innerCreate(url, null);
    }

    protected HttpConnection innerCreate(final URL url, final Proxy proxy) {
        return new PreemptiveAuthHttpClientConnection(this.provider, url.toString(), proxy);
    }
}
