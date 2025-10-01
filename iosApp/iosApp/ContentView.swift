import UIKit
import SwiftUI
import ComposeApp
import AVFoundation

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // Configure audio session before creating the main view controller
        AudioSessionHelper.shared.configureAudioSession()
        AudioSessionHelper.shared.setInitialNowPlayingInfo()
        AudioSessionHelper.shared.prepareForPlayback()
        
        // Create the main view controller which initializes NowPlayingManager and RemoteCommandHandler
        let viewController = MainViewControllerKt.MainViewController()
        
        // Now configure the Swift command handlers that will call into Kotlin
        AudioSessionHelper.shared.configureRemoteCommandHandlers()
        
        return viewController
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
                .ignoresSafeArea() // render edge-to-edge
                .ignoresSafeArea(.keyboard) // Compose has own keyboard handler
    }
}



