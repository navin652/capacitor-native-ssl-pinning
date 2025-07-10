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

public class ForwardingCookieHandler extends CookieHandler {
    private static final String VERSION_ZERO_HEADER = "Set-Cookie";
    private static final String VERSION_ONE_HEADER = "Set-Cookie2";
    private static final String COOKIE_HEADER = "Cookie";

    private final Context context;
    @Nullable
    private CookieManager cookieManager;

    public ForwardingCookieHandler(Context context) {
        this.context = context;
    }

    @Override
    public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
        CookieManager cm = getCookieManager();
        if (cm == null) return Collections.emptyMap();

        String cookies = cm.getCookie(uri.toString());
        if (TextUtils.isEmpty(cookies)) return Collections.emptyMap();

        return Collections.singletonMap(COOKIE_HEADER, Collections.singletonList(cookies));
    }

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

    public void clearCookies(PluginCall callback) {
        CookieManager cm = getCookieManager();
        if (cm != null) {
            cm.removeAllCookies(value -> callback.resolve());
        }
    }

    public void addCookies(String url, List<String> cookies) {
        CookieManager cm = getCookieManager();
        if (cm != null && cookies != null) {
            for (String cookie : cookies) {
                cm.setCookie(url, cookie, null); // Async by default
            }
            cm.flush(); // Persist cookies
        }
    }

    private static boolean isCookieHeader(String name) {
        return VERSION_ZERO_HEADER.equalsIgnoreCase(name) || VERSION_ONE_HEADER.equalsIgnoreCase(name);
    }

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

    public Context getContext() {
        return context;
    }
}
