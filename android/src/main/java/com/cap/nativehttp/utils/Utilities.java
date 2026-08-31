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
 * Misc helpers shared across the {@code utils} package: stream copying, domain parsing, response
 * header/directory-key resolution. The iOS counterpart is {@code Utilities.swift}.
 *
 * @author Navinkumar Brijesh Singh
 * @since 12/03/2025
 */
public class Utilities {

    /**
     * Copies all bytes from an {@link InputStream} to a {@link File}, closing both streams
     * afterward.
     *
     * @param in   the source stream (closed by this method)
     * @param file the destination file, created/overwritten
     */
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
     * Copies every key/value pair from a headers object onto an OkHttp request builder.
     *
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


    /**
     * Flattens an OkHttp response's headers into a plain {@code { [name]: value }} object for the
     * plugin result. If a header repeats, only the last value is kept (see
     * {@link Headers#get(String)}).
     *
     * @param okHttpResponse the completed response to read headers from
     * @return the response headers as a JS-friendly object
     */
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

    /**
     * Resolves a {@code Directory} enum key (see {@code src/types.ts}) to a filesystem location for
     * {@code file}/{@code blob} response downloads. Only {@code DOCUMENTS}, {@code CACHE}/
     * {@code TEMPORARY}, {@code EXTERNAL}, {@code EXTERNAL_STORAGE}, and {@code EXTERNAL_CACHE} are
     * handled explicitly; every other key (including {@code DATA} and {@code LIBRARY}/
     * {@code LIBRARY_NO_CLOUD}) falls back to the app's private internal files directory
     * ({@link Context#getFilesDir()}).
     *
     * @param context      used to resolve the platform directory
     * @param directoryKey a {@code Directory} enum value, case-insensitive
     * @return the resolved directory, ready to have a file created inside it
     */
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

    /**
     * Extracts the host from a URL, with a leading {@code "www."} stripped. Used as the cache key
     * for both {@link OkHttpUtils}'s per-domain client cache and {@link CookieManager}'s per-host
     * cookie store, so cookie/pinning lookups are insensitive to a {@code www.} prefix.
     *
     * @param url a full URL (e.g. {@code https://www.example.com/path})
     * @return the host with any leading {@code "www."} removed (e.g. {@code example.com})
     * @throws URISyntaxException if {@code url} is not a valid URI
     */
    public static String getDomainName(String url) throws URISyntaxException {
        URI uri = new URI(url);
        String domain = uri.getHost();
        return domain.startsWith("www.") ? domain.substring(4) : domain;
    }
}