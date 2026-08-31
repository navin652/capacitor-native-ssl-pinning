// SSLSecurityUtils.java
package com.cap.nativehttp.utils;

import android.annotation.SuppressLint;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Trust-all helpers used only when the request has {@code disableAllSecurity: true} set. Mirrors
 * {@code SSLSecurityUtils.swift}'s {@code trustAllCredential} on iOS. Intentionally accepts any
 * server certificate, bypassing all TLS trust checks -- only use this for local/dev endpoints,
 * never in production.
 */
public class SSLSecurityUtils {

    /** An {@link X509TrustManager} that accepts every certificate chain without validation. */
    @SuppressLint("CustomX509TrustManager")
    public static class TrustAllX509TrustManager implements X509TrustManager {
        /** No-op: accepts any client certificate. */
        @SuppressLint("TrustAllX509TrustManager")
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        /** No-op: accepts any server certificate. */
        @SuppressLint("TrustAllX509TrustManager")
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        /** @return an empty array; this trust manager does not have its own accepted issuers */
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** @return a single-element array containing a fresh {@link TrustAllX509TrustManager} */
    public static TrustManager[] getTrustAllManagers() {
        return new TrustManager[]{new TrustAllX509TrustManager()};
    }

    /**
     * Builds an {@link SSLSocketFactory} backed by the given trust-all managers, for use with
     * {@link okhttp3.OkHttpClient.Builder#sslSocketFactory}.
     *
     * @param trustManagers typically the result of {@link #getTrustAllManagers()}
     * @return a socket factory that performs no certificate validation
     */
    public static SSLSocketFactory getTrustAllSSLSocketFactory(TrustManager[] trustManagers) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustManagers, new SecureRandom());
        return sslContext.getSocketFactory();
    }
}
