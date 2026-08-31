---
name: plugin-source-guide
description: Explains how capacitor-native-ssl-pinning's source is organized and how its docs/build are generated. Use before adding/changing a plugin method or option, editing README API docs, or touching native (Android/iOS) code in this repo.
---

# capacitor-native-ssl-pinning: source & build guide

This plugin adds native SSL/public-key pinning to Capacitor HTTP requests. It's a Capacitor plugin
generated from the official plugin template, with the API and pinning approach adapted from
[react-native-ssl-pinning](https://github.com/MaxToyberman/react-native-ssl-pinning) (Max Toyberman) —
`android/.../OkHttpUtils.java` still carries his original attribution comment.

## Implementation status

Both native platforms are fully implemented and mirror each other feature-for-feature (`fetch`,
`getCookies`, `removeCookieByName`, `toggleLogging`, `clearCertificateCache`; certificate &
public-key pinning; `text`/`base64`/`blob`/`file` response types; multipart/file uploads; request
logging):

- **Android** — OkHttp3-based. Details: [android/README.md](../../../android/README.md).
- **iOS** — native `URLSession` + `CryptoKit`-based (no third-party HTTP library; an earlier
  AFNetworking-based scaffold was replaced since AFNetworking was never actually declared as a
  dependency in `Package.swift`/the podspec). Details: [ios/README.md](../../../ios/README.md).
- **Web — fallback only.** `src/web.ts` implements the same interface using the browser `fetch`/
  `document.cookie` APIs. SSL pinning options are accepted but meaningless there (browsers own TLS
  trust) — this is expected, not a gap to fix. `clearCertificateCache()` is a no-op there too (no
  per-domain client/session cache to invalidate).

### Runtime certificate rotation (`sslPinning.source` + `clearCertificateCache`)

Certificates for certificate-pinning mode (`pkPinning: false`) can come from two places, selected by
`sslPinning.source` (see the JSDoc on `NativeSSLPinning.Options.sslPinning` in
`src/definitions.ts`):

- `'asset'` (default) — bundled resource paths, resolved under Android's `assets/` or iOS's
  `Bundle.main`, as described in each platform README's "Where to put `.cer` files" section.
- `'filesystem'` — absolute paths, or `file://`/`content://` URIs, to certificate files on device
  storage (e.g. obtained via `@capacitor/filesystem`'s `Filesystem.getUri()`). This is for
  certificates fetched/rotated at runtime rather than shipped in the app bundle.

Both platforms still cache one pinned client/session per domain and reuse it for the process
lifetime (see the caching note below), so simply overwriting a certificate file on disk does
**not** change what an already-pinned domain trusts. Call `NativeHttp.clearCertificateCache()`
after writing new certificate files so the next `fetch()` per domain rebuilds pinning against the
fresh certificates — Android's `OkHttpUtils.clearClientCache()` and iOS's
`URLSessionUtils.clearSessionCache()` both just clear their respective per-domain cache maps.

The iOS side was verified on macOS after the initial port, which surfaced (and fixed) two real bugs
worth knowing if you touch pinning code again:

- **Public-key pinning hash.** `SecKeyCopyExternalRepresentation` returns the *raw* key (PKCS#1 for
  RSA, raw point bytes for EC), not the X.509 SPKI DER that `sha256/...` pins are actually computed
  from (openssl, OkHttp's `CertificatePinner`, TrustKit, etc. all hash the full SPKI). Hashing the
  raw bytes directly meant pinning silently never matched real-world pins. Fixed by reconstructing
  the SPKI DER (prepending the fixed algorithm-identifier header for the key's type/size) before
  hashing — see `URLSessionUtils.swift`'s `spkiHeader(for:)`. Only RSA 2048/4096 and EC P-256/P-384
  have a header defined; other key types/sizes fail closed (skipped when matching the chain).
- **Certificate-pinning cert paths.** iOS's certificate loading (now `loadCertificates(named:source:)`
  in `URLSessionUtils.swift`) resolves nested, Capacitor-style paths (`public/certificates/eftapme_new`),
  not just a bare filename, matching how Android resolves the same `sslPinning.certs` string under
  `assets/` — see [ios/README.md](../../../ios/README.md#where-to-put-cer-files) /
  [android/README.md](../../../android/README.md#where-to-put-cer-files). It now also throws a clear
  error if no cert resolves, instead of silently pinning against an empty (trust-nothing) list.

**Not yet independently re-verified on macOS**: `clearCertificateCache` and `sslPinning.source:
'filesystem'` support were added to both platforms after the macOS verification pass above. The
Android side has been compiled (`./gradlew compileDebugJavaWithJavac`); the iOS side
(`URLSessionUtils.swift`'s `loadCertificates(named:source:)` / `clearSessionCache()`,
`NativeHttpPlugin.swift`'s `clearCertificateCache`) has not been confirmed to build on Xcode/macOS
yet — treat it as implemented-but-unverified until it has.

`ios/Sources/NativeHttpPlugin/NativeHttp.swift`, the leftover plugin-template `echo` scaffold class,
has been deleted — the plugin entry point talks to the `Utils/` helpers directly, there's no separate
"implementation" class the way the template originally set up. `ios/Tests/` was updated to match
(no longer references the deleted class). Android's own template scaffold tests
(`android/src/test/.../ExampleUnitTest.java`, `android/src/androidTest/.../ExampleInstrumentedTest.java`)
are still untouched boilerplate, unrelated to this plugin's behavior — don't treat those as
documentation of real behavior.

## Repo map

```
src/
├── definitions.ts   # Plugin contract: NativeSSLPinning namespace (Options/Response/Cookies/Header)
│                     # + the NativeHttpPlugin interface. This is the single source of truth for the
│                     # public API shape and its JSDoc.
├── types.ts          # Shared types not specific to one call, e.g. the `Directory` enum used by
│                     # fileSaveDirectory (heavily JSDoc-commented — that JSDoc also feeds docgen).
├── index.ts          # registerPlugin() + the NativeHttp object apps actually import. Wraps the
│                     # native call to transparently convert a web FormData body into the
│                     # `{ _parts: [...] }` JSON shape the native (Android/iOS) side expects.
└── web.ts            # NativeHttpWeb: browser-only implementation used when running as a PWA / in
                        # a browser instead of on-device.

android/src/main/java/com/cap/nativehttp/   # OkHttp3 implementation — see android/README.md
ios/Sources/NativeHttpPlugin/               # URLSession implementation — see ios/README.md
dist/                                        # build output (esm + rollup bundle + docs.json) — generated, don't hand-edit
```

Both native sides use the same internal breakdown — a thin plugin-entry class that only wires calls
to a small `Utils`/`utils` package: request/session building + pinning + the per-domain cache (build,
reuse, and invalidate via `clearClientCache`/`clearSessionCache`) in `OkHttpUtils.java` /
`URLSessionUtils.swift`, fetch orchestration + response shaping (`HttpFetcher.java` /
`HttpFetcher.swift`), cookies (`CookieManager.java` / `CookieManager.swift`), a trust-all helper for
`disableAllSecurity` (`SSLSecurityUtils.java` / `SSLSecurityUtils.swift`), and misc helpers
(`Utilities.java` / `Utilities.swift`). Android additionally has a `TempFileManager` to copy
`content://` URIs to temp files for upload — iOS doesn't need this since it can read `file://`
URIs/paths directly.

## The build & docgen pipeline

`npm run build` = `clean` → `docgen` → `tsc` → `rollup -c rollup.config.mjs`.

The important part: **`npm run docgen` (via `@capacitor/docgen`) regenerates the `<docgen-index>` and
`<docgen-api>` blocks in the root `README.md`, plus `dist/docs.json`, directly from the JSDoc
comments and type signatures in `src/definitions.ts`** (and the types it references, like
`Directory` in `types.ts`). Consequences:

- Never hand-edit the content between `<docgen-index>` / `</docgen-index>` or
  `<docgen-api>` / `</docgen-api>` in `README.md` — it will be silently overwritten next time
  someone runs `npm run docgen` or `npm run build`. To change what those blocks say, edit the JSDoc
  / type signatures in `src/definitions.ts` (or `src/types.ts`) and rerun docgen.
- Everything else in `README.md` (Platform Support, Usage Examples, install instructions, etc.) is
  hand-maintained prose and is safe — and expected — to edit directly.
- `npm run verify` builds/tests all three platforms (`verify:ios`, `verify:android`, `verify:web`);
  `verify:ios` requires Xcode/macOS and can't be run from a non-macOS environment — if you're
  implementing/changing iOS code elsewhere, say so explicitly rather than claiming it builds.

## Checklist: adding or changing a plugin method/option

Because the same contract is duplicated across three independent implementations, changes need to
be carried through all of them or the platforms will silently diverge:

1. **`src/definitions.ts`** — update the `NativeSSLPinning` namespace (`Options`/`Response`/etc.) or
   the `NativeHttpPlugin` interface, with JSDoc (it flows into the generated README API docs).
2. **`src/types.ts`** — add here instead if it's a standalone shared type/enum (like `Directory`).
3. **`src/web.ts`** — implement (or explicitly no-op/throw, like `toggleLogging` does today) the
   browser-side behavior.
4. **Android** — implement in `android/src/main/java/com/cap/nativehttp/NativeHttpPlugin.java` and
   whichever `utils/` class owns that concern (request building in `OkHttpUtils`, response shaping
   in `HttpFetcher`, cookies in `CookieManager`, etc.); register new methods with `@PluginMethod`.
5. **iOS** — implement in `ios/Sources/NativeHttpPlugin/NativeHttpPlugin.swift` and the matching
   `Utils/` file (`URLSessionUtils`, `HttpFetcher`, `CookieManager`, etc.), matching Android's option
   semantics; add the method to the `pluginMethods` array (methods missing from that array aren't
   callable from JS even if the Swift function exists).
6. **Regenerate docs** — run `npm run docgen` (or `npm run build`) so the README API tables and
   `dist/docs.json` reflect the change.
7. **`android/README.md`** / **`ios/README.md`** — update whichever platform's behavior you changed
   (pinning selection, file/cookie handling, etc.).

## Quick script reference

| Script | What it does |
| --- | --- |
| `npm run build` | Full web build: clean → docgen → tsc → rollup |
| `npm run docgen` | Regenerate README API docs + `dist/docs.json` from `src/definitions.ts` JSDoc |
| `npm run verify` | Build/test all platforms (ios/android/web) |
| `npm run lint` / `npm run fmt` | ESLint + Prettier + SwiftLint check / autofix |
| `cd android && ./gradlew clean build test` | Android-only build/test (same as `verify:android`) |
| `npm run verify:ios` | iOS-only build (`xcodebuild ...`) — requires Xcode/macOS |

See [CONTRIBUTING.md](../../../CONTRIBUTING.md) for local setup details.
