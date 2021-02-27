/*
 * Copyright (C) 2013 Christian Halstrick <christian.halstrick@sap.com>
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.jenkinsci.plugins.gitclient.jgit;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.SystemDefaultCredentialsProvider;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.apache.TemporaryBufferEntity;
import org.eclipse.jgit.transport.http.apache.internal.HttpApacheText;
import org.eclipse.jgit.util.TemporaryBuffer;
import org.jenkinsci.plugins.gitclient.trilead.SmartCredentialsProvider;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A {@link HttpConnection} which uses {@link HttpClient} and attempts to
 * authenticate preemptively.
 */
public class PreemptiveAuthHttpClientConnection implements HttpConnection {
    private static final String SLASH = "/";

    HttpClient client;

    String urlStr;

    HttpUriRequest req;

    HttpResponse resp = null;

    String method = "GET";

    private TemporaryBufferEntity entity;

    private boolean isUsingProxy = false;

    private Proxy proxy;

    private Integer timeout = null;

    private Integer readTimeout;

    private Boolean followRedirects;

    @Deprecated
    private org.apache.http.conn.ssl.X509HostnameVerifier hostnameverifier;

    SSLContext ctx;

    private final SmartCredentialsProvider credentialsProvider;

    public PreemptiveAuthHttpClientConnection(final SmartCredentialsProvider credentialsProvider, final String urlStr) {
        this(credentialsProvider, urlStr, null);
    }

    public PreemptiveAuthHttpClientConnection(final SmartCredentialsProvider credentialsProvider, final String urlStr, final Proxy proxy) {
        this(credentialsProvider, urlStr, proxy, null);
    }

    public PreemptiveAuthHttpClientConnection(final SmartCredentialsProvider credentialsProvider, final String urlStr, final Proxy proxy, final HttpClient cl) {
        this.credentialsProvider = credentialsProvider;
        this.urlStr = urlStr;
        this.proxy = proxy;
        this.client = cl;
    }

    static URIish goUp(final URIish uri) {
        final String originalPath = uri.getPath();
        if (originalPath == null || originalPath.length() == 0 || originalPath.equals(SLASH)) {
            return null;
        }
        final int lastSlash;
        if (originalPath.endsWith(SLASH)) {
            lastSlash = originalPath.lastIndexOf(SLASH, originalPath.length() - 2);
        }
        else {
            lastSlash = originalPath.lastIndexOf(SLASH);
        }
        final String pathUpOneLevel = originalPath.substring(0, lastSlash);
        final URIish result;
        if (pathUpOneLevel.length() == 0) {
            result = uri.setPath(null);
        }
        else {
            result = uri.setPath(pathUpOneLevel);
        }
        return result;
    }

    private HttpClient getClient() {
        if (client == null) {
            final HttpClientBuilder builder = HttpClientBuilder.create();
            CredentialItem.Username u = new CredentialItem.Username();
            CredentialItem.Password p = new CredentialItem.Password();
            final URIish serviceUri;
            try {
                serviceUri = new URIish(urlStr);
            }
            catch (final URISyntaxException e) {
                throw new Error(e);
            }
            final HttpHost targetHost = new HttpHost(serviceUri.getHost(), serviceUri.getPort(), serviceUri.getScheme());

            CredentialsProvider clientCredentialsProvider = new SystemDefaultCredentialsProvider();
            if (credentialsProvider.supports(u, p)) {
                URIish uri = serviceUri;
                while(uri != null) {
                    if (credentialsProvider.get(uri, u, p)) {
                        final String userName = u.getValue();
                        final String password = new String(p.getValue());
                        p.clear();
                        final Credentials credentials = createNTCredentials(userName, password);
                        final AuthScope authScope = new AuthScope(targetHost);
                        clientCredentialsProvider = new BasicCredentialsProvider();
                        clientCredentialsProvider.setCredentials(authScope, credentials);
                        break;
                    }
                    uri = goUp(uri);
                }
            }
            builder.setDefaultCredentialsProvider(clientCredentialsProvider);

            if (proxy != null && !Proxy.NO_PROXY.equals(proxy)) {
                isUsingProxy = true;
                configureProxy(builder, proxy);
            }

            final RequestConfig.Builder requestConfigBuilder = RequestConfig.custom();
            if (readTimeout != null)
                requestConfigBuilder.setSocketTimeout(readTimeout);
            if (timeout != null)
                requestConfigBuilder.setConnectTimeout(timeout);
            if (followRedirects != null)
                requestConfigBuilder.setRedirectsEnabled(followRedirects);
            requestConfigBuilder.setAuthenticationEnabled(true);
            final RequestConfig requestConfig = requestConfigBuilder.build();

            builder.setDefaultRequestConfig(requestConfig);

            if (hostnameverifier != null) {
                builder.setSSLHostnameVerifier(hostnameverifier);
            }
            client = builder.build();
        }

        return client;
    }

    static NTCredentials createNTCredentials(final String userName, final String password) {
        final int firstAt = userName.indexOf('@');
        final int firstSlash = userName.indexOf('/');
        final int firstBackSlash = userName.indexOf('\\');

        final String user, domain;
        if (firstAt != -1) {
            // cnorris@walker.example.com
            user = userName.substring(0, firstAt);
            domain = userName.substring(firstAt + 1);
        }
        else if (firstSlash != -1) {
            // WALKER/cnorris
            domain = userName.substring(0, firstSlash);
            user = userName.substring(firstSlash + 1);
        }
        else if (firstBackSlash != -1) {
            // WALKER\cnorris
            domain = userName.substring(0, firstBackSlash);
            user = userName.substring(firstBackSlash + 1);
        }
        else {
            user = userName;
            domain = null;
        }

        return new NTCredentials(user, password, null, domain);
    }

    private static void configureProxy(final HttpClientBuilder builder, final Proxy proxy) {
        if (proxy != null && !Proxy.NO_PROXY.equals(proxy)) {
            final SocketAddress socketAddress = proxy.address();
            if (socketAddress instanceof InetSocketAddress) {
                final InetSocketAddress inetSocketAddress = (InetSocketAddress) socketAddress;
                final String proxyHost = inetSocketAddress.getHostName();
                final int proxyPort = inetSocketAddress.getPort();
                final HttpHost httpHost = new HttpHost(proxyHost, proxyPort);
                builder.setProxy(httpHost);
            }
        }
    }

    private SSLContext getSSLContext() {
        if (ctx == null) {
            try {
                ctx = SSLContext.getInstance("TLS"); //$NON-NLS-1$
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(
                        HttpApacheText.get().unexpectedSSLContextException, e);
            }
        }
        return ctx;
    }

    public void setBuffer(TemporaryBuffer buffer) {
        this.entity = new TemporaryBufferEntity(buffer);
    }

    public int getResponseCode() throws IOException {
        execute();
        return resp.getStatusLine().getStatusCode();
    }

    public URL getURL() {
        try {
            return new URL(urlStr);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    public String getResponseMessage() throws IOException {
        execute();
        return resp.getStatusLine().getReasonPhrase();
    }

    private void execute() throws IOException, ClientProtocolException {
        if (resp == null)
            if (entity != null) {
                if (req instanceof HttpEntityEnclosingRequest) {
                    HttpEntityEnclosingRequest eReq = (HttpEntityEnclosingRequest) req;
                    eReq.setEntity(entity);
                }
                resp = getClient().execute(req);
                entity.getBuffer().close();
                entity = null;
            } else
                resp = getClient().execute(req);
    }

    public Map<String, List<String>> getHeaderFields() {
        Map<String, List<String>> ret = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header hdr : resp.getAllHeaders()) {
            List<String> list = new LinkedList<>();
            for (HeaderElement hdrElem : hdr.getElements())
                list.add(hdrElem.toString());
            ret.put(hdr.getName(), Collections.unmodifiableList(list));
        }
        return Collections.unmodifiableMap(ret);
    }

    public List<String> getHeaderFields(@org.eclipse.jgit.annotations.NonNull String name) {
        Map<String, List<String>> allHeaders = getHeaderFields();
        return allHeaders.get(name);
    }

    public void setRequestProperty(String name, String value) {
        req.addHeader(name, value);
    }

    public void setRequestMethod(String method) throws ProtocolException {
        this.method = method;
        if ("GET".equalsIgnoreCase(method)) //$NON-NLS-1$
            req = new HttpGet(urlStr);
        else if ("PUT".equalsIgnoreCase(method)) //$NON-NLS-1$
            req = new HttpPut(urlStr);
        else if ("POST".equalsIgnoreCase(method)) //$NON-NLS-1$
            req = new HttpPost(urlStr);
        else {
            this.method = null;
            throw new UnsupportedOperationException();
        }
    }

    public void setUseCaches(boolean usecaches) {
        // not needed
    }

    public void setConnectTimeout(int timeout) {
        this.timeout = timeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getContentType() {
        HttpEntity responseEntity = resp.getEntity();
        if (responseEntity != null) {
            Header contentType = responseEntity.getContentType();
            if (contentType != null)
                return contentType.getValue();
        }
        return null;
    }

    public InputStream getInputStream() throws IOException {
        return resp.getEntity().getContent();
    }

    // will return only the first field
    public String getHeaderField(String name) {
        Header header = resp.getFirstHeader(name);
        return (header == null) ? null : header.getValue();
    }

    public int getContentLength() {
        return Integer.parseInt(resp.getFirstHeader("content-length") //$NON-NLS-1$
                .getValue());
    }

    public void setInstanceFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
    }

    public void setDoOutput(boolean dooutput) {
        // TODO: check whether we can really ignore this.
    }

    public void setFixedLengthStreamingMode(int contentLength) {
        if (entity != null)
            throw new IllegalArgumentException();
        entity = new TemporaryBufferEntity(new TemporaryBuffer.LocalFile(null));
        entity.setContentLength(contentLength);
    }

    public OutputStream getOutputStream() throws IOException {
        if (entity == null)
            entity = new TemporaryBufferEntity(new TemporaryBuffer.LocalFile(null));
        return entity.getBuffer();
    }

    public void setChunkedStreamingMode(int chunklen) {
        if (entity == null)
            entity = new TemporaryBufferEntity(new TemporaryBuffer.LocalFile(null));
        entity.setChunked(true);
    }

    public String getRequestMethod() {
        return method;
    }

    public boolean usingProxy() {
        return isUsingProxy;
    }

    public void connect() throws IOException {
        execute();
    }

    public void setHostnameVerifier(final HostnameVerifier hostnameverifier) {
        this.hostnameverifier = new X509HostnameVerifierImpl(hostnameverifier);
    }

    @Deprecated
    private static class X509HostnameVerifierImpl implements org.apache.http.conn.ssl.X509HostnameVerifier {

        private final HostnameVerifier hostnameverifier;

        public X509HostnameVerifierImpl(HostnameVerifier hostnameverifier) {
            this.hostnameverifier = hostnameverifier;
        }

        @Override
        public boolean verify(String hostname, SSLSession session) {
            return hostnameverifier.verify(hostname, session);
        }

        @Override
        public void verify(String host, String[] cns, String[] subjectAlts)
                throws SSLException {
            throw new UnsupportedOperationException("Unsupported hostname verifier called for " + host);
        }

        @Override
        public void verify(String host, X509Certificate cert)
                throws SSLException {
            throw new UnsupportedOperationException("Unsupported hostname verifier called for " + host + " with X.509 certificate");
        }

        @Override
        public void verify(String host, SSLSocket ssl) throws IOException {
            if (!hostnameverifier.verify(host, ssl.getSession())) {
                throw new IOException();
            }
        }
    }

    public void configure(KeyManager[] km, TrustManager[] tm,
                          SecureRandom random) throws KeyManagementException {
        getSSLContext().init(km, tm, random);
    }
}
