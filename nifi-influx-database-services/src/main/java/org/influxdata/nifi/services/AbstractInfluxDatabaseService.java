/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.influxdata.nifi.services;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import edu.umd.cs.findbugs.annotations.NonNull;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.ssl.SSLContextService;

/**
 * @author Jakub Bednar (bednar@github) (11/06/2019 09:50)
 */
abstract class AbstractInfluxDatabaseService extends AbstractControllerService {

    /**
     * The {@link OkHttpClient.Builder} requires the {@link X509TrustManager} to use the SSL connection for that
     * we have to build own {@link SSLContext}.
     *
     * @see org.apache.nifi.security.util.SslContextFactory#createSslContext
     */
    void configureSSL(@NonNull final OkHttpClient.Builder okHttpClient,
                      @NonNull final SSLContextService.ClientAuth clientAuth,
                      @NonNull final SSLContextService sslService)
            throws IOException, GeneralSecurityException {

        Objects.requireNonNull(okHttpClient, "OkHttpClient.Builder is required");
        Objects.requireNonNull(clientAuth, "ClientAuth is required");
        Objects.requireNonNull(sslService, "SSLContextService is required");


        //
        // Load Key and Trust store
        //

        KeyStore keyStore = KeyStore.getInstance(sslService.getKeyStoreType());
        if (sslService.isKeyStoreConfigured()) {

            try (final InputStream is = new FileInputStream(sslService.getKeyStoreFile())) {
                keyStore.load(is, sslService.getKeyStorePassword().toCharArray());
            }
        }

        KeyStore trustStore = KeyStore.getInstance(sslService.getTrustStoreType());
        if (sslService.isTrustStoreConfigured()) {
            try (final InputStream is = new FileInputStream(sslService.getTrustStoreFile())) {
                trustStore.load(is, sslService.getTrustStorePassword().toCharArray());
            }
        }

        //
        // Init Key and Trust managers factory
        //

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        if (sslService.isKeyStoreConfigured()) {
            keyManagerFactory.init(keyStore, sslService.getKeyStorePassword().toCharArray());
        }

        TrustManagerFactory trustManagerFactory = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (sslService.isTrustStoreConfigured()) {
            trustManagerFactory.init(trustStore);
        }

        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
        if (ArrayUtils.isEmpty(trustManagers) || !(trustManagers[0] instanceof X509TrustManager)) {

            String message = String.format("The TrustManagers: '%s' does not contains X509TrustManager "
                    + "which is required to configure SSL Connection by OkHttpClient.", (Object) trustManagers);

            throw new IllegalStateException(message);
        }

        //
        // Build SSL Context
        //
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagers, new SecureRandom());

        // Thx org.apache.nifi.security.util.SslContextFactory#createSslContext
        if (SSLContextService.ClientAuth.REQUIRED == clientAuth) {
            sslContext.getDefaultSSLParameters().setNeedClientAuth(true);
        } else if (SSLContextService.ClientAuth.WANT == clientAuth) {
            sslContext.getDefaultSSLParameters().setWantClientAuth(true);
        } else {
            sslContext.getDefaultSSLParameters().setWantClientAuth(false);
        }

        okHttpClient.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustManagers[0]);
    }
}