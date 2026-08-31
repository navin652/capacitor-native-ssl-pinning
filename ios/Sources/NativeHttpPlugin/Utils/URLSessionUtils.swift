import Foundation
import Capacitor
import CryptoKit

/// Builds and caches URLSessions per domain, and builds the URLRequest for a fetch() call. Mirrors
/// android/src/main/java/com/cap/nativehttp/utils/OkHttpUtils.java: a pinned/trust-all session is
/// built once per domain and reused for the lifetime of the plugin (`clientsByDomain` there,
/// `sessionsByDomain` here) so pinning/redirect setup only happens once per host. This means
/// changing pinning options for a domain after the first successful call has no effect until either
/// the app restarts or `clearSessionCache()` is called (see `NativeHttpPlugin.clearCertificateCache`).
enum URLSessionUtils {
    /// When `true`, `HttpFetcher` logs the request line/headers and response status via
    /// `CAPLog.print`. Checked fresh on every request (unlike Android's build-time interceptor), so
    /// toggling it takes effect immediately, including for already-cached sessions.
    static var enableDebugLogging = false

    /// Pinned/trust-all `URLSession`s, cached by domain name for the plugin's lifetime. Cleared by
    /// `clearSessionCache()`.
    private static var sessionsByDomain: [String: URLSession] = [:]

    /// Picks a session per the same precedence as HttpFetcher.fetch()/OkHttpUtils on Android:
    /// `disableAllSecurity` wins outright (trust-all, never cached), otherwise `sslPinning.certs` is
    /// required and selects certificate vs public-key pinning based on `pkPinning`. For certificate
    /// pinning, `sslPinning.source` additionally selects where `certs` entries are loaded from (see
    /// `loadCertificates(named:source:)`).
    ///
    /// - Parameters:
    ///   - domain: the request's domain, used as the session cache key
    ///   - options: the full request options (`disableAllSecurity`, `sslPinning`, `pkPinning`,
    ///     `followRedirects`, ...)
    /// - Returns: a session configured for the given domain's security mode; a cached one if this
    ///   domain has been pinned before
    /// - Throws: `NativeHttpError.message` if pinning is required but misconfigured (missing
    ///   `sslPinning.certs`, or no certificate resolves for certificate pinning)
    static func session(forDomain domain: String, options: JSObject) throws -> URLSession {
        let disableAllSecurity = (options["disableAllSecurity"] as? Bool) ?? false
        if disableAllSecurity {
            return buildSession(mode: .trustAll, options: options)
        }

        if let cached = sessionsByDomain[domain] {
            return cached
        }

        guard let sslPinning = options["sslPinning"] as? JSObject,
              let certs = sslPinning["certs"] as? [String] else {
            throw NativeHttpError.message("SSL Pinning key not provided")
        }

        let pkPinning = (options["pkPinning"] as? Bool) ?? false
        let source = (sslPinning["source"] as? String) ?? "asset"
        let mode: TrustMode = pkPinning
            ? .publicKeyPinning(hashes: certs.map { $0.replacingOccurrences(of: "sha256/", with: "") })
            : .certificatePinning(certData: try loadCertificates(named: certs, source: source))

        let session = buildSession(mode: mode, options: options)
        sessionsByDomain[domain] = session
        return session
    }

    /// Mirrors OkHttpUtils.buildRequest: applies headers, resolves the request body (raw string, or
    /// the `{ _parts / formData._parts }` multipart shape), and the per-request timeout. Any other
    /// object-shaped body (not a string, not multipart parts) is intentionally left bodyless, same
    /// as on Android -- callers are expected to JSON.stringify() JSON bodies before calling fetch().
    static func buildRequest(urlString: String, options: JSObject) throws -> URLRequest {
        guard let url = URL(string: urlString) else {
            throw NativeHttpError.message("Invalid URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = (options["method"] as? String)?.uppercased() ?? "GET"

        if let timeoutMs = numberValue(options["timeoutInterval"]) {
            request.timeoutInterval = timeoutMs / 1000
        }

        var contentTypeProvided = false
        if let headers = options["headers"] as? JSObject {
            for (key, value) in headers {
                request.setValue(Utilities.stringValue(value), forHTTPHeaderField: key)
                if key.caseInsensitiveCompare("content-type") == .orderedSame {
                    contentTypeProvided = true
                }
            }
        }

        if let bodyString = options["body"] as? String {
            request.httpBody = bodyString.data(using: .utf8)
            if !contentTypeProvided {
                request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            }
        } else if let bodyMap = options["body"] as? JSObject {
            let parts: JSArray?
            if let formData = bodyMap["formData"] as? JSObject {
                parts = formData["_parts"] as? JSArray
            } else {
                parts = bodyMap["_parts"] as? JSArray
            }

            if let parts = parts {
                let boundary = "Boundary-\(UUID().uuidString)"
                request.httpBody = try Utilities.multipartBody(parts: parts, boundary: boundary)
                request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            }
        }

        return request
    }

    // MARK: - Session construction

    /// The security mode a session/delegate was built for. Matches OkHttpUtils's three client-build
    /// paths on Android (trust-all, public-key `CertificatePinner`, custom cert `X509TrustManager`).
    fileprivate enum TrustMode {
        case trustAll
        case publicKeyPinning(hashes: [String])
        case certificatePinning(certData: [Data])
    }

    /// Builds a fresh `URLSession` for a security mode: shares cookies with `HTTPCookieStorage.shared`
    /// (so `CookieManager` sees them without a separate forwarding step), and attaches a
    /// `NativeHttpSessionDelegate` to handle trust evaluation and `followRedirects`.
    ///
    /// - Parameters:
    ///   - mode: the trust mode to build the session's delegate with
    ///   - options: the request options, consulted for `followRedirects`
    /// - Returns: a new, unconfigured-cache session ready to run requests
    private static func buildSession(mode: TrustMode, options: JSObject) -> URLSession {
        let configuration = URLSessionConfiguration.default
        configuration.httpCookieStorage = HTTPCookieStorage.shared
        configuration.httpShouldSetCookies = true
        configuration.httpCookieAcceptPolicy = .always

        let followRedirects = (options["followRedirects"] as? Bool) ?? false
        let delegate = NativeHttpSessionDelegate(mode: mode, followRedirects: followRedirects)
        return URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }

    /// Loads `.cer`/`.pem` certificate data for certificate (SSL) pinning, from one of two places
    /// selected by `source` (mirrors `OkHttpUtils.initSSLPinning`'s `sslPinning.source` handling on
    /// Android):
    /// - `source == "filesystem"`: each entry in `names` is an absolute filesystem path (a leading
    ///   `file://` is stripped) to a certificate file on device storage -- e.g. the path/URI
    ///   returned by `@capacitor/filesystem`'s `Filesystem.getUri()`. Use this for certificates
    ///   fetched/rotated at runtime.
    /// - otherwise (default `"asset"`): each entry is a bundled iOS app resource path resolved via
    ///   `Bundle.main`. Callers may pass either a bare certificate name (`eftapme_new`, extension
    ///   defaults to `cer`) or a nested, Capacitor-style path (`public/certificates/eftapme_new`).
    ///
    /// - Parameters:
    ///   - names: the `sslPinning.certs` entries (asset paths, or filesystem paths/URIs)
    ///   - source: `"asset"` (default) or `"filesystem"`, from `sslPinning.source`
    /// - Returns: the loaded certificate data, one entry per resolved name
    /// - Throws: `NativeHttpError.message` if none of `names` resolves to readable certificate data
    ///   -- callers should not silently proceed with an empty (trust-nothing) pin list
    private static func loadCertificates(named names: [String], source: String) throws -> [Data] {
        let filesystem = source.caseInsensitiveCompare("filesystem") == .orderedSame

        let certificates = names.compactMap { certRef -> Data? in
            if filesystem {
                var path = certRef
                if path.hasPrefix("file://") {
                    path = String(path.dropFirst("file://".count))
                }
                return try? Data(contentsOf: URL(fileURLWithPath: path))
            }

            let nsPath = certRef as NSString
            let directory = nsPath.deletingLastPathComponent == "." ? nil : nsPath.deletingLastPathComponent
            let resourceName = (nsPath.lastPathComponent as NSString).deletingPathExtension
            let fileExtension = nsPath.pathExtension.isEmpty ? "cer" : nsPath.pathExtension

            let path = Bundle.main.path(forResource: resourceName, ofType: fileExtension, inDirectory: directory)
                ?? Bundle.main.path(forResource: resourceName, ofType: fileExtension)

            guard let path else { return nil }
            return try? Data(contentsOf: URL(fileURLWithPath: path))
        }

        guard !certificates.isEmpty else {
            throw NativeHttpError.message(
                filesystem ? "No SSL certificates found at the given filesystem paths" : "No bundled SSL certificates found"
            )
        }
        return certificates
    }

    /// Clears the per-domain cached URLSessions. Pinning config is cached once per domain and reused
    /// for the plugin lifetime, so certificates replaced on disk (rotation via
    /// `sslPinning.source: "filesystem"`) would otherwise only take effect after an app restart. Call
    /// this after writing new certificate files so the next fetch() rebuilds pinning.
    static func clearSessionCache() {
        sessionsByDomain.removeAll()
    }

    /// Coerces a JS numeric value (bridged as either `Int` or `Double`) to a `Double`, for options
    /// like `timeoutInterval` where the JS-side type is just `number`.
    ///
    /// - Parameter value: a value from the options object, expected to be numeric
    /// - Returns: the value as a `Double`, or `nil` if it isn't numeric
    private static func numberValue(_ value: JSValue?) -> Double? {
        if let doubleValue = value as? Double { return doubleValue }
        if let intValue = value as? Int { return Double(intValue) }
        return nil
    }
}

/// URLSessionDelegate handling SSL/public-key pinning trust evaluation and the followRedirects
/// option for a single cached per-domain session. Combines what OkHttpUtils.initSSLPinning /
/// initPublicKeyPinning and SSLSecurityUtils.java do on Android into one delegate, since URLSession
/// (unlike OkHttp) always needs a delegate object to intercept trust challenges and redirects.
private final class NativeHttpSessionDelegate: NSObject, URLSessionDelegate, URLSessionTaskDelegate {
    private let mode: URLSessionUtils.TrustMode
    private let followRedirects: Bool

    /// - Parameters:
    ///   - mode: the trust mode this session's requests should be evaluated against
    ///   - followRedirects: whether HTTP redirects should be followed; `false` cancels them
    init(mode: URLSessionUtils.TrustMode, followRedirects: Bool) {
        self.mode = mode
        self.followRedirects = followRedirects
    }

    /// `URLSessionDelegate` callback: decides whether to trust the server's certificate chain,
    /// dispatching to the right check for this delegate's `mode` (trust-all, public-key pinning via
    /// `publicKeyMatches`, or certificate pinning via `certificateChainTrusted`). Ignores any
    /// challenge that isn't server-trust evaluation (e.g. client certificate requests), deferring to
    /// the system's default handling.
    ///
    /// - Parameters:
    ///   - session: the session the challenge came from
    ///   - challenge: the authentication challenge to evaluate
    ///   - completionHandler: called with `.useCredential`/a trust credential to accept the
    ///     connection, or `.cancelAuthenticationChallenge` to reject it
    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let serverTrust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        switch mode {
        case .trustAll:
            completionHandler(.useCredential, SSLSecurityUtils.trustAllCredential(for: serverTrust))
        case .publicKeyPinning(let hashes):
            if Self.publicKeyMatches(serverTrust: serverTrust, pinnedHashes: hashes) {
                completionHandler(.useCredential, URLCredential(trust: serverTrust))
            } else {
                completionHandler(.cancelAuthenticationChallenge, nil)
            }
        case .certificatePinning(let certData):
            if Self.certificateChainTrusted(serverTrust: serverTrust, pinnedCertificates: certData) {
                completionHandler(.useCredential, URLCredential(trust: serverTrust))
            } else {
                completionHandler(.cancelAuthenticationChallenge, nil)
            }
        }
    }

    /// `URLSessionTaskDelegate` callback: applies `followRedirects` (default `false`, same as
    /// Android) by either following the proposed redirect request or cancelling it, which completes
    /// the task with the redirect response as-is (so the caller sees the `3xx` status/`Location`
    /// header rather than the redirected content).
    ///
    /// - Parameters:
    ///   - response: the redirect response received
    ///   - request: the request the system proposes following the redirect with
    ///   - completionHandler: called with `request` to follow the redirect, or `nil` to not
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(followRedirects ? request : nil)
    }

    /// Checks every certificate in the presented chain against the pinned SHA-256 SPKI hashes, same
    /// as OkHttp's CertificatePinner (the previous scaffold only checked the leaf certificate at
    /// index 0, which is not equivalent).
    ///
    /// `SecKeyCopyExternalRepresentation` returns the *raw* key (PKCS#1 `RSAPublicKey` for RSA,
    /// raw point bytes for EC) -- it does NOT include the X.509 `SubjectPublicKeyInfo` (SPKI)
    /// `AlgorithmIdentifier` header. Every other pin in this ecosystem (OkHttp's CertificatePinner
    /// on Android, openssl, standard "sha256/..." pin generators) hashes the full SPKI DER, so
    /// hashing the raw representation directly produces a different value and pinning always fails
    /// against real-world pins. We reconstruct the SPKI DER by prepending the fixed
    /// algorithm-identifier header for the key's type/size (same technique TrustKit/OWASP use)
    /// before hashing.
    private static func publicKeyMatches(serverTrust: SecTrust, pinnedHashes: [String]) -> Bool {
        for certificate in certificates(in: serverTrust) {
            guard let publicKey = SecCertificateCopyKey(certificate),
                  let publicKeyData = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?,
                  let attributes = SecKeyCopyAttributes(publicKey) as? [CFString: Any],
                  let header = spkiHeader(for: attributes) else {
                continue
            }
            let spkiData = Data(header) + publicKeyData
            let hash = Data(SHA256.hash(data: spkiData)).base64EncodedString()
            if pinnedHashes.contains(hash) {
                return true
            }
        }
        return false
    }

    /// Fixed X.509 SPKI `AlgorithmIdentifier` + length-prefix headers, keyed by key type/size, as
    /// published by TrustKit/OWASP. Prepending the right one to the raw key bytes from
    /// `SecKeyCopyExternalRepresentation` reproduces the same DER that `openssl x509 -pubkey | openssl
    /// pkey -pubin -outform der` would emit, which is what the "sha256/..." pins are computed from.
    private static func spkiHeader(for attributes: [CFString: Any]) -> [UInt8]? {
        guard let keyType = attributes[kSecAttrKeyType] as? String,
              let keySizeInBits = attributes[kSecAttrKeySizeInBits] as? Int else {
            return nil
        }

        let rsaHeaderSpki: [UInt8] = [
            0x30, 0x82, 0x01, 0x22, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
            0x01, 0x05, 0x00, 0x03, 0x82, 0x01, 0x0f, 0x00
        ]
        let rsa4096HeaderSpki: [UInt8] = [
            0x30, 0x82, 0x02, 0x22, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
            0x01, 0x05, 0x00, 0x03, 0x82, 0x02, 0x0f, 0x00
        ]
        let ecDsaSecp256r1HeaderSpki: [UInt8] = [
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
            0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
        ]
        let ecDsaSecp384r1HeaderSpki: [UInt8] = [
            0x30, 0x76, 0x30, 0x10, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x05, 0x2b,
            0x81, 0x04, 0x00, 0x22, 0x03, 0x62, 0x00
        ]

        switch (keyType as CFString, keySizeInBits) {
        case (kSecAttrKeyTypeRSA, 2048):
            return rsaHeaderSpki
        case (kSecAttrKeyTypeRSA, 4096):
            return rsa4096HeaderSpki
        case (kSecAttrKeyTypeECSECPrimeRandom, 256):
            return ecDsaSecp256r1HeaderSpki
        case (kSecAttrKeyTypeECSECPrimeRandom, 384):
            return ecDsaSecp384r1HeaderSpki
        default:
            return nil
        }
    }

    /// Equivalent to Android's custom X509TrustManager built from a KeyStore containing only the
    /// pinned certs (OkHttpUtils.initSSLPinning): only chains that build up to one of the pinned
    /// certificates are trusted. An empty pin list trusts nothing, matching the empty-KeyStore case
    /// on Android.
    private static func certificateChainTrusted(serverTrust: SecTrust, pinnedCertificates: [Data]) -> Bool {
        guard !pinnedCertificates.isEmpty else { return false }
        let anchors = pinnedCertificates.compactMap { SecCertificateCreateWithData(nil, $0 as CFData) }
        guard !anchors.isEmpty else { return false }

        SecTrustSetAnchorCertificates(serverTrust, anchors as CFArray)
        SecTrustSetAnchorCertificatesOnly(serverTrust, true)

        var error: CFError?
        return SecTrustEvaluateWithError(serverTrust, &error)
    }

    /// Returns every certificate in a trust's chain, using the non-deprecated `SecTrustCopyCertificateChain`
    /// where available (iOS 15+) and falling back to `SecTrustGetCertificateAtIndex` on iOS 14.
    ///
    /// - Parameter trust: the server trust to read the chain from
    /// - Returns: the chain's certificates, leaf first
    private static func certificates(in trust: SecTrust) -> [SecCertificate] {
        if #available(iOS 15.0, *) {
            return (SecTrustCopyCertificateChain(trust) as? [SecCertificate]) ?? []
        } else {
            let count = SecTrustGetCertificateCount(trust)
            return (0..<count).compactMap { SecTrustGetCertificateAtIndex(trust, $0) }
        }
    }
}
