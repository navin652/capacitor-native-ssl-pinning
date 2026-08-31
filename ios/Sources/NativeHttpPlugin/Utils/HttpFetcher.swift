import Foundation
import Capacitor

/// Orchestrates a single fetch() call: builds a request, executes it against the right
/// pinned/trust-all URLSession, and shapes the response. Mirrors
/// android/src/main/java/com/cap/nativehttp/utils/HttpFetcher.java (fetch / handleResponse).
final class HttpFetcher {
    /// Runs an HTTP request with SSL/public-key pinning and resolves/rejects `call` asynchronously
    /// once it completes. See `URLSessionUtils.session(forDomain:options:)` for how the session
    /// (and its pinning mode) is chosen.
    ///
    /// - Parameter call: expects a `url` String and an `options` object
    func fetch(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url"), let options = call.getObject("options") else {
            call.reject("Missing required parameters")
            return
        }

        let domain = Utilities.domainName(from: urlString)

        let session: URLSession
        let request: URLRequest
        do {
            session = try URLSessionUtils.session(forDomain: domain, options: options)
            request = try URLSessionUtils.buildRequest(urlString: urlString, options: options)
        } catch NativeHttpError.message(let message) {
            call.reject(message)
            return
        } catch {
            call.reject("Unexpected error occurred", error.localizedDescription)
            return
        }

        if URLSessionUtils.enableDebugLogging {
            CAPLog.print("[NativeHttp] --> \(request.httpMethod ?? "GET") \(urlString)")
            if let headers = request.allHTTPHeaderFields, !headers.isEmpty {
                CAPLog.print("[NativeHttp] Headers: \(headers)")
            }
        }

        let task = session.dataTask(with: request) { [weak self] data, response, error in
            if let error = error {
                call.reject("Error in network request", error.localizedDescription)
                return
            }
            guard let httpResponse = response as? HTTPURLResponse, let data = data else {
                call.reject("Empty response body")
                return
            }
            self?.handleResponse(call: call, options: options, data: data, response: httpResponse)
        }
        task.resume()
    }

    /// Shapes a completed response into the plugin result and resolves/rejects `call`. Behavior
    /// depends on `options.responseType`: `file`/`blob` write the body to disk (under the directory
    /// resolved by `Utilities.resolveDirectoryURL`) and resolve with
    /// `fileDetails: { path, mimeType }`; `base64` resolves with `fileDetails: { data, mimeType }`;
    /// the default, `text`, resolves with `bodyString`. Every response also includes `headers` and
    /// `status`. A non-2xx status rejects with code `"API Response"` and the built result as the
    /// message, matching Android's `HttpFetcher.handleResponse`.
    ///
    /// - Parameters:
    ///   - call: the plugin call to resolve/reject
    ///   - options: the request options, consulted for `responseType`, `fileSaveDirectory`, `fileName`
    ///   - data: the response body bytes
    ///   - response: the completed HTTP response
    private func handleResponse(call: CAPPluginCall, options: JSObject, data: Data, response: HTTPURLResponse) {
        var result: [String: Any] = [:]
        let responseType = (options["responseType"] as? String) ?? "text"

        do {
            switch responseType {
            case "file", "blob":
                let dirKey = options["fileSaveDirectory"] as? String
                let directory = Utilities.resolveDirectoryURL(for: dirKey)
                let fileName = (options["fileName"] as? String) ?? "\(Int(Date().timeIntervalSince1970 * 1000)).bin"
                let fileURL = directory.appendingPathComponent(fileName)
                try data.write(to: fileURL, options: .atomic)
                Utilities.excludeFromBackupIfNeeded(fileURL, directoryKey: dirKey)

                result["fileDetails"] = [
                    "path": fileURL.path,
                    "mimeType": response.mimeType ?? "application/octet-stream"
                ]
            case "base64":
                result["fileDetails"] = [
                    "data": data.base64EncodedString(),
                    "mimeType": response.mimeType ?? "application/octet-stream"
                ]
            default:
                result["bodyString"] = String(data: data, encoding: .utf8) ?? ""
            }
        } catch {
            call.reject("Unexpected error occurred", error.localizedDescription)
            return
        }

        result["headers"] = Utilities.responseHeaders(response)
        result["status"] = response.statusCode

        if URLSessionUtils.enableDebugLogging {
            CAPLog.print("[NativeHttp] <-- \(response.statusCode) \(response.url?.absoluteString ?? "")")
        }

        if (200...299).contains(response.statusCode) {
            call.resolve(result)
        } else {
            call.reject("API Response", "\(result)")
        }
    }
}
