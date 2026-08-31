import Foundation

/// Trust-all helper used when `disableAllSecurity` is set. Mirrors
/// android/src/main/java/com/cap/nativehttp/utils/SSLSecurityUtils.java: intentionally accepts any
/// server certificate, bypassing all TLS trust checks. Only use this for local/dev endpoints, never
/// in production.
enum SSLSecurityUtils {
    /// Builds a credential that unconditionally trusts the given server trust, with no certificate
    /// validation performed. Used by `NativeHttpSessionDelegate`'s `.trustAll` mode.
    ///
    /// - Parameter serverTrust: the server trust from the authentication challenge
    /// - Returns: a credential accepting `serverTrust` as-is
    static func trustAllCredential(for serverTrust: SecTrust) -> URLCredential {
        URLCredential(trust: serverTrust)
    }
}
