import SwiftUI
import AVFoundation
import UIKit

@main
struct iOSApp: App {
    init() {
        configureAudioSession()
        UIApplication.shared.beginReceivingRemoteControlEvents()
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

private func configureAudioSession() {
    do {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .default, options: [])
        try session.setActive(true)
    } catch {
        NSLog("AVAudioSession error: \(error.localizedDescription)")
    }
}