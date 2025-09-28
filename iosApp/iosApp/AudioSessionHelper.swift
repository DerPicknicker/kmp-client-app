import Foundation
import AVFoundation
import MediaPlayer
import UIKit

@objc public class AudioSessionHelper: NSObject {
    
    @objc public static let shared = AudioSessionHelper()
    private var isConfigured = false
    
    private override init() {
        super.init()
        // Configure audio session immediately on init
        configureAudioSession()
    }
    
    /// Configure the audio session for background playback and Control Center integration
    @objc public func configureAudioSession() {
        guard !isConfigured else {
            print("[AudioSessionHelper] Audio session already configured")
            return
        }
        
        do {
            let audioSession = AVAudioSession.sharedInstance()
            
            // Configure as long-form local playback (music/podcasts), not live stream
            if #available(iOS 13.0, *) {
                try audioSession.setCategory(
                    .playback,
                    mode: .default,
                    policy: .longFormAudio,
                    options: [.allowAirPlay, .allowBluetooth, .allowBluetoothA2DP]
                )
            } else {
                // Fallback for older iOS (should not be used on supported targets)
                try audioSession.setCategory(
                    .playback,
                    mode: .default,
                    options: [.allowAirPlay, .allowBluetooth, .allowBluetoothA2DP]
                )
            }
            
            // Activate the audio session
            try audioSession.setActive(true)
            
            print("[AudioSessionHelper] Audio session configured successfully for playback")
            isConfigured = true
            
            // Set up interruption handling
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(handleInterruption),
                name: AVAudioSession.interruptionNotification,
                object: audioSession
            )
            
            // Set up route change handling
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(handleRouteChange),
                name: AVAudioSession.routeChangeNotification,
                object: audioSession
            )
            
        } catch {
            print("[AudioSessionHelper] Failed to configure audio session: \(error.localizedDescription)")
        }
    }
    
    /// Deactivate the audio session
    @objc public func deactivateAudioSession() {
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            print("[AudioSessionHelper] Audio session deactivated")
        } catch {
            print("[AudioSessionHelper] Failed to deactivate audio session: \(error.localizedDescription)")
        }
    }
    
    /// Ensure Control Center controls are enabled
    @objc public func enableRemoteControls() {
        DispatchQueue.main.async {
            let commandCenter = MPRemoteCommandCenter.shared()
            
            // Enable all playback controls
            commandCenter.playCommand.isEnabled = true
            commandCenter.pauseCommand.isEnabled = true
            commandCenter.togglePlayPauseCommand.isEnabled = true
            commandCenter.nextTrackCommand.isEnabled = true
            commandCenter.previousTrackCommand.isEnabled = true
            commandCenter.changePlaybackPositionCommand.isEnabled = true
            commandCenter.skipForwardCommand.isEnabled = false
            commandCenter.skipBackwardCommand.isEnabled = false
            
            print("[AudioSessionHelper] Remote controls enabled")
        }
    }
    
    /// Set initial Now Playing info to register with Control Center
    @objc public func setInitialNowPlayingInfo() {
        var nowPlayingInfo: [String: Any] = [
            MPMediaItemPropertyTitle: "Music Assistant",
            MPMediaItemPropertyArtist: "Ready to play",
            MPMediaItemPropertyPlaybackDuration: 0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: 0,
            MPNowPlayingInfoPropertyPlaybackRate: 0
        ]
        // Explicitly mark as local long-form audio (not a live stream)
        nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = false
        nowPlayingInfo[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
        MPNowPlayingInfoCenter.default().playbackState = .paused
        
        print("[AudioSessionHelper] Initial Now Playing info set")
    }
    
    /// Prepare for playback - call this before starting audio
    @objc public func prepareForPlayback() {
        // Ensure audio session is active
        do {
            let audioSession = AVAudioSession.sharedInstance()
            if !audioSession.isOtherAudioPlaying {
                try audioSession.setActive(true)
            }
            
            DispatchQueue.main.async {
                // Enable remote controls
                self.enableRemoteControls()
                
                // Begin receiving remote control events
                UIApplication.shared.beginReceivingRemoteControlEvents()
                
                print("[AudioSessionHelper] Prepared for playback")
            }
        } catch {
            print("[AudioSessionHelper] Failed to prepare for playback: \(error.localizedDescription)")
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
