import Foundation
import AVFoundation
import MediaPlayer
import UIKit
import ComposeApp

@objc public class AudioSessionHelper: NSObject {
    
    @objc public static let shared = AudioSessionHelper()
    private var isConfigured = false
    private var commandHandlersConfigured = false
    
    private override init() {
        super.init()
        // Configure audio session immediately on init
        configureAudioSession()
    }
    
    /// Configure the audio session for background playback
    @objc public func configureAudioSession() {
        guard !isConfigured else {
            print("[AudioSessionHelper] Already configured")
            return
        }
        
        do {
            let audioSession = AVAudioSession.sharedInstance()
            
            // Configure for music playback with background support
            if #available(iOS 13.0, *) {
                try audioSession.setCategory(
                    .playback,
                    mode: .default,
                    policy: .longFormAudio,
                    options: []
                )
            } else {
                try audioSession.setCategory(
                    .playback,
                    mode: .default,
                    options: []
                )
            }
            
            // Activate the audio session
            try audioSession.setActive(true)
            
            print("[AudioSessionHelper] Audio session configured")
            isConfigured = true
            
            // Set up interruption and route change handling
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(handleInterruption),
                name: AVAudioSession.interruptionNotification,
                object: audioSession
            )
            
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(handleRouteChange),
                name: AVAudioSession.routeChangeNotification,
                object: audioSession
            )
            
        } catch {
            print("[AudioSessionHelper] Failed: \(error.localizedDescription)")
        }
    }
    
    
    // MARK: - Interruption Handling
    
    @objc private func handleInterruption(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }
        
        switch type {
        case .began:
            print("[AudioSessionHelper] Audio interruption began")
            // Audio has been interrupted, pause playback
            NotificationCenter.default.post(name: .audioSessionInterrupted, object: nil)
            
        case .ended:
            print("[AudioSessionHelper] Audio interruption ended")
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    // Resume playback
                    NotificationCenter.default.post(name: .audioSessionResumed, object: nil)
                }
            }
            
        @unknown default:
            break
        }
    }
    
    @objc private func handleRouteChange(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }
        
        switch reason {
        case .oldDeviceUnavailable:
            // Headphones were unplugged, or audio output changed
            print("[AudioSessionHelper] Audio route changed: old device unavailable")
            NotificationCenter.default.post(name: .audioRouteChanged, object: nil)
            
        case .newDeviceAvailable:
            print("[AudioSessionHelper] Audio route changed: new device available")
            
        default:
            print("[AudioSessionHelper] Audio route changed: \(reason)")
        }
    }
}

// MARK: - Custom Notifications

extension Notification.Name {
    static let audioSessionInterrupted = Notification.Name("audioSessionInterrupted")
    static let audioSessionResumed = Notification.Name("audioSessionResumed")
    static let audioRouteChanged = Notification.Name("audioRouteChanged")
}
