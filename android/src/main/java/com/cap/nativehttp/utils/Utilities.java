package com.cap.nativehttp.utils;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.NonNull;

import com.getcapacitor.JSObject;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by Navinkumar Brijesh Singh on 12/03/2025.
 */

public class Utilities {

    // Copy an InputStream to a File.
    public static void copyInputStreamToFile(InputStream in, File file) throws IOException {
        try (InputStream input = in;
             OutputStream output = new FileOutputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = input.read(buf)) != -1) {
                output.write(buf, 0, len);
            }
        }
    }

    /**
     * @param map     - map of headers
     * @param builder - request builder, all headers will be added to this request
     */
    public static void addHeadersFromMap(JSONObject map, Request.Builder builder) throws JSONException {
        Iterator<String> iterator = map.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            builder.addHeader(key, map.getString(key));
        }
    }


    @NonNull
    public static JSObject buildResponseHeaders(Response okHttpResponse) {
        Headers responseHeaders = okHttpResponse.headers();
        Set<String> headerNames = responseHeaders.names();
        JSObject headers = new JSObject();
        for (String header : headerNames) {
            headers.put(header, responseHeaders.get(header));
        }
        return headers;
    }

    public static File resolveDirectory(Context context, String directoryKey) {
        return switch (directoryKey.toUpperCase(Locale.ROOT)) {
            case "DOCUMENTS" -> context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            case "CACHE", "TEMPORARY" -> context.getCacheDir();
            case "EXTERNAL" -> context.getExternalFilesDir(null);
            case "EXTERNAL_STORAGE" -> Environment.getExternalStorageDirectory();
            case "EXTERNAL_CACHE" -> context.getExternalCacheDir();
            default -> context.getFilesDir();
        };
    }

    public static String getDomainName(String url) throws URISyntaxException {
        URI uri = new URI(url);
        String domain = uri.getHost();
        return domain.startsWith("www.") ? domain.substring(4) : domain;
    }
}