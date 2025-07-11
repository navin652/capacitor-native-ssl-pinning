package com.cap.nativehttp;

import android.content.Context;

import com.cap.nativehttp.utils.CookieManager;
import com.cap.nativehttp.utils.ForwardingCookieHandler;
import com.cap.nativehttp.utils.HttpFetcher;
import com.cap.nativehttp.utils.OkHttpUtils;
import com.cap.nativehttp.utils.TempFileManager;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONException;

import java.io.IOException;
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
            call.reject("Invalid request JSON", e.getMessage());
        } catch (IOException e) {
            call.reject("File Exception", e.getMessage());
        } catch (Exception e) {
            call.reject("Unexpected error occurred : ", e.getMessage());
        }
    }

    @PluginMethod
    public void getCookies(PluginCall call) {
        try {
            cookieManager.getCookies(call);
        } catch (URISyntaxException e) {
            call.reject(e.getMessage());
        } catch (Exception e) {
            call.reject("Unexpected error occurred", e);
        }
    }

    @PluginMethod
    public void removeCookieByName(PluginCall call) {
        cookieManager.removeCookieByName(call);
    }

    @PluginMethod
    public void toggleLogging(PluginCall call) {
        OkHttpUtils.enableDebugLogging = call.getBoolean("enableLogging",false);
    }
}

