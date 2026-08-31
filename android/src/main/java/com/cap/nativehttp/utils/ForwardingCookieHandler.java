package com.cap.nativehttp.utils;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.getcapacitor.PluginCall;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bridges cookies between {@link CookieManager} (this plugin's OkHttp {@link CookieJar}) and
 * Android's own {@link android.webkit.CookieManager} (the store shared with in-app
 * {@code WebView}s), so cookies set by a {@code fetch()} call are also visible to a
 * {@code WebView}, and vice versa. Implemented as a {@link CookieHandler} for API-shape
 * compatibility, but only {@link #get}/{@link #put} are used by {@link CookieManager}.
 */
public class ForwardingCookieHandler extends CookieHandler {
    private static final String VERSION_ZERO_HEADER = "Set-Cookie";
    private static final String VERSION_ONE_HEADER = "Set-Cookie2";
    private static final String COOKIE_HEADER = "Cookie";

    private final Context context;
    @Nullable
    private CookieManager cookieManager;

    /** @param context used to lazily obtain Android's {@link android.webkit.CookieManager} instance */
    public ForwardingCookieHandler(Context context) {
        this.context = context;
    }

    /**
     * Returns the {@code Cookie} header (if any) that Android's WebView cookie store holds for a
     * URI.
     *
     * @param uri     the request URI
     * @param headers unused; part of the {@link CookieHandler} contract
     * @return a single-entry map with the {@code Cookie} header, or an empty map if there are none
     *         or the cookie manager is unavailable
     */
    @Override
    public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
        CookieManager cm = getCookieManager();
        if (cm == null) return Collections.emptyMap();

        String cookies = cm.getCookie(uri.toString());
        if (TextUtils.isEmpty(cookies)) return Collections.emptyMap();

        return Collections.singletonMap(COOKIE_HEADER, Collections.singletonList(cookies));
    }

    /**
     * Forwards any {@code Set-Cookie}/{@code Set-Cookie2} response headers into Android's WebView
     * cookie store (see {@link #addCookies}).
     *
     * @param uri     the URL the response came from
     * @param headers the response headers to scan for cookie headers
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void put(URI uri, Map<String, List<String>> headers) {
        String url = uri.toString();
        headers.forEach((key, values) -> {
            if (key != null && isCookieHeader(key)) {
                addCookies(url, values);
            }
        });
    }

    /**
     * Removes every cookie from Android's WebView cookie store and resolves {@code callback} once
     * done. Not currently wired to a plugin method, but kept for API symmetry.
     *
     * @param callback resolved after the cookies are cleared
     */
    public void clearCookies(PluginCall callback) {
        CookieManager cm = getCookieManager();
        if (cm != null) {
            cm.removeAllCookies(value -> callback.resolve());
        }
    }

    /**
     * Sets each raw {@code Set-Cookie} header value on Android's WebView cookie store for the given
     * URL, then flushes them to persistent storage.
     *
     * @param url     the URL the cookies apply to
     * @param cookies raw {@code Set-Cookie} header values (e.g. {@code "name=value; Path=/"})
     */
    public void addCookies(String url, List<String> cookies) {
        CookieManager cm = getCookieManager();
        if (cm != null && cookies != null) {
            for (String cookie : cookies) {
                cm.setCookie(url, cookie, null); // Async by default
            }
            cm.flush(); // Persist cookies
        }
    }

    /**
     * @param name a response header name
     * @return {@code true} if the header is {@code Set-Cookie} or {@code Set-Cookie2} (case-insensitive)
     */
    private static boolean isCookieHeader(String name) {
        return VERSION_ZERO_HEADER.equalsIgnoreCase(name) || VERSION_ONE_HEADER.equalsIgnoreCase(name);
    }

    /**
     * Lazily obtains Android's {@link android.webkit.CookieManager} singleton. Returns {@code null}
     * (rather than throwing) if it can't be obtained, e.g. because the device's WebView provider is
     * missing or broken -- callers treat a {@code null} manager as "no cookie forwarding available".
     *
     * @return the shared WebView cookie manager, or {@code null} if unavailable
     */
    @Nullable
    private CookieManager getCookieManager() {
        if (cookieManager == null) {
            try {
                cookieManager = CookieManager.getInstance();
            } catch (Exception ignored) {
                return null;
            }
        }
        return cookieManager;
    }

    /** @return the Android {@link Context} this handler was created with */
    public Context getContext() {
        return context;
    }
}
