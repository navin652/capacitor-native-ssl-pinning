import Foundation
import Capacitor
import CryptoKit

/// Builds and caches URLSessions per domain, and builds the URLRequest for a fetch() call. Mirrors
/// android/src/main/java/com/cap/nativehttp/utils/OkHttpUtils.java: a pinned/trust-all session is
/// built once per domain and reused for the lifetime of the plugin (`clientsByDomain` there,
/// `sessionsByDomain` here) so pinning/redirect setup only happens once per host. Note this means,
/// same as on Android, that changing pinning options for a domain after the first successful call
/// has no effect until the app restarts.
enum URLSessionUtils {
    static var enableDebugLogging = false

    private static var sessionsByDomain: [String: URLSession] = [:]

    /// Picks a session per the same precedence as HttpFetcher.fetch()/OkHttpUtils on Android:
    /// disableAllSecurity wins outright (trust-all, never cached), otherwise sslPinning.certs is
    /// required and selects certificate vs public-key pinning based on pkPinning.
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
        let mode: TrustMode = pkPinning
            ? .publicKeyPinning(hashes: certs.map { $0.replacingOccurrences(of: "sha256/", with: "") })
            : .certificatePinning(certData: loadBundledCertificates(named: certs))

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

    fileprivate enum TrustMode {
        case trustAll
        case publicKeyPinning(hashes: [String])
        case certificatePinning(certData: [Data])
    }

    private static func buildSession(mode: TrustMode, options: JSObject) -> URLSession {
        let configuration = URLSessionConfiguration.default
        configuration.httpCookieStorage = HTTPCookieStorage.shared
        configuration.httpShouldSetCookies = true
        configuration.httpCookieAcceptPolicy = .always

        let followRedirects = (options["followRedirects"] as? Bool) ?? false
        let delegate = NativeHttpSessionDelegate(mode: mode, followRedirects: followRedirects)
        return URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }

    /// Certificate-pinning mode loads `.cer` files bundled as iOS app resources (Bundle.main),
    /// matching the pattern android/.../OkHttpUtils.java uses for its `assets/<name>.cer` lookup:
    /// the consuming app supplies the certificate as a bundled resource, keyed by filename only.
    private static func loadBundledCertificates(named names: [String]) -> [Data] {
        names.compactMap { certPath in
            let certName = (certPath as NSString).lastPathComponent
            guard let path = Bundle.main.path(forResource: certName, ofType: "cer"),
                  let data = try? Data(contentsOf: URL(fileURLWithPath: path)) else {
                return nil
            }
            return data
        }
    }

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

    init(mode: URLSessionUtils.TrustMode, followRedirects: Bool) {
        self.mode = mode
        self.followRedirects = followRedirects
    }

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
    private static func publicKeyMatches(serverTrust: SecTrust, pinnedHashes: [String]) -> Bool {
        for certificate in certificates(in: serverTrust) {
            guard let publicKey = SecCertificateCopyKey(certificate),
                  let publicKeyData = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
                continue
            }
            let hash = Data(SHA256.hash(data: publicKeyData)).base64EncodedString()
            if pinnedHashes.contains(hash) {
                return true
            }
        }
        return false
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

    private static func certificates(in trust: SecTrust) -> [SecCertificate] {
        if #available(iOS 15.0, *) {
            return (SecTrustCopyCertificateChain(trust) as? [SecCertificate]) ?? []
        } else {
            let count = SecTrustGetCertificateCount(trust)
            return (0..<count).compactMap { SecTrustGetCertificateAtIndex(trust, $0) }
        }
    }
}
