import Foundation
import Capacitor

/// Cookie get/remove for the NativeHttp plugin. Mirrors
/// android/src/main/java/com/cap/nativehttp/utils/CookieManager.java, but is simpler on iOS: since
/// URLSessionUtils configures every session's `httpCookieStorage` to `HTTPCookieStorage.shared`,
/// that same shared store already has the cookies a fetch() call received -- there's no need for a
/// separate forwarding step like Android's ForwardingCookieHandler (which exists there to mirror
/// OkHttp's in-memory cookie jar into android.webkit.CookieManager for WebView visibility).
final class CookieManager {
    func getCookies(_ call: CAPPluginCall) {
        guard let domain = call.getString("domain") else {
            call.reject("Missing required parameter: domain")
            return
        }

        let host = Utilities.domainName(from: domain)
        var result: [String: String] = [:]

        for cookie in HTTPCookieStorage.shared.cookies ?? [] where normalizedDomain(cookie.domain) == host {
            result[cookie.name] = cookie.value
        }

        call.resolve(result)
    }

    func removeCookieByName(_ call: CAPPluginCall) {
        guard let cookieName = call.getString("cookieName") else {
            call.reject("Missing required parameter: cookieName")
            return
        }

        let storage = HTTPCookieStorage.shared
        for cookie in storage.cookies ?? [] where cookie.name == cookieName {
            storage.deleteCookie(cookie)
        }
        call.resolve()
    }

    private func normalizedDomain(_ domain: String) -> String {
        domain.hasPrefix(".") ? String(domain.dropFirst()) : domain
    }
}
