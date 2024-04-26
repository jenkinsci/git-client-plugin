package org.jenkinsci.plugins.gitclient.jgit;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.jenkinsci.plugins.gitclient.JGitAPIImpl;

public class PreemptiveAuthHttpClientConnectionFactory implements HttpConnectionFactory {

    private static final String NEED_CREDENTIALS_PROVIDER =
            "The " + PreemptiveAuthHttpClientConnectionFactory.class.getName()
                    + " needs to be provided a credentials provider";

    @Override
    public HttpConnection create(final URL url) throws IOException {
        return innerCreate(url, null);
    }

    @Override
    public HttpConnection create(final URL url, final Proxy proxy) throws IOException {
        return innerCreate(url, null);
    }

    protected HttpConnection innerCreate(final URL url, final Proxy proxy) {
        return new PreemptiveAuthHttpClientConnection(JGitAPIImpl.getProvider(), url.toString(), proxy);
    }
}
