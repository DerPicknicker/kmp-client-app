import Foundation
import MediaPlayer

/// Manages iOS Now Playing info (Control Center, Lock Screen)
/// and remote command handling (play/pause/next/prev buttons)
class NowPlayingManager {
    
    typealias CommandHandler = (String) -> Void
    
    private var commandHandler: CommandHandler?
    private var currentArtworkUrl: String?
    private var artworkLoadTask: URLSessionDataTask?
    
    init() {
        setupRemoteCommands()
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
        if let artworkUrl = artworkUrl, artworkUrl != currentArtworkUrl {
            currentArtworkUrl = artworkUrl
            loadArtwork(from: artworkUrl) { [weak self] image in
                guard let self = self, let image = image else { return }
                self.updateArtwork(image)
            }
        }
    }
    
    /// Updates just the elapsed time (for frequent progress updates)
    func updateElapsedTime(_ elapsedTime: Double, playbackRate: Double = 1.0) {
        var nowPlayingInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsedTime
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = playbackRate
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
    }
    
    /// Clears the Now Playing info
    func clearNowPlayingInfo() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        currentArtworkUrl = nil
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
