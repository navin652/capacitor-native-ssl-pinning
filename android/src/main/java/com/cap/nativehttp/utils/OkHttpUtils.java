package com.cap.nativehttp.utils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.cap.nativehttp.BuildConfig;
import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.CertificatePinner;
import okhttp3.CookieJar;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * Created by Max Toyberman on 2/11/18.
 */

public class OkHttpUtils {

    private static final String HEADERS_KEY = "headers";
    private static final String BODY_KEY = "body";
    private static final String METHOD_KEY = "method";
    private static final HashMap<String, OkHttpClient> clientsByDomain = new HashMap<>();
    private static SSLContext sslContext;
    private static String content_type = "application/json; charset=utf-8";
    public static MediaType mediaType = MediaType.parse(content_type);

    public static OkHttpClient buildOkHttpClient(CookieJar cookieJar, String domainName, List<String> certs, JSONObject options) throws JSONException {

        OkHttpClient client = null;
        if (!clientsByDomain.containsKey(domainName)) {
            OkHttpClient.Builder clientBuilder = applyCommonClientConfig(new OkHttpClient.Builder(), cookieJar, options);

            if (options.has("pkPinning") && options.getBoolean("pkPinning")) {
                // public key pinning
                clientBuilder.certificatePinner(initPublicKeyPinning(certs, domainName));
            } else {
                // ssl pinning
                X509TrustManager manager = initSSLPinning(certs);
                clientBuilder
                        .sslSocketFactory(sslContext.getSocketFactory(), manager);
            }
            applyDebugLogging(clientBuilder);

            client = clientBuilder
                    .build();

            clientsByDomain.put(domainName, client);
        } else {
            client = clientsByDomain.get(domainName);
        }

        return applyTimeoutsIfPresent(client, options);
    }

    public static OkHttpClient buildDefaultOkHttpClient(CookieJar cookieJar, String ignoredDomainName, JSONObject options) throws JSONException {
        boolean disableAllSecurity = options.optBoolean("disableAllSecurity", false);

        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();

        if (disableAllSecurity) {
            try {
                TrustManager[] trustAllCerts = SSLSecurityUtils.getTrustAllManagers();
                SSLSocketFactory sslSocketFactory = SSLSecurityUtils.getTrustAllSSLSocketFactory(trustAllCerts);

                clientBuilder = new OkHttpClient.Builder()
                        .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                        .hostnameVerifier((hostname, session) -> true);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create a trust-all OkHttp client", e);
            }
        }

        clientBuilder = applyCommonClientConfig(clientBuilder, cookieJar, options);

        applyDebugLogging(clientBuilder);

        return applyTimeoutsIfPresent(clientBuilder.build(), options);
    }

    public static void applyDebugLogging(OkHttpClient.Builder builder) {
        if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);
            builder.addInterceptor(logging);
        }
    }

    private static OkHttpClient applyTimeoutsIfPresent(OkHttpClient client, JSONObject options) throws JSONException {
        if (!options.has("timeoutInterval")) return client;

        int timeout = options.getInt("timeoutInterval");

        return client.newBuilder()
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .callTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
    }

    private static OkHttpClient.Builder applyCommonClientConfig(OkHttpClient.Builder builder, CookieJar cookieJar, JSONObject options) {
        boolean followRedirects = options.optBoolean("followRedirects", false);
        return builder
                .cookieJar(cookieJar)
                .followRedirects(followRedirects)
                .followSslRedirects(followRedirects);
    }

    private static CertificatePinner initPublicKeyPinning(List<String> pins, String domain) {
        CertificatePinner.Builder certificatePinnerBuilder = new CertificatePinner.Builder();
        //add all keys to the certificates pinner
        for (int i = 0; i < pins.size(); i++) {
            certificatePinnerBuilder.add(domain, pins.get(i));
        }
        return certificatePinnerBuilder.build();
    }

    private static X509TrustManager initSSLPinning(List<String> certs) {
        X509TrustManager trustManager = null;
        try {
            sslContext = SSLContext.getInstance("TLS");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            String keyStoreType = KeyStore.getDefaultType();
            KeyStore keyStore = KeyStore.getInstance(keyStoreType);
            keyStore.load(null, null);

            for (int i = 0; i < certs.size(); i++) {
                String filename = certs.get(i);
                InputStream caInput = new BufferedInputStream(Objects.requireNonNull(OkHttpUtils.class.getClassLoader()).getResourceAsStream("assets/" + filename + ".cer"));
                Certificate ca;
                try {
                    ca = cf.generateCertificate(caInput);
                } finally {
                    caInput.close();
                }
                keyStore.setCertificateEntry(filename, ca);
            }

            String tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(tmfAlgorithm);
            tmf.init(keyStore);

            TrustManager[] trustManagers = tmf.getTrustManagers();
            if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
                throw new IllegalStateException("Unexpected default trust managers:" + Arrays.toString(trustManagers));
            }
            trustManager = (X509TrustManager) trustManagers[0];

            sslContext.init(null, new TrustManager[]{trustManager}, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return trustManager;
    }

    private static boolean isFilePart(JSONArray part) throws JSONException {
        if (!(part.get(1) instanceof JSONObject)) {
            return false;
        }
        JSONObject value = part.getJSONObject(1);
        return value.has("type") && (value.has("uri") || value.has("path") || value.has("data"));
    }

    private static void addFormDataPart(Context context, MultipartBody.Builder multipartBodyBuilder, JSONObject fileData, String key) throws JSONException {
        String type = fileData.optString("type", "application/octet-stream");
        String fileName = fileData.optString("fileName", fileData.optString("name", "upload.bin"));

        if (fileData.has("data")) {
            // Handle base64 file
            String base64Data = fileData.getString("data");
            byte[] fileBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
            RequestBody fileBody = RequestBody.create(fileBytes, MediaType.parse(type));
            multipartBodyBuilder.addFormDataPart(key, fileName, fileBody);
        } else if (fileData.has("uri") || fileData.has("path")) {
            // Handle native file
            Uri fileUri = Uri.parse(fileData.optString("uri", fileData.optString("path", "")));
            try {
                File file = getTempFile(context, fileUri);
                RequestBody fileBody = RequestBody.create(file, MediaType.parse(type));
                multipartBodyBuilder.addFormDataPart(key, fileName, fileBody);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            Log.w("NativeHttp", "No valid file data found for key: " + key);
        }
    }

    private static RequestBody buildFormDataRequestBody(Context context, JSObject formData) throws JSONException {
        MultipartBody.Builder multipartBodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        multipartBodyBuilder.setType((Objects.requireNonNull(MediaType.parse("multipart/form-data"))));
        if (formData.has("_parts")) {
            JSONArray parts = formData.getJSONArray("_parts");
            for (int i = 0; i < parts.length(); i++) {
                JSONArray part = parts.getJSONArray(i);
                String key = "";
                Class<?> aClass = part.get(0).getClass();
                if (aClass.equals(String.class)) {
                    key = part.getString(0);
                } else if (aClass.equals(Integer.class)) {
                    key = String.valueOf(part.getInt(0));
                }
                if (isFilePart(part)) {
                    addFormDataPart(context, multipartBodyBuilder, Objects.requireNonNull(part.getJSONObject(1)), key);
                } else {
                    String value = part.getString(1);
                    multipartBodyBuilder.addFormDataPart(key, Objects.requireNonNull(value));
                }
            }
        }
        return multipartBodyBuilder.build();
    }

    public static Request buildRequest(Context context, JSObject options, String hostname) throws JSONException {

        Request.Builder requestBuilder = new Request.Builder();
        RequestBody body = null;

        String method = "GET";

        if (options.has(HEADERS_KEY)) {
            setRequestHeaders(options, requestBuilder);
        }

        if (options.has(METHOD_KEY)) {
            method = options.getString(METHOD_KEY);
        }

        if (options.has(BODY_KEY)) {
            Class<?> aClass = options.get(BODY_KEY).getClass();
            if (aClass.equals(String.class)) {
                body = RequestBody.create(Objects.requireNonNull(options.getString(BODY_KEY)), mediaType);
            } else if (aClass.equals(JSONObject.class)) {
                JSObject bodyMap = JSObject.fromJSONObject(options.getJSONObject(BODY_KEY));
                if (bodyMap.has("formData")) {
                    JSObject formData = JSObject.fromJSONObject(bodyMap.getJSONObject("formData"));
                    body = buildFormDataRequestBody(context, formData);
                } else if (bodyMap.has("_parts")) {
                    body = buildFormDataRequestBody(context, bodyMap);
                }
            }
        }
        return requestBuilder
                .url(hostname)
                .method(Objects.requireNonNull(method), body)
                .build();
    }

    public static File getTempFile(Context context, Uri uri) throws IOException {
        File tempFile = File.createTempFile("upload_", ".tmp", context.getCacheDir());
        Utilities.copyInputStreamToFile(context.getContentResolver().openInputStream(uri), tempFile);
        TempFileManager.registerTempFile(tempFile);
        return tempFile;
    }

    private static void setRequestHeaders(JSONObject options, Request.Builder requestBuilder) throws JSONException {
        JSONObject map = options.getJSONObject((HEADERS_KEY));
        //add headers to request
        Utilities.addHeadersFromMap(map, requestBuilder);
        if (map.has("content-type")) {
            content_type = map.getString("content-type");
            mediaType = MediaType.parse(content_type);
        }
    }
}