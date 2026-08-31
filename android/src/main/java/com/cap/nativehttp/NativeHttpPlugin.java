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

/**
 * Capacitor plugin entry point for {@code NativeHttp} (SSL/public-key pinning HTTP requests).
 * <p>
 * This class only wires each plugin call to the {@code utils} package, which owns all the actual
 * behavior: request/client building and pinning ({@link OkHttpUtils}), fetch orchestration and
 * response shaping ({@link HttpFetcher}), cookies ({@link CookieManager} /
 * {@link ForwardingCookieHandler}), and temp-file cleanup for uploads ({@link TempFileManager}).
 * The iOS counterpart ({@code NativeHttpPlugin.swift}) mirrors this class and package
 * feature-for-feature.
 */
@CapacitorPlugin(name = "NativeHttp")
public class NativeHttpPlugin extends Plugin {
    private CookieManager cookieManager;
    private HttpFetcher httpFetcher;

    /**
     * Creates the plugin's {@link CookieManager}/{@link HttpFetcher} pair, scoped to the Android
     * {@link Context} of the Capacitor bridge. Called once per plugin instance before any
     * {@code @PluginMethod} is invoked.
     */
    @Override
    public void load() {
        super.load();
        Context mContext = getBridge().getContext();
        cookieManager = new CookieManager(new ForwardingCookieHandler(mContext));
        httpFetcher = new HttpFetcher(mContext, cookieManager);
    }

    /**
     * Deletes any temp files created for native file uploads ({@link TempFileManager}) that are
     * still pending cleanup when the plugin/bridge is torn down.
     */
    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        TempFileManager.cleanup();
    }

    /**
     * Performs an HTTP request with SSL/public-key pinning. See {@link HttpFetcher#fetch} for the
     * full behavior (client/session selection, request building, response shaping).
     *
     * @param call the plugin call; expects {@code url} and {@code options} (see
     *             {@code NativeSSLPinning.Options} on the JS side)
     */
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

    /**
     * Resolves with the in-memory cookies stored for a domain. See
     * {@link CookieManager#getCookies}.
     *
     * @param call the plugin call; expects a {@code domain} string
     */
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

    /**
     * Removes every stored cookie with the given name, across all domains. See
     * {@link CookieManager#removeCookieByName}.
     *
     * @param call the plugin call; expects a {@code cookieName} string
     */
    @PluginMethod
    public void removeCookieByName(PluginCall call) {
        cookieManager.removeCookieByName(call);
    }

    /**
     * Enables or disables verbose OkHttp request/response logging ({@link OkHttpUtils#applyDebugLogging}).
     * Only affects {@link okhttp3.OkHttpClient}s built after this call -- clients already cached
     * per domain in {@link OkHttpUtils} are unaffected until the app restarts or
     * {@link #clearCertificateCache} is called.
     *
     * @param call the plugin call; expects a boolean {@code enableLogging} (defaults to {@code false})
     */
    @PluginMethod
    public void toggleLogging(PluginCall call) {
        OkHttpUtils.enableDebugLogging = call.getBoolean("enableLogging",false);
    }

    /**
     * Clears the per-domain cached {@link okhttp3.OkHttpClient}s ({@link OkHttpUtils#clearClientCache}).
     * Call this after writing new certificate files to device storage (runtime pinning rotation via
     * {@code sslPinning.source: "filesystem"}) so the next {@code fetch()} for a domain rebuilds
     * pinning with the fresh certificates, instead of reusing the stale cached client.
     *
     * @param call the plugin call; takes no parameters and always resolves
     */
    @PluginMethod
    public void clearCertificateCache(PluginCall call) {
        OkHttpUtils.clearClientCache();
        call.resolve();
    }
}

