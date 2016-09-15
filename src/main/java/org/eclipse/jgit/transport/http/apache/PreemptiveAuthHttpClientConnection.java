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
package org.eclipse.jgit.transport.http.apache;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.HttpParams;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.apache.internal.HttpApacheText;
import org.jenkinsci.plugins.gitclient.trilead.SmartCredentialsProvider;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.NoSuchAlgorithmException;

/**
 * A {@link HttpConnection} which uses {@link HttpClient} and attempts to
 * authenticate preemptively.
 */
public class PreemptiveAuthHttpClientConnection extends HttpClientConnection {

    private TemporaryBufferEntity entity;

    private boolean isUsingProxy = false;

    private Proxy proxy;

    private Integer timeout = null;

    private Integer readTimeout;

    private Boolean followRedirects;

    private X509HostnameVerifier hostnameverifier;

    private final SmartCredentialsProvider credentialsProvider;

    public PreemptiveAuthHttpClientConnection(final SmartCredentialsProvider credentialsProvider, final String urlStr) {
        this(credentialsProvider, urlStr, null);
    }

    public PreemptiveAuthHttpClientConnection(final SmartCredentialsProvider credentialsProvider, final String urlStr, final Proxy proxy) {
        this(credentialsProvider, urlStr, proxy, null);
    }

    public PreemptiveAuthHttpClientConnection(final SmartCredentialsProvider credentialsProvider, final String urlStr, final Proxy proxy, final HttpClient cl) {
        super(urlStr, proxy, cl);
        this.credentialsProvider = credentialsProvider;
        this.proxy = proxy;
    }

    private HttpClient getClient() {
        if (client == null)
            client = new DefaultHttpClient();
        HttpParams params = client.getParams();
        if (proxy != null && !Proxy.NO_PROXY.equals(proxy)) {
            isUsingProxy = true;
            InetSocketAddress adr = (InetSocketAddress) proxy.address();
            params.setParameter(ConnRoutePNames.DEFAULT_PROXY,
                    new HttpHost(adr.getHostName(), adr.getPort()));
        }
        if (timeout != null)
            params.setIntParameter(CoreConnectionPNames.CONNECTION_TIMEOUT,
                    timeout.intValue());
        if (readTimeout != null)
            params.setIntParameter(CoreConnectionPNames.SO_TIMEOUT,
                    readTimeout.intValue());
        if (followRedirects != null)
            params.setBooleanParameter(ClientPNames.HANDLE_REDIRECTS,
                    followRedirects.booleanValue());
        if (hostnameverifier != null) {
            SSLSocketFactory sf;
            sf = new SSLSocketFactory(getSSLContext(), hostnameverifier);
            Scheme https = new Scheme("https", 443, sf); //$NON-NLS-1$
            client.getConnectionManager().getSchemeRegistry().register(https);
        }

        // TODO: configure authentication
        return client;
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

    @Override
    public boolean usingProxy() {
        return isUsingProxy;
    }

    @Override
    public void connect() throws IOException {
        execute();
    }
}
