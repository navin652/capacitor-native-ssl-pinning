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
        CAPPluginMethod(name: "toggleLogging", returnType: CAPPluginReturnPromise)
    ]

    private let httpFetcher = HttpFetcher()
    private let cookieManager = CookieManager()

    @objc func fetch(_ call: CAPPluginCall) {
        httpFetcher.fetch(call)
    }

    @objc func getCookies(_ call: CAPPluginCall) {
        cookieManager.getCookies(call)
    }

    @objc func removeCookieByName(_ call: CAPPluginCall) {
        cookieManager.removeCookieByName(call)
    }

    @objc func toggleLogging(_ call: CAPPluginCall) {
        URLSessionUtils.enableDebugLogging = call.getBool("enableLogging") ?? false
        call.resolve()
    }
}
