import Capacitor
import UIKit
import UserNotifications

class OrkaViewController: CAPBridgeViewController {
    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(OrkaDevicePlugin())
    }
}

@objc(OrkaDevicePlugin)
public class OrkaDevicePlugin: CAPPlugin, CAPBridgedPlugin, UIDocumentPickerDelegate {
    public let identifier = "OrkaDevicePlugin"
    public let jsName = "OrkaDevice"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "configureNotifications", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "notificationStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openNotificationSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "previewNotification", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setAppearance", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "saveFile", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "print", returnType: CAPPluginReturnPromise)
    ]
    private var exportCall: CAPPluginCall?
    private var exportURL: URL?

    @objc func configureNotifications(_ call: CAPPluginCall) { notificationStatus(call) }
    @objc func notificationStatus(_ call: CAPPluginCall) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            call.resolve([
                "notificationsEnabled": settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional,
                "channelId": "", "supportsChannels": false, "importance": 0,
                "soundEnabled": settings.soundSetting == .enabled,
                "sound": NSNull(), "soundUsage": 0, "vibrationEnabled": false,
                "bypassDnd": false, "policyAccessGranted": false,
                "interruptionFilter": 0, "alarmVolume": 0, "alarmMaxVolume": 0,
                "ringerMode": 0, "batteryOptimizationsIgnored": false
            ])
        }
    }
    @objc func openNotificationSettings(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            guard let url = URL(string: UIApplication.openSettingsURLString) else {
                call.resolve(["opened": false]); return
            }
            UIApplication.shared.open(url) { opened in call.resolve(["opened": opened]) }
        }
    }
    @objc func previewNotification(_ call: CAPPluginCall) {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error = error { call.reject("Notification permission could not be requested", nil, error); return }
            guard granted else { call.resolve(["shown": false]); return }
            let content = UNMutableNotificationContent()
            content.title = "Orka local notification test"
            content.body = "This test was scheduled on your phone. It is not a server push."
            content.sound = .default
            let request = UNNotificationRequest(identifier: "orka-local-preview", content: content,
                trigger: UNTimeIntervalNotificationTrigger(timeInterval: 10, repeats: false))
            center.add(request) { error in
                if let error = error { call.reject("The local notification could not be scheduled", nil, error) }
                else { call.resolve(["shown": true]) }
            }
        }
    }
    @objc func setAppearance(_ call: CAPPluginCall) {
        guard let hex = call.getString("color"), hex.range(of: "^#[0-9a-fA-F]{6}$", options: .regularExpression) != nil,
              let rgb = UInt32(hex.dropFirst(), radix: 16) else { call.reject("Invalid appearance color"); return }
        DispatchQueue.main.async {
            self.bridge?.viewController?.overrideUserInterfaceStyle = call.getBool("dark", false) ? .dark : .light
            self.bridge?.viewController?.view.backgroundColor = UIColor(
                red: CGFloat((rgb >> 16) & 255) / 255, green: CGFloat((rgb >> 8) & 255) / 255,
                blue: CGFloat(rgb & 255) / 255, alpha: 1)
            self.bridge?.viewController?.setNeedsStatusBarAppearanceUpdate()
            call.resolve()
        }
    }
    @objc func saveFile(_ call: CAPPluginCall) {
        guard let name = call.getString("name"), !name.isEmpty, name.count <= 160,
              !name.contains("/"), !name.contains("\\"), name != ".", name != "..",
              let mime = call.getString("mimeType"), ["image/png", "text/plain"].contains(mime),
              let encoded = call.getString("base64"), encoded.utf8.count <= 11_184_812,
              let data = Data(base64Encoded: encoded), data.count <= 8_388_608 else {
            call.reject("Invalid export or file exceeds 8 MiB"); return
        }
        DispatchQueue.main.async {
            guard self.exportCall == nil, let controller = self.bridge?.viewController else {
                call.reject("Finish the current file export before trying again"); return
            }
            do {
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let url = directory.appendingPathComponent(name)
                try data.write(to: url, options: .atomic)
                self.exportCall = call; self.exportURL = url
                let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
                picker.delegate = self
                controller.present(picker, animated: true)
            } catch { call.reject("The file could not be exported", nil, error) }
        }
    }
    public func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        finishExport(saved: !urls.isEmpty)
    }
    public func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finishExport(saved: false) }
    private func finishExport(saved: Bool) {
        exportCall?.resolve(["saved": saved]); exportCall = nil
        if let url = exportURL { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        exportURL = nil
    }
    @objc func print(_ call: CAPPluginCall) {
        guard let html = call.getString("html"), html.utf8.count <= 16_777_216,
              let title = call.getString("title") else { call.reject("Invalid print document"); return }
        DispatchQueue.main.async {
            guard let view = self.bridge?.viewController?.view, UIPrintInteractionController.isPrintingAvailable else {
                call.reject("Printing is unavailable on this device"); return
            }
            let printer = UIPrintInteractionController.shared
            let info = UIPrintInfo(dictionary: nil)
            info.jobName = String(title.prefix(160)); info.outputType = .general
            printer.printInfo = info
            printer.printFormatter = UIMarkupTextPrintFormatter(markupText: html)
            let completion: UIPrintInteractionController.CompletionHandler = { _, completed, error in
                if let error = error { call.reject("Printing failed", nil, error) }
                else { call.resolve(["started": completed]) }
            }
            if UIDevice.current.userInterfaceIdiom == .pad {
                printer.present(from: view.bounds, in: view, animated: true, completionHandler: completion)
            } else { printer.present(animated: true, completionHandler: completion) }
        }
    }
}
