import Foundation
import Capacitor

/// Capacitor plugin entry point for NativeHttp on iOS. Mirrors
/// android/src/main/java/com/cap/nativehttp/NativeHttpPlugin.java: this class only wires plugin
/// calls to the Utils/ helpers, all actual behavior lives there (see HttpFetcher, CookieManager,
/// URLSessionUtils, SSLSecurityUtils, Utilities).
@objc(NativeHttpPlugin)
public class NativeHttpPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NativeHttpPlugin"
    public let jsName = "NativeHttp"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "fetch", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCookies", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeCookieByName", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "toggleLogging", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearCertificateCache", returnType: CAPPluginReturnPromise)
    ]

    private let httpFetcher = HttpFetcher()
    private let cookieManager = CookieManager()

    /// Performs an HTTP request with SSL/public-key pinning. See `HttpFetcher.fetch` for the full
    /// behavior (session selection, request building, response shaping).
    ///
    /// - Parameter call: expects `url` (String) and `options` (see `NativeSSLPinning.Options` on
    ///   the JS side)
    @objc func fetch(_ call: CAPPluginCall) {
        httpFetcher.fetch(call)
    }

    /// Resolves with the cookies stored for a domain. See `CookieManager.getCookies`.
    ///
    /// - Parameter call: expects a `domain` String
    @objc func getCookies(_ call: CAPPluginCall) {
        cookieManager.getCookies(call)
    }

    /// Removes every stored cookie with the given name, across all domains. See
    /// `CookieManager.removeCookieByName`.
    ///
    /// - Parameter call: expects a `cookieName` String
    @objc func removeCookieByName(_ call: CAPPluginCall) {
        cookieManager.removeCookieByName(call)
    }

    /// Enables or disables verbose request/response logging (`URLSessionUtils.enableDebugLogging`).
    /// Unlike Android's build-time interceptor, this is checked fresh on every request, so toggling
    /// it takes effect immediately -- including for already-cached per-domain sessions.
    ///
    /// - Parameter call: expects a boolean `enableLogging` (defaults to `false`); always resolves
    @objc func toggleLogging(_ call: CAPPluginCall) {
        URLSessionUtils.enableDebugLogging = call.getBool("enableLogging") ?? false
        call.resolve()
    }

    /// Clears the per-domain cached `URLSession`s (`URLSessionUtils.clearSessionCache`). Call this
    /// after writing new certificate files to device storage (runtime pinning rotation via
    /// `sslPinning.source: "filesystem"`) so the next `fetch()` for a domain rebuilds pinning with
    /// the fresh certificates, instead of reusing the stale cached session.
    ///
    /// - Parameter call: takes no parameters; always resolves
    @objc func clearCertificateCache(_ call: CAPPluginCall) {
        URLSessionUtils.clearSessionCache()
        call.resolve()
    }
}
