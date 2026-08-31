package com.cap.nativehttp.utils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
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
 * Builds and caches {@link OkHttpClient}s for pinned and unpinned ({@code disableAllSecurity})
 * requests, and builds the OkHttp {@link Request} (headers, body, multipart) for a {@code fetch()}
 * call. One client is built and cached per domain ({@link #clientsByDomain}) and reused for the
 * plugin's lifetime, so pinning setup only happens once per host -- see {@link #clearClientCache}
 * for how to invalidate that cache (e.g. after certificate rotation). The iOS counterpart is
 * {@code URLSessionUtils.swift}.
 */
public class OkHttpUtils {

    private static final String HEADERS_KEY = "headers";
    private static final String BODY_KEY = "body";
    private static final String METHOD_KEY = "method";

    /** Pinned/unpinned {@link OkHttpClient}s, cached by domain name for the plugin's lifetime. */
    private static final HashMap<String, OkHttpClient> clientsByDomain = new HashMap<>();
    /** The {@link SSLContext} built for the most recent certificate-pinning setup. */
    private static SSLContext sslContext;
    private static String content_type = "application/json; charset=utf-8";
    /** Default request body {@link MediaType}; overridden by an explicit {@code content-type} header. */
    public static MediaType mediaType = MediaType.parse(content_type);
    /** When {@code true}, newly-built {@link OkHttpClient}s log full request/response bodies. Toggled via {@code toggleLogging}. */
    public static Boolean enableDebugLogging = false;

    private static final String SSL_SOURCE_KEY = "source";
    private static final String SSL_SOURCE_FILESYSTEM = "filesystem";

    /**
     * Builds (or returns the cached) pinned {@link OkHttpClient} for a domain, selecting
     * certificate pinning or public-key pinning based on {@code options.pkPinning}.
     *
     * @param context    used to resolve filesystem-backed certificate references (see
     *                   {@link #openCertificateStream})
     * @param cookieJar  the cookie jar to attach to a newly-built client
     * @param domainName the request's domain, used as the client cache key
     * @param certs      the {@code sslPinning.certs} entries (asset names/paths, filesystem paths,
     *                   or {@code sha256/...} public-key pins, depending on {@code pkPinning})
     * @param options    the full request options, consulted for {@code pkPinning}, redirects,
     *                   logging, and timeouts
     * @return a client configured for the given domain's pinning mode, with any per-request
     *         timeout override applied
     */
    public static OkHttpClient buildOkHttpClient(Context context, CookieJar cookieJar, String domainName, List<String> certs, JSONObject options) throws JSONException, CertificateException, NoSuchAlgorithmException, KeyStoreException, IOException, KeyManagementException {

        OkHttpClient client = null;
        if (!clientsByDomain.containsKey(domainName)) {
            OkHttpClient.Builder clientBuilder = applyCommonClientConfig(new OkHttpClient.Builder(), cookieJar, options);

            if (options.has("pkPinning") && options.getBoolean("pkPinning")) {
                // public key pinning
                clientBuilder.certificatePinner(initPublicKeyPinning(certs, domainName));
            } else {
                // ssl pinning
                X509TrustManager manager = initSSLPinning(context, certs, options);
                clientBuilder
                        .sslSocketFactory(sslContext.getSocketFactory(), manager);
            }
            if (enableDebugLogging)
                applyDebugLogging(clientBuilder);

            client = clientBuilder
                    .build();

            clientsByDomain.put(domainName, client);
        } else {
            client = clientsByDomain.get(domainName);
        }

        return applyTimeoutsIfPresent(client, options);
    }

    /**
     * Builds a client for the {@code disableAllSecurity} path: no pinning, and (when
     * {@code disableAllSecurity} is {@code true}) a trust-all {@link SSLSocketFactory}/hostname
     * verifier from {@link SSLSecurityUtils} that accepts any server certificate. This client is
     * never cached in {@link #clientsByDomain} -- it is rebuilt on every call.
     *
     * @param cookieJar         the cookie jar to attach to the client
     * @param ignoredDomainName unused; kept for call-site symmetry with {@link #buildOkHttpClient}
     * @param options           the full request options, consulted for {@code disableAllSecurity},
     *                          redirects, logging, and timeouts
     * @return a new client, trust-all if {@code disableAllSecurity} is set, otherwise using default
     *         platform TLS trust
     */
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

        if (enableDebugLogging)
            applyDebugLogging(clientBuilder);

        return applyTimeoutsIfPresent(clientBuilder.build(), options);
    }

    /**
     * Adds an OkHttp {@link HttpLoggingInterceptor} at {@code BODY} level to a client builder, so
     * every request/response (including bodies) is logged. Only applied when
     * {@link #enableDebugLogging} is {@code true} at build time -- see {@code toggleLogging} in
     * {@link com.cap.nativehttp.NativeHttpPlugin}.
     *
     * @param builder the client builder to add the interceptor to, mutated in place
     */
    public static void applyDebugLogging(OkHttpClient.Builder builder) {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);
        builder.addInterceptor(logging);
    }

    /**
     * Derives a client with per-request read/write/connect/call timeouts if {@code timeoutInterval}
     * (milliseconds) is present in the request options, otherwise returns the client unchanged.
     *
     * @param client  the base client (pinned, trust-all, or cached) to derive from
     * @param options the request options, consulted for {@code timeoutInterval}
     * @return the original client, or a copy with the requested timeouts applied
     */
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

    /**
     * Applies the settings shared by every client, pinned or not: the cookie jar and
     * {@code followRedirects}/{@code followSslRedirects} (defaulting to {@code false} when the
     * option is omitted). Applied once at build time, so it is baked into whatever gets cached in
     * {@link #clientsByDomain} for pinned clients.
     *
     * @param builder   the client builder to configure, mutated in place
     * @param cookieJar the cookie jar to attach
     * @param options   the request options, consulted for {@code followRedirects}
     * @return the same builder, for chaining
     */
    private static OkHttpClient.Builder applyCommonClientConfig(OkHttpClient.Builder builder, CookieJar cookieJar, JSONObject options) {
        boolean followRedirects = options.optBoolean("followRedirects", false);
        return builder
                .cookieJar(cookieJar)
                .followRedirects(followRedirects)
                .followSslRedirects(followRedirects);
    }

    /**
     * Builds an OkHttp {@link CertificatePinner} for public-key pinning: every hash in {@code pins}
     * (a {@code sha256/...} SPKI hash, as produced by the openssl pipeline in the root README) is
     * pinned against {@code domain}. OkHttp checks every certificate in the presented chain against
     * these pins, not just the leaf.
     *
     * @param pins   the {@code sha256/...} public-key pins from {@code sslPinning.certs}
     * @param domain the domain the pins apply to
     * @return a {@link CertificatePinner} trusting only chains with a matching pin for {@code domain}
     */
    private static CertificatePinner initPublicKeyPinning(List<String> pins, String domain) {
        CertificatePinner.Builder certificatePinnerBuilder = new CertificatePinner.Builder();
        //add all keys to the certificates pinner
        for (int i = 0; i < pins.size(); i++) {
            certificatePinnerBuilder.add(domain, pins.get(i));
        }
        return certificatePinnerBuilder.build();
    }

    /**
     * Clears the per-domain OkHttpClient pinning cache. Both platforms cache one pinned
     * client/session per domain and reuse it for the plugin lifetime, so certificates replaced on
     * device storage (rotation via `sslPinning.source: "filesystem"`) would otherwise not take
     * effect until the app restarts. Call this after writing new certificate files so the next
     * fetch() for a given domain rebuilds pinning with the fresh certificates.
     */
    public static void clearClientCache() {
        clientsByDomain.clear();
        sslContext = null;
    }

    /**
     * Builds a certificate-pinning {@link X509TrustManager} from a {@link KeyStore} containing only
     * the given certificates: only chains that build up to (or exactly match) one of these
     * certificates are trusted. An empty {@code certs} list produces an empty key store, which
     * trusts nothing -- so {@code sslPinning: { certs: [] }} only makes sense together with
     * {@code disableAllSecurity: true}.
     * <p>
     * Reads {@code options.sslPinning.source} to decide where each entry in {@code certs} comes
     * from: {@code "filesystem"} treats each entry as a path/URI to a certificate file on device
     * storage (see {@link #openCertificateStream}, for runtime pinning rotation); the default,
     * {@code "asset"}, treats each entry as a bundled Android asset path resolved via
     * {@code assets/<entry>.cer} (see the "Where to put .cer files" section of android/README.md).
     *
     * @param context used to open filesystem/content-resolver-backed certificate streams
     * @param certs   the {@code sslPinning.certs} entries (asset paths or filesystem paths/URIs)
     * @param options the full request options, consulted for {@code sslPinning.source}
     * @return a trust manager that trusts only chains anchored by the given certificates
     */
    private static X509TrustManager initSSLPinning(Context context, List<String> certs, JSONObject options) throws NoSuchAlgorithmException, CertificateException, KeyStoreException, IOException, KeyManagementException, JSONException {
        // Determine whether the cert references are bundle asset names (default) or filesystem
        // paths/URIs to certificate files stored on device storage (runtime rotation).
        String source = "asset";
        if (options.has("sslPinning")) {
            JSONObject sslPinning = options.getJSONObject("sslPinning");
            if (sslPinning.has(SSL_SOURCE_KEY) && SSL_SOURCE_FILESYSTEM.equalsIgnoreCase(sslPinning.getString(SSL_SOURCE_KEY))) {
                source = SSL_SOURCE_FILESYSTEM;
            }
        }

        X509TrustManager trustManager = null;
        sslContext = SSLContext.getInstance("TLS");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        String keyStoreType = KeyStore.getDefaultType();
        KeyStore keyStore = KeyStore.getInstance(keyStoreType);
        keyStore.load(null, null);

        for (int i = 0; i < certs.size(); i++) {
            String filename = certs.get(i);
            InputStream caInput;
            if ("filesystem".equals(source)) {
                caInput = openCertificateStream(context, filename);
            } else {
                caInput = new BufferedInputStream(Objects.requireNonNull(OkHttpUtils.class.getClassLoader()).getResourceAsStream("assets/" + filename + ".cer"));
            }
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
        return trustManager;
    }

    /**
     * Opens an InputStream for a filesystem-backed certificate reference. Accepts an absolute
     * filesystem path, a `file://` URI, or a `content://` URI (e.g. as produced by
     * `@capacitor/filesystem` `Filesystem.getUri()`). Content and file schemes are handled through
     * the Android ContentResolver so the file does not need to be world-readable.
     */
    private static InputStream openCertificateStream(Context context, String certRef) throws IOException {
        Uri uri = Uri.parse(certRef);
        if (uri.getScheme() == null) {
            // Bare absolute path, e.g. /data/user/0/<pkg>/files/ssl/mycert.cer
            uri = Uri.fromFile(new File(certRef));
        }
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new IOException("Could not open certificate file: " + certRef);
        }
        return new BufferedInputStream(input);
    }

    /**
     * Determines whether a {@code [key, value]} entry from a {@code _parts} array represents a file
     * (an object with a {@code type} plus one of {@code uri}/{@code path}/{@code data}) rather than
     * a plain string form field.
     *
     * @param part a two-element {@code [key, value]} JSON array from the request body's {@code _parts}
     * @return {@code true} if {@code part[1]} looks like a file descriptor
     */
    private static boolean isFilePart(JSONArray part) throws JSONException {
        if (!(part.get(1) instanceof JSONObject)) {
            return false;
        }
        JSONObject value = part.getJSONObject(1);
        return value.has("type") && (value.has("uri") || value.has("path") || value.has("data"));
    }

    /**
     * Adds one file part to a multipart body, reading its bytes either from a base64 {@code data}
     * field or from a native {@code uri}/{@code path} (copied to a temp file first via
     * {@link #getTempFile}, since OkHttp's streaming {@link RequestBody} needs a {@link File} or
     * byte array, not a content URI directly).
     *
     * @param context               used to resolve and copy a native file URI
     * @param multipartBodyBuilder  the multipart body builder to add the part to, mutated in place
     * @param fileData              the file descriptor: {@code type}, {@code fileName}/{@code name},
     *                              and one of {@code data} (base64) or {@code uri}/{@code path}
     * @param key                   the form field name for this part
     */
    private static void addFormDataPart(Context context, MultipartBody.Builder multipartBodyBuilder, JSONObject fileData, String key) throws JSONException, IOException {
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
                throw new IOException(e);
            }
        } else {
            Log.w("NativeHttp", "No valid file data found for key: " + key);
        }
    }

    /**
     * Builds a {@code multipart/form-data} request body from the {@code { _parts: [[key, value], ...] } }
     * shape produced by {@code src/index.ts}'s web-FormData conversion, or passed directly as
     * Capacitor file objects (see the root README's upload examples). Each part is either a plain
     * string field or a file (see {@link #isFilePart}/{@link #addFormDataPart}).
     *
     * @param context  used to resolve native file URIs for file parts
     * @param formData an object with a {@code _parts} array of {@code [key, value]} pairs
     * @return the assembled multipart body
     */
    private static RequestBody buildFormDataRequestBody(Context context, JSObject formData) throws JSONException, IOException {
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

    /**
     * Builds the OkHttp {@link Request} for a {@code fetch()} call: method, headers, and body.
     * A string body is sent as-is (using {@link #mediaType}, updated from a {@code content-type}
     * header if one was provided). An object body is only handled when it has a {@code formData} or
     * {@code _parts} key (built into a multipart body via {@link #buildFormDataRequestBody}) --
     * any other object-shaped body is intentionally left bodyless; callers are expected to
     * {@code JSON.stringify()} JSON bodies before calling {@code fetch()}.
     *
     * @param context  used to resolve native file URIs in a multipart body
     * @param options  the request options: {@code method}, {@code headers}, {@code body}
     * @param hostname the request URL
     * @return the built request, ready to be run on a client from {@link #buildOkHttpClient}/
     *         {@link #buildDefaultOkHttpClient}
     */
    public static Request buildRequest(Context context, JSObject options, String hostname) throws JSONException, IOException {

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

    /**
     * Copies the content behind a native file {@link Uri} (e.g. a {@code content://} URI from
     * {@code @capacitor/filesystem}) into a temp file in the app's cache directory, so it can be
     * attached to a multipart body as a {@link File}. Registers the temp file with
     * {@link TempFileManager} so it is deleted once the request finishes (success or failure).
     *
     * @param context used to resolve the URI via the content resolver
     * @param uri     the native file URI to copy
     * @return the created temp file, already registered for cleanup
     */
    public static File getTempFile(Context context, Uri uri) throws IOException {
        File tempFile = File.createTempFile("upload_", ".tmp", context.getCacheDir());
        Utilities.copyInputStreamToFile(context.getContentResolver().openInputStream(uri), tempFile);
        TempFileManager.registerTempFile(tempFile);
        return tempFile;
    }

    /**
     * Copies the {@code headers} map from the request options onto the OkHttp request builder. If a
     * {@code content-type} header (lowercase key) is present, it also updates {@link #mediaType} /
     * {@link #content_type}, which determines the {@code Content-Type} used when encoding a raw
     * string body.
     *
     * @param options        the request options, expected to have a {@code headers} object
     * @param requestBuilder the request builder to add headers to, mutated in place
     */
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