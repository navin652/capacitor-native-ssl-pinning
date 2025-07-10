package com.cap.nativehttp;

import android.content.Context;

import com.cap.nativehttp.utils.CookieManager;
import com.cap.nativehttp.utils.ForwardingCookieHandler;
import com.cap.nativehttp.utils.HttpFetcher;
import com.cap.nativehttp.utils.TempFileManager;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONException;

import java.net.URISyntaxException;

@CapacitorPlugin(name = "NativeHttp")
public class NativeHttpPlugin extends Plugin {
    private CookieManager cookieManager;
    private HttpFetcher httpFetcher;

    @Override
    public void load() {
        super.load();
        Context mContext = getBridge().getContext();
        cookieManager = new CookieManager(new ForwardingCookieHandler(mContext));
        httpFetcher = new HttpFetcher(mContext, cookieManager);
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        TempFileManager.cleanup();
    }

    @PluginMethod
    public void fetch(PluginCall call) {
        try {
            httpFetcher.fetch(call);
        } catch (JSONException e) {
            call.reject("Invalid request JSON", e);
        }
    }

    @PluginMethod
    public void getCookies(PluginCall call) {
        try {
            cookieManager.getCookies(call);
        } catch (URISyntaxException e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void removeCookieByName(PluginCall call) {
        cookieManager.removeCookieByName(call);
    }
}

