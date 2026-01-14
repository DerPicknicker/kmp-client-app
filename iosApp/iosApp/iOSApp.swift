import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    // Keep a strong reference to the player
    private let player = MPVController()

    init() {
        // Register the Swift implementation with Kotlin
        PlatformPlayerProvider.shared.player = player
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}