import XCTest

final class OrkaUITests: XCTestCase {
    func testLocalNotificationWhileAppIsInBackground() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["Good to see you."].waitForExistence(timeout: 30))
        app.buttons["Phone notification test"].tap()
        let preview = app.buttons["Test local notification"]
        XCTAssertTrue(preview.waitForExistence(timeout: 10))
        app.swipeUp()
        preview.tap()
        let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = system.alerts.buttons["Allow"]
        if allow.waitForExistence(timeout: 5) { allow.tap() }
        XCTAssertTrue(app.staticTexts["Local test scheduled in 10 seconds. Lock your phone to check its notification."].waitForExistence(timeout: 10))
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(system.staticTexts["Orka local notification test"].waitForExistence(timeout: 20))
        let evidence = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        evidence.name = "Orka local notification on iOS"; evidence.lifetime = .keepAlways
        add(evidence)
    }
    func testNativeLoginAndApplicationNavigation() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["Good to see you."].waitForExistence(timeout: 30))
        XCTAssertTrue(app.buttons["Sign in"].exists)
        let header = app.links.firstMatch
        XCTAssertTrue(header.exists)
        let screen = app.windows.firstMatch.frame
        let viewport = app.webViews.firstMatch.frame
        // System status bars are not descendants of the tested application.
        // On this notched iPhone simulator, the usable WebView must be inset
        // from both physical screen edges, with its header inside that area.
        XCTAssertGreaterThan(viewport.minY, screen.minY)
        XCTAssertLessThan(viewport.maxY, screen.maxY)
        XCTAssertGreaterThanOrEqual(header.frame.minY, viewport.minY)
        let login = XCTAttachment(screenshot: app.screenshot())
        login.name = "Orka iPhone native login"; login.lifetime = .keepAlways
        add(login)
        app.links["New here? Join your team"].tap()
        XCTAssertTrue(app.staticTexts["Join your team."].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Submit application"].exists)
        app.links["Back to sign in"].tap()
        XCTAssertTrue(app.staticTexts["Good to see you."].waitForExistence(timeout: 10))
        app.links["Forgot password?"].tap()
        XCTAssertTrue(app.staticTexts["Let\u{2019}s get you back in."].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Send reset email"].exists)
        let reset = XCTAttachment(screenshot: app.screenshot())
        reset.name = "Orka iPhone password reset"; reset.lifetime = .keepAlways
        add(reset)
    }
}
