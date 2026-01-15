import SwiftUI
import ComposeApp
import UIKit

@main
struct iOSApp: App {
    // Keep a strong reference to the player
    // Using MPVController because AudioConverter doesn't support streaming FLAC/Opus decoding
    // NativeAudioController would work for PCM-only or with external libraries (libFLAC, swift-opus)
    private let player = MPVController()

    init() {
        // Register the Swift implementation with Kotlin
        PlatformPlayerProvider.shared.player = player
        
        // Initialize NowPlayingManager early to configure AudioSession
        _ = NowPlayingManager.shared
        
        // Required for apps to appear in Control Center
        // Must be called for remote control events to work
        UIApplication.shared.beginReceivingRemoteControlEvents()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}