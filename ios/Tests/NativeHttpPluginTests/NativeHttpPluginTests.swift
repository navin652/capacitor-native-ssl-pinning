import XCTest
@testable import NativeHttpPlugin

class NativeHttpPluginTests: XCTestCase {
    func testDomainNameStripsWWWPrefix() {
        XCTAssertEqual(Utilities.domainName(from: "https://www.example.com/path"), "example.com")
    }

    func testDomainNameKeepsNonWWWHost() {
        XCTAssertEqual(Utilities.domainName(from: "https://api.example.com/path"), "api.example.com")
    }
}
