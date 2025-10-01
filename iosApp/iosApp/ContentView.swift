import UIKit
import SwiftUI
import ComposeApp
import AVFoundation

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // Configure audio session first
        AudioSessionHelper.shared.configureAudioSession()
        
        // Create the main view controller which initializes NowPlayingManager and RemoteCommandHandler
        let viewController = MainViewControllerKt.MainViewController()
        
        // Configure command handlers BEFORE enabling commands
        AudioSessionHelper.shared.configureRemoteCommandHandlers()
        
        // Now set initial info and prepare for playback
        AudioSessionHelper.shared.setInitialNowPlayingInfo()
        AudioSessionHelper.shared.prepareForPlayback()
        
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



