import XCTest

final class OrkaUITests: XCTestCase {
    func testNativeLoginAndApplicationNavigation() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["Good to see you."].waitForExistence(timeout: 30))
        XCTAssertTrue(app.buttons["Sign in"].exists)
        let login = XCTAttachment(screenshot: app.screenshot())
        login.name = "Orka iPhone native login"; login.lifetime = .keepAlways
        add(login)
        app.links["New here? Join your team"].tap()
        XCTAssertTrue(app.staticTexts["Join your team."].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Submit application"].exists)
        app.links["Back to sign in"].tap()
        XCTAssertTrue(app.staticTexts["Good to see you."].waitForExistence(timeout: 10))
        app.links["Forgot password?"].tap()
        XCTAssertTrue(app.staticTexts["Let’s get you back in."].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Send reset email"].exists)
        let reset = XCTAttachment(screenshot: app.screenshot())
        reset.name = "Orka iPhone password reset"; reset.lifetime = .keepAlways
        add(reset)
    }
}
