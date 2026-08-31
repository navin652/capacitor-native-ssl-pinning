import Foundation
import Capacitor

/// Cookie get/remove for the NativeHttp plugin. Mirrors
/// android/src/main/java/com/cap/nativehttp/utils/CookieManager.java, but is simpler on iOS: since
/// URLSessionUtils configures every session's `httpCookieStorage` to `HTTPCookieStorage.shared`,
/// that same shared store already has the cookies a fetch() call received -- there's no need for a
/// separate forwarding step like Android's ForwardingCookieHandler (which exists there to mirror
/// OkHttp's in-memory cookie jar into android.webkit.CookieManager for WebView visibility).
final class CookieManager {
    /// Resolves `call` with the cookies stored for a domain, as a `{ [cookieName]: value }` object.
    /// Looks up by exact host match (leading-dot on the cookie's domain is stripped first) -- there
    /// is no subdomain/suffix matching, matching Android's exact-host lookup.
    ///
    /// - Parameter call: expects a `domain` String (a full URL or bare host)
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

    /// Removes every stored cookie with the given name, across every domain in
    /// `HTTPCookieStorage.shared`. Always resolves, even if no cookie with that name existed.
    ///
    /// - Parameter call: expects a `cookieName` String
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

    /// Strips a leading `.` from a cookie's `domain` (per RFC 6265, a domain-scoped cookie's domain
    /// is often stored with a leading dot), so it can be compared against a plain host string.
    ///
    /// - Parameter domain: an `HTTPCookie.domain` value
    /// - Returns: the domain without a leading `.`
    private func normalizedDomain(_ domain: String) -> String {
        domain.hasPrefix(".") ? String(domain.dropFirst()) : domain
    }
}
