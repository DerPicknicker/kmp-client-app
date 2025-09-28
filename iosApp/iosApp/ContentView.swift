import UIKit
import SwiftUI
import ComposeApp
import AVFoundation

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // Configure audio session before creating the main view controller
        AudioSessionHelper.shared.configureAudioSession()
        AudioSessionHelper.shared.setInitialNowPlayingInfo()
        AudioSessionHelper.shared.enableRemoteControls()
        
        return MainViewControllerKt.MainViewController()
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



