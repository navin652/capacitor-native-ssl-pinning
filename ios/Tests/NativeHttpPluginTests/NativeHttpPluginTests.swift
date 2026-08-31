import XCTest
@testable import NativeHttpPlugin

/// Unit tests for `Utilities`. Kept intentionally small: most of this plugin's behavior needs a
/// live network/keychain (pinning, cookies, file I/O), which isn't practical to unit test here --
/// `npm run verify:ios` / manual testing in a host app is what actually exercises that.
class NativeHttpPluginTests: XCTestCase {
    /// `Utilities.domainName(from:)` should strip a leading "www." from the host.
    func testDomainNameStripsWWWPrefix() {
        XCTAssertEqual(Utilities.domainName(from: "https://www.example.com/path"), "example.com")
    }

    /// `Utilities.domainName(from:)` should leave a non-"www." subdomain untouched.
    func testDomainNameKeepsNonWWWHost() {
        XCTAssertEqual(Utilities.domainName(from: "https://api.example.com/path"), "api.example.com")
    }
}
