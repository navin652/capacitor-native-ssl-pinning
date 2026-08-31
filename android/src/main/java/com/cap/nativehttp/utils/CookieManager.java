package com.cap.nativehttp.utils;

// CookieManager.java

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/**
 * OkHttp {@link CookieJar} implementation for the plugin: an in-memory, per-host cookie store,
 * additionally mirrored into Android's {@code WebView} cookie store via
 * {@link ForwardingCookieHandler} so cookies set by a {@code fetch()} call are also visible to any
 * in-app {@code WebView}. {@code getCookies}/{@code removeCookieByName} only look at the in-memory
 * store. The iOS counterpart, {@code CookieManager.swift}, is simpler since it reads/writes
 * {@code HTTPCookieStorage.shared} directly rather than needing a separate forwarding step.
 */
public class CookieManager implements CookieJar {
    private final Map<String, List<Cookie>> cookieStore = new HashMap<>();
    private final ForwardingCookieHandler cookieHandler;

    /** @param cookieHandler used to mirror cookies into Android's WebView cookie store */
    public CookieManager(ForwardingCookieHandler cookieHandler) {
        this.cookieHandler = cookieHandler;
    }

    /**
     * OkHttp {@link CookieJar} callback: stores every cookie from a response for later requests
     * (see {@link #setCookie}).
     *
     * @param httpUrl the URL the response came from
     * @param cookies the cookies to persist, from the response's {@code Set-Cookie} header(s)
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public synchronized void saveFromResponse(@NonNull HttpUrl httpUrl, @NonNull List<Cookie> cookies) {
        for (Cookie cookie : cookies) {
            setCookie(httpUrl, cookie);
        }
    }

    /**
     * OkHttp {@link CookieJar} callback: returns the cookies previously stored for a URL's host.
     *
     * @param httpUrl the URL about to be requested
     * @return the stored cookies for that host, or an empty list if none
     */
    @NonNull
    @Override
    public synchronized List<Cookie> loadForRequest(@NonNull HttpUrl httpUrl) {
        List<Cookie> cookies = cookieStore.get(httpUrl.host());
        return cookies != null ? cookies : new ArrayList<>();
    }

    /**
     * Stores (or replaces) one cookie in the in-memory store, keyed by host, and mirrors it into
     * Android's WebView cookie store via {@link #cookieHandler}. A cookie with the same name and
     * path as an existing one replaces it, matching standard cookie-jar overwrite semantics.
     *
     * @param url    the URL the cookie was set for
     * @param cookie the cookie to store
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    private void setCookie(HttpUrl url, Cookie cookie) {
        String host = url.host();
        List<Cookie> cookieList = null;
        cookieList = cookieStore.computeIfAbsent(host, k -> new ArrayList<>());

        cookieList.removeIf(existingCookie -> existingCookie.name().equals(cookie.name()) && existingCookie.path().equals(cookie.path()));
        cookieList.add(cookie);

        try {
            Map<String, List<String>> cookieMap = new HashMap<>();
            cookieMap.put("Set-cookie", Collections.singletonList(cookie.toString()));
            cookieHandler.put(url.uri(), cookieMap);
        } catch (Exception ignored) {
        }
    }

    /**
     * Resolves {@code call} with the in-memory cookies stored for a domain, as a
     * {@code { [cookieName]: value }} object. Looks up by exact host match (via
     * {@link Utilities#getDomainName}) -- there is no subdomain/suffix matching.
     *
     * @param call the plugin call; expects a {@code domain} string (a full URL or bare host)
     */
    public void getCookies(PluginCall call) throws URISyntaxException {
        String domain = call.getString("domain");
        JSObject cookieMap = new JSObject();
        List<Cookie> cookies = cookieStore.get(Utilities.getDomainName(domain));
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                cookieMap.put(cookie.name(), cookie.value());
            }
        }
        call.resolve(cookieMap);
    }

    /**
     * Removes every stored cookie with the given name, across every host in the in-memory store.
     * Always resolves, even if no cookie with that name existed.
     *
     * @param call the plugin call; expects a {@code cookieName} string
     */
    public void removeCookieByName(PluginCall call) {
        String cookieName = call.getString("cookieName");
        for (Map.Entry<String, List<Cookie>> entry : cookieStore.entrySet()) {
            List<Cookie> filteredCookies = new ArrayList<>();
            for (Cookie cookie : entry.getValue()) {
                if (!cookie.name().equals(cookieName)) {
                    filteredCookies.add(cookie);
                }
            }
            entry.setValue(filteredCookies);
        }
        call.resolve();
    }
}

