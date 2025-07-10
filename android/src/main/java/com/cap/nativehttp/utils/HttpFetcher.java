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

public class HttpFetcher {

    private final Context context;
    private final CookieManager cookieManager;
    private static final String DISABLE_ALL_SECURITY = "disableAllSecurity";
    private static final String OPT_SSL_PINNING_KEY = "sslPinning";
    private static final String RESPONSE_TYPE = "responseType";

    public HttpFetcher(Context context, CookieManager cookieManager) {
        this.context = context;
        this.cookieManager = cookieManager;
    }

    public void fetch(PluginCall call) throws JSONException {
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
            client = OkHttpUtils.buildOkHttpClient(cookieManager, domainName, certs, options);
        } else {
            call.reject("SSL Pinning key not provided");
            return;
        }

        Request request = OkHttpUtils.buildRequest(context, options, url);
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call_, @NonNull IOException e) {
                TempFileManager.cleanup();
                call.reject(e.getMessage(), e);
            }

            @Override
            public void onResponse(@NonNull Call call_, @NonNull Response okHttpResponse) throws IOException {
                try {
                    handleResponse(call, options, okHttpResponse, response);
                } finally {
                    TempFileManager.cleanup();
                }
            }
        });
    }

    private void handleResponse(PluginCall call, JSObject options, Response okHttpResponse, JSObject response) throws IOException {
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
                call.reject("Request failed", response);
            }
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }
}
