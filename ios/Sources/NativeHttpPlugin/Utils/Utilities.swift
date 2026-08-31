import Foundation
import Capacitor

/// Errors surfaced as plugin call rejections. Mirrors the plain reject() messages used by
/// android/src/main/java/com/cap/nativehttp/utils/HttpFetcher.java and OkHttpUtils.java.
enum NativeHttpError: Error {
    case message(String)
}

/// Shared helpers used by HttpFetcher/URLSessionUtils/CookieManager. Mirrors
/// android/src/main/java/com/cap/nativehttp/utils/Utilities.java.
enum Utilities {
    /// Matches Android's `Utilities.getDomainName`: the host, with a leading "www." stripped. Used
    /// as the cache key for both `URLSessionUtils`'s per-domain session cache and `CookieManager`'s
    /// cookie lookups.
    ///
    /// - Parameter urlString: a full URL (e.g. `https://www.example.com/path`)
    /// - Returns: the host with any leading `"www."` removed, or `urlString` itself if it has no
    ///   parseable host
    static func domainName(from urlString: String) -> String {
        guard let host = URL(string: urlString)?.host else { return urlString }
        return host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    }

    /// Flattens an `HTTPURLResponse`'s headers into a plain `{ [name]: value }` dictionary for the
    /// plugin result.
    ///
    /// - Parameter response: the response to read headers from
    /// - Returns: the response headers as a JS-friendly dictionary
    static func responseHeaders(_ response: HTTPURLResponse) -> [String: String] {
        var headers: [String: String] = [:]
        for (key, value) in response.allHeaderFields {
            if let key = key as? String {
                headers[key] = "\(value)"
            }
        }
        return headers
    }

    /// Resolves a `Directory` enum key (see src/types.ts) to a filesystem location, following that
    /// enum's documented iOS behavior: most keys (DOCUMENTS, DATA, EXTERNAL, EXTERNAL_STORAGE,
    /// EXTERNAL_CACHE) map to the app's Documents directory on iOS, since iOS doesn't have
    /// Android's shared/external storage concept.
    static func resolveDirectoryURL(for directoryKey: String?) -> URL {
        let fileManager = FileManager.default
        let documents = (try? fileManager.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? fileManager.temporaryDirectory

        switch (directoryKey ?? "DATA").uppercased() {
        case "LIBRARY", "LIBRARY_NO_CLOUD":
            return (try? fileManager.url(for: .libraryDirectory, in: .userDomainMask, appropriateFor: nil, create: true)) ?? documents
        case "CACHE":
            return (try? fileManager.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)) ?? documents
        case "TEMPORARY":
            return fileManager.temporaryDirectory
        default:
            return documents
        }
    }

    /// LIBRARY_NO_CLOUD is documented as "Library directory without cloud backup" -- honor that by
    /// excluding the written file from iCloud/iTunes backup.
    static func excludeFromBackupIfNeeded(_ fileURL: URL, directoryKey: String?) {
        guard (directoryKey ?? "").uppercased() == "LIBRARY_NO_CLOUD" else { return }
        var url = fileURL
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }

    /// Renders a bridged JS value as a `String`: returned as-is if it's already a `String`,
    /// otherwise via `String(describing:)`. Used for header values and non-file multipart form
    /// fields, which may arrive as a non-`String` JS type (number, bool, ...).
    ///
    /// - Parameter value: a value from a request option
    /// - Returns: the value's string representation
    static func stringValue(_ value: JSValue) -> String {
        if let stringValue = value as? String {
            return stringValue
        }
        return "\(value)"
    }

    /// Builds a multipart/form-data body from the `{ _parts: [[key, value], ...] }` shape produced
    /// by src/index.ts (web FormData conversion) or passed directly as Capacitor file objects (see
    /// the root README's upload examples). Mirrors OkHttpUtils.buildFormDataRequestBody /
    /// addFormDataPart on Android.
    ///
    /// - Parameters:
    ///   - parts: an array of two-element `[key, value]` arrays; a file part's value is an object
    ///     with `type` plus one of `uri`/`path`/`data` (see `isFilePart`), anything else is sent as
    ///     a plain string field
    ///   - boundary: the multipart boundary string to delimit parts with (without the `--` prefix)
    /// - Returns: the assembled multipart body
    /// - Throws: `NativeHttpError.message` if a file part's data can't be read (see `loadFileData`)
    static func multipartBody(parts: JSArray, boundary: String) throws -> Data {
        var body = Data()
        let lineBreak = "\r\n"

        for partValue in parts {
            guard let part = partValue as? JSArray, part.count == 2 else { continue }

            let key: String
            if let stringKey = part[0] as? String {
                key = stringKey
            } else if let intKey = part[0] as? Int {
                key = String(intKey)
            } else {
                continue
            }

            body.append("--\(boundary)\(lineBreak)".data(using: .utf8)!)

            if let fileInfo = part[1] as? JSObject, isFilePart(fileInfo) {
                let fileName = (fileInfo["fileName"] as? String) ?? (fileInfo["name"] as? String) ?? "upload.bin"
                let mimeType = (fileInfo["type"] as? String) ?? "application/octet-stream"
                let fileData = try loadFileData(from: fileInfo)

                body.append("Content-Disposition: form-data; name=\"\(key)\"; filename=\"\(fileName)\"\(lineBreak)".data(using: .utf8)!)
                body.append("Content-Type: \(mimeType)\(lineBreak)\(lineBreak)".data(using: .utf8)!)
                body.append(fileData)
                body.append(lineBreak.data(using: .utf8)!)
            } else {
                body.append("Content-Disposition: form-data; name=\"\(key)\"\(lineBreak)\(lineBreak)".data(using: .utf8)!)
                body.append("\(stringValue(part[1]))\(lineBreak)".data(using: .utf8)!)
            }
        }

        body.append("--\(boundary)--\(lineBreak)".data(using: .utf8)!)
        return body
    }

    /// Determines whether a `[key, value]` multipart part's value represents a file (an object with
    /// a `type` plus one of `uri`/`path`/`data`) rather than a plain string form field.
    ///
    /// - Parameter value: `part[1]` from a `_parts` entry
    /// - Returns: `true` if `value` looks like a file descriptor
    private static func isFilePart(_ value: JSObject) -> Bool {
        guard value["type"] != nil else { return false }
        return value["uri"] != nil || value["path"] != nil || value["data"] != nil
    }

    /// Reads a file part's bytes, either decoded from a base64 `data` field or read directly from a
    /// native `uri`/`path` (a `file://` URL or plain filesystem path -- no temp-file copy is needed
    /// here the way Android's `TempFileManager` requires for `content://` URIs).
    ///
    /// - Parameter fileInfo: the file descriptor: `type`, `fileName`/`name`, and one of `data`
    ///   (base64) or `uri`/`path`
    /// - Returns: the file's raw bytes
    /// - Throws: `NativeHttpError.message` if neither a valid `data` nor a readable `uri`/`path` is present
    private static func loadFileData(from fileInfo: JSObject) throws -> Data {
        if let base64 = fileInfo["data"] as? String, let data = Data(base64Encoded: base64) {
            return data
        }
        if let uriString = (fileInfo["uri"] as? String) ?? (fileInfo["path"] as? String) {
            let url = URL(string: uriString) ?? URL(fileURLWithPath: uriString)
            return try Data(contentsOf: url)
        }
        throw NativeHttpError.message("No valid file data found for multipart part")
    }
}
