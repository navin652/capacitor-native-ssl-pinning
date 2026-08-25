import Foundation

/// Trust-all helper used when `disableAllSecurity` is set. Mirrors
/// android/src/main/java/com/cap/nativehttp/utils/SSLSecurityUtils.java: intentionally accepts any
/// server certificate, bypassing all TLS trust checks. Only use this for local/dev endpoints, never
/// in production.
enum SSLSecurityUtils {
    static func trustAllCredential(for serverTrust: SecTrust) -> URLCredential {
        URLCredential(trust: serverTrust)
    }
}
