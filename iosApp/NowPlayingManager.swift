import Foundation
import MediaPlayer
import AVFoundation

/// Manages iOS Now Playing info (Control Center, Lock Screen)
/// and remote command handling (play/pause/next/prev buttons)
class NowPlayingManager {
    
    typealias CommandHandler = (String) -> Void
    
    static let shared = NowPlayingManager()
    
    private var commandHandler: CommandHandler?
    private var currentArtworkUrl: String?
    private var artworkLoadTask: URLSessionDataTask?
    
    init() {
        configureAudioSession()
        setupRemoteCommands()
    }
    
    /// Configures the audio session for background playback
    /// This MUST be called for Control Center/Lock Screen to work
    private func configureAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            // Use longFormAudio policy for music streaming apps - required for Control Center
            try session.setCategory(.playback, mode: .default, policy: .longFormAudio, options: [])
            try session.setPreferredIOBufferDuration(0.01) // 10ms for low latency
            try session.setActive(true)
            print("NowPlayingManager: Audio session configured with longFormAudio policy")
        } catch {
            print("NowPlayingManager: Failed to configure audio session: \(error)")
        }
    }
    
    /// Call this when playback starts to ensure we become the Now Playing app
    func activatePlayback() {
        do {
            let session = AVAudioSession.sharedInstance()
            // Re-activate session to ensure we're the current Now Playing app
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            print("NowPlayingManager: Playback activated")
        } catch {
            print("NowPlayingManager: Failed to activate playback: \(error)")
        }
    }
    
    /// Sets the handler for remote commands (play, pause, next, previous)
    func setCommandHandler(_ handler: @escaping CommandHandler) {
        self.commandHandler = handler
    }
    
    /// Updates the Now Playing info displayed in Control Center and Lock Screen
    func updateNowPlayingInfo(
        title: String?,
        artist: String?,
        album: String?,
        artworkUrl: String?,
        duration: Double,
        elapsedTime: Double,
        playbackRate: Double
    ) {
        print("NowPlayingManager: updateNowPlayingInfo called - Title: \(title ?? "nil"), Rate: \(playbackRate)")
        var nowPlayingInfo: [String: Any] = [:]
        
        // Basic metadata
        if let title = title {
            nowPlayingInfo[MPMediaItemPropertyTitle] = title
        }
        if let artist = artist {
            nowPlayingInfo[MPMediaItemPropertyArtist] = artist
        }
        if let album = album {
            nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = album
        }
        
        // Playback info
        nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsedTime
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = playbackRate
        
        // Set info immediately (without artwork)
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
        
        // Load artwork asynchronously if URL changed
        if let artworkUrl = artworkUrl {
            if artworkUrl != currentArtworkUrl {
                currentArtworkUrl = artworkUrl
                loadArtwork(from: artworkUrl) { [weak self] image in
                    guard let self = self, let image = image else { return }
                    self.updateArtwork(image)
                }
            } else if let currentImage = self.currentImage {
                 // Re-apply existing artwork if strictly needed, or just rely on the fact that we preserved it?
                 // Actually, clearing and resetting might lose the artwork if we don't re-set it.
                 // Ideally we cache the image.
                 self.updateArtwork(currentImage)
            }
        }
    }
    
    // Cache the current image to re-apply it easily
    private var currentImage: UIImage?
    
    /// Updates just the elapsed time (for frequent progress updates)
    func updateElapsedTime(_ elapsedTime: Double, playbackRate: Double = 1.0) {
        var nowPlayingInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsedTime
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = playbackRate
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
    }
    
    /// Clears the Now Playing info
    func clearNowPlayingInfo() {
        print("NowPlayingManager: Clearing Now Playing info")
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        currentArtworkUrl = nil
        currentImage = nil
        artworkLoadTask?.cancel()
    }
    
    // MARK: - Private
    
    private func setupRemoteCommands() {
        let commandCenter = MPRemoteCommandCenter.shared()
        
        // Play command
        commandCenter.playCommand.isEnabled = true
        commandCenter.playCommand.addTarget { [weak self] _ in
            self?.commandHandler?("play")
            return .success
        }
        
        // Pause command
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            self?.commandHandler?("pause")
            return .success
        }
        
        // Toggle play/pause command
        commandCenter.togglePlayPauseCommand.isEnabled = true
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.commandHandler?("toggle_play_pause")
            return .success
        }
        
        // Next track command
        commandCenter.nextTrackCommand.isEnabled = true
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            self?.commandHandler?("next")
            return .success
        }
        
        // Previous track command
        commandCenter.previousTrackCommand.isEnabled = true
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            self?.commandHandler?("previous")
            return .success
        }
        
        // Change Playback Position (Scrubbing)
        commandCenter.changePlaybackPositionCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
             guard let positionEvent = event as? MPChangePlaybackPositionCommandEvent else {
                 return .commandFailed
             }
             // Send "seek:12.5"
             self?.commandHandler?("seek:\(positionEvent.positionTime)")
             return .success
        }

        // Note: Skip forward/backward can be added if needed
        commandCenter.skipForwardCommand.isEnabled = false
        commandCenter.skipBackwardCommand.isEnabled = false
    }
    
    private func loadArtwork(from urlString: String, completion: @escaping (UIImage?) -> Void) {
        // Cancel any previous load
        artworkLoadTask?.cancel()
        
        guard let url = URL(string: urlString) else {
            print("NowPlayingManager: Invalid artwork URL: \(urlString)")
            completion(nil)
            return
        }
        
        print("NowPlayingManager: Loading artwork from \(urlString)")
        
        artworkLoadTask = URLSession.shared.dataTask(with: url) { data, response, error in
            DispatchQueue.main.async {
                if let error = error {
                    print("NowPlayingManager: Failed to load artwork: \(error.localizedDescription)")
                    completion(nil)
                    return
                }
                
                guard let data = data, let image = UIImage(data: data) else {
                    print("NowPlayingManager: Failed to decode artwork image")
                    completion(nil)
                    return
                }
                
                print("NowPlayingManager: Artwork loaded successfully (\(image.size.width)x\(image.size.height))")
                self.currentImage = image
                completion(image)
            }
        }
        artworkLoadTask?.resume()
    }
    
    private func updateArtwork(_ image: UIImage) {
        var nowPlayingInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        
        let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
        nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
        
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
    }
}
