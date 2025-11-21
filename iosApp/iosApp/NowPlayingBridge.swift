import Foundation
import AVFoundation
import MediaPlayer
import UIKit
import ComposeApp

/// Bridge between Kotlin and iOS Now Playing/Remote Controls
/// This Swift class fully manages MPNowPlayingInfoCenter and MPRemoteCommandCenter
@objc public class NowPlayingBridge: NSObject {
    
    @objc public static let shared = NowPlayingBridge()
    
    private var isConfigured = false
    private var cachedArtworkUrl: String?
    private var cachedArtwork: MPMediaItemArtwork?
    
    private override init() {
        super.init()
    }
    
    /// Configure the bridge once - sets up command handlers and notification observer
    @objc public func configure() {
        guard !isConfigured else {
            print("[NowPlayingBridge] Already configured")
            return
        }
        
        // Observe notifications from Kotlin
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleUpdateNowPlayingNotification(_:)),
            name: NSNotification.Name("UpdateNowPlayingNotification"),
            object: nil
        )
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            let commandCenter = MPRemoteCommandCenter.shared()
            
            // clear existing targets to prevent duplicates/conflicts
            commandCenter.playCommand.removeTarget(nil)
            commandCenter.pauseCommand.removeTarget(nil)
            commandCenter.togglePlayPauseCommand.removeTarget(nil)
            commandCenter.nextTrackCommand.removeTarget(nil)
            commandCenter.previousTrackCommand.removeTarget(nil)
            commandCenter.changePlaybackPositionCommand.removeTarget(nil)
            
            // Configure command handlers
            commandCenter.playCommand.isEnabled = true
            commandCenter.playCommand.addTarget { [weak self] event in
                print("[NowPlayingBridge] Play command")
                RemoteCommandHandler.shared.handlePlay()
                return .success
            }
            
            commandCenter.pauseCommand.isEnabled = true
            commandCenter.pauseCommand.addTarget { [weak self] event in
                print("[NowPlayingBridge] Pause command")
                RemoteCommandHandler.shared.handlePause()
                return .success
            }
            
            // Headset button support
            commandCenter.togglePlayPauseCommand.isEnabled = true
            commandCenter.togglePlayPauseCommand.addTarget { [weak self] event in
                print("[NowPlayingBridge] Toggle play/pause command")
                RemoteCommandHandler.shared.handlePlayPause()
                return .success
            }
            
            commandCenter.nextTrackCommand.isEnabled = true
            commandCenter.nextTrackCommand.addTarget { [weak self] event in
                print("[NowPlayingBridge] Next track command")
                RemoteCommandHandler.shared.handleNext()
                return .success
            }
            
            commandCenter.previousTrackCommand.isEnabled = true
            commandCenter.previousTrackCommand.addTarget { [weak self] event in
                print("[NowPlayingBridge] Previous track command")
                RemoteCommandHandler.shared.handlePrevious()
                return .success
            }
            
            commandCenter.changePlaybackPositionCommand.isEnabled = true
            commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
                if let event = event as? MPChangePlaybackPositionCommandEvent {
                    print("[NowPlayingBridge] Seek command: \(event.positionTime)s")
                    RemoteCommandHandler.shared.handleSeek(positionSeconds: event.positionTime)
                    return .success
                }
                return .commandFailed
            }
            
            // Disable unsupported commands
            commandCenter.skipForwardCommand.isEnabled = false
            commandCenter.skipBackwardCommand.isEnabled = false
            commandCenter.seekForwardCommand.isEnabled = false
            commandCenter.seekBackwardCommand.isEnabled = false
            
            // Begin receiving remote control events
            UIApplication.shared.beginReceivingRemoteControlEvents()
            
            self.isConfigured = true
            print("[NowPlayingBridge] Configured successfully")
        }
    }
    
    /// Handle notification from Kotlin
    @objc private func handleUpdateNowPlayingNotification(_ notification: Notification) {
        guard let userInfo = notification.userInfo else { return }
        
        let title = userInfo["title"] as? String ?? ""
        let artist = userInfo["artist"] as? String ?? ""
        let album = userInfo["album"] as? String ?? ""
        let duration = userInfo["duration"] as? Double ?? 0.0
        let elapsed = userInfo["elapsed"] as? Double ?? 0.0
        let isPlaying = userInfo["isPlaying"] as? Bool ?? false
        let artworkUrl = userInfo["artworkUrl"] as? String
        
        updateNowPlayingInfo(
            title: title,
            artist: artist,
            album: album,
            durationSeconds: duration,
            elapsedSeconds: elapsed,
            isPlaying: isPlaying,
            artworkUrl: artworkUrl
        )
    }
    
    /// Update Now Playing info - the ONLY method that should update MPNowPlayingInfoCenter
    private func updateNowPlayingInfo(
        title: String,
        artist: String,
        album: String,
        durationSeconds: Double,
        elapsedSeconds: Double,
        isPlaying: Bool,
        artworkUrl: String?
    ) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            var nowPlayingInfo = [String: Any]()
            
            // Basic metadata
            nowPlayingInfo[MPMediaItemPropertyTitle] = title
            if !artist.isEmpty {
                nowPlayingInfo[MPMediaItemPropertyArtist] = artist
            }
            if !album.isEmpty {
                nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = album
            }
            
            // Timing
            nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = durationSeconds
            nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsedSeconds
            nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
            
            // Media type - this is CRITICAL for iOS to show controls
            nowPlayingInfo[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
            nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = false
            
            // Use cached artwork if available
            if let cached = self.cachedArtwork {
                nowPlayingInfo[MPMediaItemPropertyArtwork] = cached
            }
            
            // Update Now Playing info ONCE with all data
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
            
            // Update playback state - CRITICAL for controls to appear
            MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
            
            print("[NowPlayingBridge] Updated: \(title) - \(isPlaying ? "Playing" : "Paused")")
            
            // Load artwork only if URL changed
            if let urlString = artworkUrl, !urlString.isEmpty, urlString != self.cachedArtworkUrl {
                self.cachedArtworkUrl = urlString
                self.loadArtwork(from: urlString) { [weak self] artwork in
                    guard let self = self else { return }
                    if let artwork = artwork {
                        self.cachedArtwork = artwork
                        // Update artwork without touching other fields
                        DispatchQueue.main.async {
                            var updatedInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                            updatedInfo[MPMediaItemPropertyArtwork] = artwork
                            MPNowPlayingInfoCenter.default().nowPlayingInfo = updatedInfo
                            print("[NowPlayingBridge] Artwork loaded")
                        }
                    }
                }
            }
        }
    }
    
    /// Clear Now Playing info
    @objc public func clearNowPlaying() {
        DispatchQueue.main.async {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            MPNowPlayingInfoCenter.default().playbackState = .stopped
            print("[NowPlayingBridge] Cleared")
        }
    }
    
    // MARK: - Private Helpers
    
    private func loadArtwork(from urlString: String, completion: @escaping (MPMediaItemArtwork?) -> Void) {
        guard let url = URL(string: urlString) else {
            completion(nil)
            return
        }
        
        URLSession.shared.dataTask(with: url) { data, response, error in
            guard let data = data, let image = UIImage(data: data) else {
                completion(nil)
                return
            }
            
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { size in
                return image
            }
            
            DispatchQueue.main.async {
                completion(artwork)
            }
        }.resume()
    }
}

