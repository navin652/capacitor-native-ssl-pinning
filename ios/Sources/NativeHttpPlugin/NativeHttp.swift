import Foundation

@objc public class NativeHttp: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
