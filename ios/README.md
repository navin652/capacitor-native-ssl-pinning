# iOS Implementation

This is the iOS side of [`capacitor-native-ssl-pinning`](../README.md), built on Apple's native `URLSession` + `CryptoKit` (no third-party HTTP library). It mirrors the Android implementation's structure and behavior -- see [android/README.md](../android/README.md) for the reference platform this was ported from.

## Source layout

```
ios/Sources/NativeHttpPlugin/
├── NativeHttpPlugin.swift        # @objc(NativeHttpPlugin)/CAPBridgedPlugin entry point, exposes fetch/getCookies/removeCookieByName/toggleLogging
└── Utils/
    ├── URLSessionUtils.swift     # Builds & caches URLSessions per domain, cert/public-key pinning, request building, multipart bodies
    ├── HttpFetcher.swift         # Orchestrates a single fetch() call: picks a session, runs the request, shapes the response
    ├── CookieManager.swift       # get/removeCookieByName backed directly by HTTPCookieStorage.shared
    ├── SSLSecurityUtils.swift    # Trust-all credential used when disableAllSecurity is set
    └── Utilities.swift           # Domain parsing, header/response helpers, Directory-key → URL resolution, multipart body building
```

`NativeHttpPlugin` only wires plugin calls to these helpers; `URLSessionUtils` additionally caches one `URLSession` (and its pinning delegate) per domain, the same way Android's `OkHttpUtils.clientsByDomain` does -- so pinning/redirect setup for a given host only happens once per app run. This also means, same as on Android, that changing pinning options for a domain after the first successful call has no effect until the app restarts.

## How pinning is selected

`URLSessionUtils.session(forDomain:options:)` picks the session using the same precedence as Android's `HttpFetcher`/`OkHttpUtils`:

1. `disableAllSecurity: true` → a trust-all session (`SSLSecurityUtils.trustAllCredential`) that accepts any server certificate. No pinning at all; use only for local/dev endpoints.
2. otherwise, `sslPinning.certs` is required, and the mode branches on `pkPinning`:
   - `pkPinning: true` -- public-key pinning: every certificate in the presented chain is checked against the `sha256/...` hashes in `certs`, matching OkHttp's `CertificatePinner` semantics (any cert in the chain may match, not just the leaf). The hash is computed over the certificate's full X.509 `SubjectPublicKeyInfo` (SPKI) DER -- the same thing `openssl x509 -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256` produces (see the root README's "Ways to Extract Public key" section) and what OkHttp's `CertificatePinner` hashes on Android. `SecKeyCopyExternalRepresentation` only returns the *raw* key (PKCS#1 for RSA, raw point bytes for EC), without that SPKI header, so `URLSessionUtils` reconstructs the SPKI DER by prepending the fixed algorithm-identifier header for the key's type/size (RSA 2048/4096, EC P-256/P-384 -- the same technique TrustKit/OWASP use) before hashing. **A pinned certificate using an unsupported key type/size will fail closed** (that certificate is skipped when checking the chain, same as a non-matching hash).
   - `pkPinning: false` (default) -- certificate pinning: the `.cer` files named in `certs` are set as the *only* trust anchors (`SecTrustSetAnchorCertificates` + `SecTrustSetAnchorCertificatesOnly`), so only chains that build up to one of those exact certificates validate.
3. Neither present → the call is rejected with `"SSL Pinning key not provided"`.

An empty `certs` array trusts nothing under either pinning mode (matching Android's empty-KeyStore behavior) -- so `sslPinning: { certs: [] }` only makes sense together with `disableAllSecurity: true`, same as in the root README's usage examples.

### Where to put `.cer` files

Certificate-pinning mode resolves each string in `sslPinning.certs` as a bundle resource path (`Bundle.main.path(forResource:ofType:inDirectory:)`), falling back to a flat top-level lookup if that fails. You can pass either a bare certificate name (`eftapme_new`, extension defaults to `cer`) or a nested, Capacitor-style path (`public/certificates/eftapme_new`) -- matching the paths used in the root README's examples and mirroring how Android resolves the same string under `assets/` (see [android/README.md](../android/README.md#where-to-put-cer-files)). Either way, the `.cer` file must be added as a bundled resource in the iOS app's Xcode project (added to the app target, not just present on the file system). If none of the given certs resolve to a bundled file, the call is rejected with `"No bundled SSL certificates found"` rather than silently pinning against an empty (trust-nothing) list.

## Other behavior worth knowing

- **Cookies** are stored directly in `HTTPCookieStorage.shared`, which every session is configured to use (`httpCookieStorage`/`httpShouldSetCookies`/`httpCookieAcceptPolicy = .always`). `getCookies`/`removeCookieByName` read/delete straight from that store -- there's no separate in-memory jar to keep in sync, unlike Android.
- **Response shaping** (`HttpFetcher.handleResponse`) depends on `responseType`: `file`/`blob` write to disk under the directory resolved by `Utilities.resolveDirectoryURL` (mapped from the `Directory` enum per its documented iOS behavior -- see root README), `base64` returns an encoded string, and the default `text` path returns `bodyString`.
- **Multipart/file uploads** are built in `Utilities.multipartBody` from a `{ _parts: [...] }` body shape; file parts are read directly from `uri`/`path` (a `file://` URL or plain path) or decoded from base64 `data` -- no temp-file copy step is needed here the way Android's `TempFileManager` requires for `content://` URIs, since iOS file URIs/paths can be read directly.
- **Request logging** is off by default; `toggleLogging({ enableLogging: true })` flips `URLSessionUtils.enableDebugLogging`, which logs the request line/headers and response status via `CAPLog.print`. Unlike Android's interceptor (baked into the client at build time), this flag is checked fresh on every request, so toggling it takes effect immediately, including for already-cached per-domain sessions.
- **Timeouts** (`timeoutInterval`, milliseconds) are applied per-request via `URLRequest.timeoutInterval`. **Redirects** (`followRedirects`, default `false`) are applied per-session via the delegate's `willPerformHTTPRedirection` hook, so (like the pinning setup) they're fixed for a domain once its session is first built.

## Requirements

- iOS 14.0+ deployment target (see [Package.swift](../Package.swift) / [CapacitorNativeSslPinning.podspec](../CapacitorNativeSslPinning.podspec)).
- Swift 5.1+. No third-party dependencies -- only Foundation, Capacitor, and CryptoKit (all system-provided).

## Building & testing this module standalone

```shell
npm run verify:ios
```

This runs `xcodebuild -scheme CapacitorNativeSslPinning -destination generic/platform=iOS` from the project root, and requires Xcode/macOS.
