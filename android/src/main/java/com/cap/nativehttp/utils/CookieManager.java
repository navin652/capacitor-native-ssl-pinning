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

public class CookieManager implements CookieJar {
    private final Map<String, List<Cookie>> cookieStore = new HashMap<>();
    private final ForwardingCookieHandler cookieHandler;

    public CookieManager(ForwardingCookieHandler cookieHandler) {
        this.cookieHandler = cookieHandler;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public synchronized void saveFromResponse(@NonNull HttpUrl httpUrl, @NonNull List<Cookie> cookies) {
        for (Cookie cookie : cookies) {
            setCookie(httpUrl, cookie);
        }
    }

    @NonNull
    @Override
    public synchronized List<Cookie> loadForRequest(@NonNull HttpUrl httpUrl) {
        List<Cookie> cookies = cookieStore.get(httpUrl.host());
        return cookies != null ? cookies : new ArrayList<>();
    }

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

