package com.cap.nativehttp.utils;

// HttpFetcher.java

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Orchestrates a single {@code fetch()} call: picks the right {@link OkHttpClient} for the
 * request's security options, runs it asynchronously, and shapes the response. The iOS counterpart
 * is {@code HttpFetcher.swift}.
 */
public class HttpFetcher {

    private final Context context;
    private final CookieManager cookieManager;
    private static final String DISABLE_ALL_SECURITY = "disableAllSecurity";
    private static final String OPT_SSL_PINNING_KEY = "sslPinning";
    private static final String RESPONSE_TYPE = "responseType";

    /**
     * @param context       used to build OkHttp clients/requests (file resolution, content resolver)
     * @param cookieManager the shared cookie jar, reused by every client built for this plugin instance
     */
    public HttpFetcher(Context context, CookieManager cookieManager) {
        this.context = context;
        this.cookieManager = cookieManager;
    }

    /**
     * Runs an HTTP request with SSL/public-key pinning and resolves/rejects {@code call}
     * asynchronously once it completes.
     * <p>
     * Client selection precedence: {@code disableAllSecurity: true} always wins (trust-all, no
     * pinning); otherwise {@code sslPinning.certs} is required, and pinning mode (certificate vs.
     * public-key) is chosen by {@code pkPinning}; if neither is present, the call is rejected with
     * {@code "SSL Pinning key not provided"}.
     *
     * @param call the plugin call; expects a {@code url} string and an {@code options} object
     */
    public void fetch(PluginCall call) throws JSONException, IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        String url = call.getString("url");
        JSObject options = call.getObject("options");
        JSObject response = new JSObject();
        String domainName;
        try {
            domainName = Utilities.getDomainName(url);
        } catch (URISyntaxException e) {
            domainName = url;
        }

        OkHttpClient client = null;
        if (options.optBoolean(DISABLE_ALL_SECURITY, false)) {
            client = OkHttpUtils.buildDefaultOkHttpClient(cookieManager, domainName, options);
        } else if (options.has(OPT_SSL_PINNING_KEY)) {
            JSONArray certsJson = ((JSONObject) options.get(OPT_SSL_PINNING_KEY)).getJSONArray("certs");
            List<String> certs = new ArrayList<>();
            for (int i = 0; i < certsJson.length(); i++) {
                certs.add(certsJson.getString(i));
            }
            client = OkHttpUtils.buildOkHttpClient(context, cookieManager, domainName, certs, options);
        } else {
            call.reject("SSL Pinning key not provided");
            return;
        }

        Request request = OkHttpUtils.buildRequest(context, options, url);
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call_, @NonNull IOException e) {
                TempFileManager.cleanup();
                call.reject("Error in network request", e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call_, @NonNull Response okHttpResponse) {
                try {
                    handleResponse(call, options, okHttpResponse, response);
                } finally {
                    TempFileManager.cleanup();
                }
            }
        });
    }

    /**
     * Shapes an OkHttp {@link Response} into the plugin's result and resolves/rejects {@code call}.
     * Behavior depends on {@code options.responseType}: {@code file}/{@code blob} write the body to
     * disk (under the directory resolved by {@link Utilities#resolveDirectory}) and resolve with
     * {@code fileDetails: { path, mimeType }}; {@code base64} resolves with
     * {@code fileDetails: { data, mimeType }}; the default, {@code text}, resolves with
     * {@code bodyString}. Every response also includes {@code headers} and {@code status}. A
     * non-2xx status rejects with code {@code "API Response"} and the built response as the message.
     *
     * @param call           the plugin call to resolve/reject
     * @param options        the request options, consulted for {@code responseType},
     *                       {@code fileSaveDirectory}, {@code fileName}
     * @param okHttpResponse the completed OkHttp response
     * @param response       the result object being built up; mutated in place before resolving
     */
    private void handleResponse(PluginCall call, JSObject options, Response okHttpResponse, JSObject response) {
        ResponseBody body = okHttpResponse.body();

        try (body) {
            if (body == null) {
                call.reject("Empty response body");
                return;
            }
            String responseType = options.optString(RESPONSE_TYPE, "text");
            if ("file".equals(responseType) || "blob".equals(responseType)) {
                String dirName = options.optString("fileSaveDirectory", "DATA");
                File baseDir = Utilities.resolveDirectory(context, dirName);
                File file = new File(
                        baseDir,
                        options.optString("fileName", System.currentTimeMillis() + ".bin")
                );
                // Check write permission
                if (!Objects.requireNonNull(file.getParentFile()).canWrite()) {
                    Log.e("HttpFetcher", "Cannot write to path: " + file.getAbsolutePath());
                    call.reject("WRITE_PERMISSION_DENIED", "App lacks permission to write to: " + baseDir.getAbsolutePath());
                    return;
                }
                Utilities.copyInputStreamToFile(body.byteStream(), file);
                JSObject fileDetails = new JSObject();
                fileDetails.put("path", file.getAbsolutePath());
                fileDetails.put("mimeType", body.contentType() != null ? Objects.requireNonNull(body.contentType()).toString() : "application/octet-stream");
                response.put("fileDetails", fileDetails);
            } else if ("base64".equals(responseType)) {
                byte[] bytes = body.bytes();
                String base64 = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                        ? android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                        : Base64.getEncoder().encodeToString(bytes);
                JSObject fileDetails = new JSObject();
                fileDetails.put("data", base64);
                fileDetails.put("mimeType", body.contentType() != null ? Objects.requireNonNull(body.contentType()).toString() : "application/octet-stream");
                response.put("fileDetails", fileDetails);
            } else {
                response.put("bodyString", body.string());
            }

            response.put("headers", Utilities.buildResponseHeaders(okHttpResponse));
            response.put("status", okHttpResponse.code());

            if (okHttpResponse.isSuccessful()) {
                call.resolve(response);
            } else {
                call.reject("API Response", String.valueOf(response));
            }
        } catch (Exception e) {
            call.reject("Unexpected error occurred : ", e.getMessage());
        }
    }
}
