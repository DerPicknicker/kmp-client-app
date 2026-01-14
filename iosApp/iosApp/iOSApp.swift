import SwiftUI
import ComposeApp
import UIKit

@main
struct iOSApp: App {
    // Keep a strong reference to the player
    private let player = MPVController()

    init() {
        // Register the Swift implementation with Kotlin
        PlatformPlayerProvider.shared.player = player
        
        // Required for apps using AudioUnit (like MPV) to appear in Control Center
        // Must be called for remote control events to work with non-AVPlayer audio
        UIApplication.shared.beginReceivingRemoteControlEvents()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}