import Foundation
import MediaPlayer
import AVFoundation

/// Manages iOS Now Playing info (Control Center, Lock Screen)
/// and remote command handling (play/pause/next/prev buttons)
class NowPlayingManager {
    
    typealias CommandHandler = (String) -> Void
    
    static let shared = NowPlayingManager()
    
    private var commandHandler: CommandHandler?
    
    // State for caching
    private var lastTrackIdentifier: String?
    private var cachedArtwork: MPMediaItemArtwork?
    
    init() {
        configureAudioSession()
        setupRemoteCommands()
    }
    
    /// Configures the audio session for background playback
    /// This MUST be called for Control Center/Lock Screen to work
    private func configureAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            // Strictly match reference implementation: .playback category, .default mode
            // Removed .longFormAudio policy as it wasn't in the working reference
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)
            print("NowPlayingManager: Audio session configured")
        } catch {
            print("NowPlayingManager: Failed to configure audio session: \(error)")
        }
    }
    
    /// Call this when playback starts to ensure we become the Now Playing app
    func activatePlayback() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            print("NowPlayingManager: Playback activated. Category: \(session.category.rawValue), Mode: \(session.mode.rawValue)")
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
        
        let trackIdentifier = "\(title ?? "")-\(artist ?? "")-\(album ?? "")"
        
        Task { @MainActor in
            var nowPlayingInfo = [String: Any]()
            
            // Basic metadata
            if let title = title { nowPlayingInfo[MPMediaItemPropertyTitle] = title }
            if let artist = artist { nowPlayingInfo[MPMediaItemPropertyArtist] = artist }
            if let album = album { nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = album }
            
            // Playback info
            nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration
            nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsedTime
            nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = playbackRate
            
            // 1. UPDATE IMMEDIATELY with metadata (so controls appear fast)
            // Reuse existing cached artwork if effective
            if trackIdentifier == self.lastTrackIdentifier, let artwork = self.cachedArtwork {
                nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
            }
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
            
            // 2. Fetch new artwork if needed
            if trackIdentifier != self.lastTrackIdentifier {
                self.lastTrackIdentifier = trackIdentifier
                self.cachedArtwork = nil
                
                if let artworkUrlString = artworkUrl, let url = URL(string: artworkUrlString) {
                    print("NowPlayingManager: Loading artwork from \(artworkUrlString)")
                    if let artwork = await self.loadArtworkAsync(from: url) {
                        self.cachedArtwork = artwork
                        // Update info again with artwork
                        var updatedInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? nowPlayingInfo
                        updatedInfo[MPMediaItemPropertyArtwork] = artwork
                        MPNowPlayingInfoCenter.default().nowPlayingInfo = updatedInfo
                        print("NowPlayingManager: Artwork loaded and cached. Info updated.")
                    }
                }
            }
        }
    }
    
    // Explicitly used for frequent progress updates if needed, though usually system handles interpolation
    // if rate is set correctly.
    func updateElapsedTime(_ elapsedTime: Double, playbackRate: Double = 1.0) {
        Task { @MainActor in
            var nowPlayingInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsedTime
            nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = playbackRate
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
        }
    }
    
    /// Clears the Now Playing info
    func clearNowPlayingInfo() {
        print("NowPlayingManager: Clearing Now Playing info")
        Task { @MainActor in
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            self.lastTrackIdentifier = nil
            self.cachedArtwork = nil
        }
    }
    
    // MARK: - Private
    
    private func setupRemoteCommands() {
        let commandCenter = MPRemoteCommandCenter.shared()
        
        // Helper to attach targets
        func addTarget(_ command: MPRemoteCommand, cmd: String) {
            command.isEnabled = true
            command.addTarget { [weak self] _ in
                self?.commandHandler?(cmd)
                return .success
            }
        }
        
        addTarget(commandCenter.playCommand, cmd: "play")
        addTarget(commandCenter.pauseCommand, cmd: "pause")
        addTarget(commandCenter.togglePlayPauseCommand, cmd: "toggle_play_pause")
        addTarget(commandCenter.nextTrackCommand, cmd: "next")
        addTarget(commandCenter.previousTrackCommand, cmd: "previous")
        
        // Scrubbing
        commandCenter.changePlaybackPositionCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
             guard let positionEvent = event as? MPChangePlaybackPositionCommandEvent else {
                 return .commandFailed
             }
             self?.commandHandler?("seek:\(positionEvent.positionTime)")
             return .success
        }

        commandCenter.skipForwardCommand.isEnabled = false
        commandCenter.skipBackwardCommand.isEnabled = false
    }
    
    private func loadArtworkAsync(from url: URL) async -> MPMediaItemArtwork? {
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            guard let image = UIImage(data: data) else { return nil }
            return MPMediaItemArtwork(boundsSize: image.size) { _ in image }
        } catch {
            print("NowPlayingManager: Failed to load artwork: \(error)")
            return nil
        }
    }
}
