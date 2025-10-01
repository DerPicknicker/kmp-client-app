import UIKit
import SwiftUI
import ComposeApp
import AVFoundation

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // 1. Configure audio session for background playback
        AudioSessionHelper.shared.configureAudioSession()
        
        // 2. Create the main view controller (initializes Kotlin side)
        let viewController = MainViewControllerKt.MainViewController()
        
        // 3. Configure Now Playing bridge (command handlers + Now Playing management)
        NowPlayingBridge.shared.configure()
        
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



