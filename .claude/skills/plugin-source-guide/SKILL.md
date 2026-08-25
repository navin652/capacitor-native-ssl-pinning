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
`getCookies`, `removeCookieByName`, `toggleLogging`; certificate & public-key pinning; `text`/
`base64`/`blob`/`file` response types; multipart/file uploads; request logging):

- **Android** — OkHttp3-based. Details: [android/README.md](../../../android/README.md).
- **iOS** — native `URLSession` + `CryptoKit`-based (no third-party HTTP library; an earlier
  AFNetworking-based scaffold was replaced since AFNetworking was never actually declared as a
  dependency in `Package.swift`/the podspec). Details: [ios/README.md](../../../ios/README.md).
- **Web — fallback only.** `src/web.ts` implements the same interface using the browser `fetch`/
  `document.cookie` APIs. SSL pinning options are accepted but meaningless there (browsers own TLS
  trust) — this is expected, not a gap to fix.

`ios/Sources/NativeHttpPlugin/NativeHttp.swift` and its XCTest in `ios/Tests/` are leftover
plugin-template scaffold (an `echo` example) that nothing else references — harmless, and left
alone deliberately, the same way Android's own template scaffold tests
(`android/src/test/.../ExampleUnitTest.java`, `android/src/androidTest/.../ExampleInstrumentedTest.java`)
were never replaced with real tests either. Don't treat either as documentation of real behavior.

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
to a small `Utils`/`utils` package: request/session building + pinning (`OkHttpUtils.java` /
`URLSessionUtils.swift`), fetch orchestration + response shaping (`HttpFetcher.java` /
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
