# Android Implementation

This is the Android side of [`capacitor-native-ssl-pinning`](../README.md), built on [OkHttp 3](https://square.github.io/okhttp/). It was the original reference platform for this plugin; iOS ([ios/README.md](../ios/README.md)) now mirrors it feature-for-feature (see the root [README](../README.md#platform-support)).

## Source layout

```
android/src/main/java/com/cap/nativehttp/
├── NativeHttpPlugin.java        # @CapacitorPlugin entry point, exposes fetch/getCookies/removeCookieByName/toggleLogging
└── utils/
    ├── OkHttpUtils.java         # Builds & caches OkHttpClients per domain, cert/public-key pinning, request building, multipart bodies
    ├── HttpFetcher.java         # Orchestrates a single fetch() call: picks a client, runs the request, shapes the response
    ├── CookieManager.java       # In-memory OkHttp CookieJar, backed by ForwardingCookieHandler
    ├── ForwardingCookieHandler.java  # Bridges OkHttp cookies to Android's WebView android.webkit.CookieManager
    ├── SSLSecurityUtils.java    # Trust-all TrustManager/SSLSocketFactory used when disableAllSecurity is set
    ├── TempFileManager.java     # Tracks & deletes temp files created for native file uploads, cleaned up in handleOnDestroy()
    └── Utilities.java           # Domain parsing, header/response helpers, Directory-key → File resolution
```

`NativeHttpPlugin.load()` wires up a single `CookieManager`/`HttpFetcher` pair per plugin instance and reuses them across calls; `OkHttpUtils` additionally caches one `OkHttpClient` per domain (`clientsByDomain`) so pinning setup isn't repeated on every request to the same host.

## How pinning is selected

`HttpFetcher.fetch()` picks the client based on the request options, in this order:

1. `disableAllSecurity: true` → `OkHttpUtils.buildDefaultOkHttpClient` — a trust-all `SSLSocketFactory`/hostname verifier from `SSLSecurityUtils`. No pinning at all; use only for local/dev endpoints.
2. otherwise, `sslPinning.certs` is required → `OkHttpUtils.buildOkHttpClient`, which branches on `pkPinning`:
   - `pkPinning: true` — public-key pinning via OkHttp's `CertificatePinner`, using the `sha256/...` hashes in `certs` for the request's domain.
   - `pkPinning: false` (default) — certificate pinning via a custom `X509TrustManager` built from `.cer` files loaded as classloader resources at `assets/<certName>.cer`.
3. Neither present → the call is rejected with `"SSL Pinning key not provided"`.

### Where to put `.cer` files

Certificate-pinning mode reads certificates via `OkHttpUtils.class.getClassLoader().getResourceAsStream("assets/" + filename + ".cer")`, where `filename` is the **exact** string passed in `sslPinning.certs` -- it is not trimmed to a bare name. In a Capacitor app this means the `.cer` file must be bundled as a raw Android asset under `android/app/src/main/assets/`, at a path matching whatever string you pass: `certs: ['public/certificates/httpbin']` expects a file at `android/app/src/main/assets/public/certificates/httpbin.cer`. (This is also why the root README's upload/pinning examples use `public/certificates/...`-style paths, mirroring the iOS side -- see [ios/README.md](../ios/README.md#where-to-put-cer-files).)

## Other behavior worth knowing

- **Cookies** are stored in-memory per host in `CookieManager` and mirrored into Android's `android.webkit.CookieManager` via `ForwardingCookieHandler`, so they're also visible to any in-app `WebView`. `getCookies`/`removeCookieByName` only look at the in-memory store.
- **Response shaping** (`HttpFetcher.handleResponse`) depends on `responseType`: `file`/`blob` write to disk under the directory resolved by `Utilities.resolveDirectory` (mapped from the `Directory` enum — see root README), `base64` returns an encoded string, and the default `text` path returns `bodyString`.
- **Multipart/file uploads** are built in `OkHttpUtils.buildFormDataRequestBody` from a `{ _parts: [...] }` body shape; parts with `uri`/`path` are copied to a temp file first (`OkHttpUtils.getTempFile`), registered with `TempFileManager`, and deleted once the request finishes (success or failure).
- **Request logging** is off by default; `toggleLogging({ enableLogging: true })` flips `OkHttpUtils.enableDebugLogging`, which adds an OkHttp `HttpLoggingInterceptor` (`BODY` level) to newly-built clients. Toggling it does not affect already-cached per-domain clients.
- **Timeouts** (`timeoutInterval`, milliseconds) and **redirects** (`followRedirects`) are applied per-request/per-client via `applyTimeoutsIfPresent`/`applyCommonClientConfig`.

## Requirements

- `compileSdk`/`targetSdk` 35, `minSdk` 23 (see [build.gradle](build.gradle); overridable via the consuming app's `ext` properties).
- Java 21 source/target compatibility.
- OkHttp 3.x (`com.squareup.okhttp3:okhttp:4.9.0` and the logging interceptor) — bundled as a plugin dependency, no extra setup needed in the consuming app.

## Building & testing this module standalone

```shell
cd android
./gradlew clean build test
```

This is also what `npm run verify:android` runs from the project root.
