import Foundation
import MediaPlayer
import AVFoundation

/// Manages iOS Now Playing info (Control Center, Lock Screen)
/// and remote command handling (play/pause/next/prev buttons)
///
/// IMPORTANT: This app uses MPV (libmpv) for audio playback, not AVPlayer.
/// MPV uses its own AudioUnit output which may have different integration
/// characteristics with iOS's Now Playing system.
class NowPlayingManager {
    
    typealias CommandHandler = (String) -> Void
    
    static let shared = NowPlayingManager()
    
    private var commandHandler: CommandHandler?
    
    // State for caching
    private var lastTrackIdentifier: String?
    private var cachedArtwork: MPMediaItemArtwork?
    
    init() {
        print("🎵 NowPlayingManager: Initializing...")
        configureAudioSession()
        setupRemoteCommands()
        printDebugState("After init")
    }
    
    /// Configures the audio session for background playback
    /// This MUST be called for Control Center/Lock Screen to work
    private func configureAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            print("🎵 NowPlayingManager: Current category BEFORE config: \(session.category.rawValue)")
            print("🎵 NowPlayingManager: Current mode BEFORE config: \(session.mode.rawValue)")
            print("🎵 NowPlayingManager: Is other audio playing: \(session.isOtherAudioPlaying)")
            
            // Use .playback category for music playback
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
            
            print("🎵 NowPlayingManager: Audio session configured successfully")
            print("🎵 NowPlayingManager: Category AFTER config: \(session.category.rawValue)")
            print("🎵 NowPlayingManager: Mode AFTER config: \(session.mode.rawValue)")
            print("🎵 NowPlayingManager: Session is active: true")
        } catch {
            print("🎵 NowPlayingManager: ❌ Failed to configure audio session: \(error)")
        }
    }
    
    /// Call this when playback starts to ensure we become the Now Playing app
    func activatePlayback() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            print("🎵 NowPlayingManager: Playback activated")
            print("🎵 NowPlayingManager: Category: \(session.category.rawValue)")
            print("🎵 NowPlayingManager: Mode: \(session.mode.rawValue)")
            print("🎵 NowPlayingManager: Route: \(session.currentRoute.outputs.map { $0.portName }.joined(separator: ", "))")
        } catch {
            print("🎵 NowPlayingManager: ❌ Failed to activate playback: \(error)")
        }
    }
    
    /// Sets the handler for remote commands (play, pause, next, previous)
    func setCommandHandler(_ handler: @escaping CommandHandler) {
        self.commandHandler = handler
        print("🎵 NowPlayingManager: Command handler set - re-registering commands")
        
        // Re-register remote commands now that we have a handler
        setupRemoteCommands()
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
        print("🎵 NowPlayingManager: ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        print("🎵 NowPlayingManager: updateNowPlayingInfo called")
        print("🎵 NowPlayingManager:   Title: \(title ?? "nil")")
        print("🎵 NowPlayingManager:   Artist: \(artist ?? "nil")")
        print("🎵 NowPlayingManager:   Album: \(album ?? "nil")")
        print("🎵 NowPlayingManager:   Duration: \(duration)")
        print("🎵 NowPlayingManager:   Elapsed: \(elapsedTime)")
        print("🎵 NowPlayingManager:   Rate: \(playbackRate)")
        print("🎵 NowPlayingManager:   Thread: \(Thread.isMainThread ? "Main" : "Background")")
        
        let trackIdentifier = "\(title ?? "")-\(artist ?? "")-\(album ?? "")"
        
        // Dispatch to main thread synchronously for immediate effect
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            var nowPlayingInfo = [String: Any]()
            
            // Basic metadata
            if let title = title { nowPlayingInfo[MPMediaItemPropertyTitle] = title }
            if let artist = artist { nowPlayingInfo[MPMediaItemPropertyArtist] = artist }
            if let album = album { nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = album }
            
            // Playback info
            nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration
            nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsedTime
            nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = playbackRate
            
            // Reuse existing cached artwork if available
            if trackIdentifier == self.lastTrackIdentifier, let artwork = self.cachedArtwork {
                nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
                print("🎵 NowPlayingManager: Using cached artwork")
            }
            
            // SET THE INFO
            print("🎵 NowPlayingManager: Setting nowPlayingInfo with \(nowPlayingInfo.count) keys:")
            for (key, value) in nowPlayingInfo {
                if key == MPMediaItemPropertyArtwork {
                    print("🎵 NowPlayingManager:   \(key): <MPMediaItemArtwork>")
                } else {
                    print("🎵 NowPlayingManager:   \(key): \(value)")
                }
            }
            
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
            
            // Verify it was set
            let verifyInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo
            print("🎵 NowPlayingManager: Verification - nowPlayingInfo has \(verifyInfo?.count ?? 0) keys")
            
            self.printDebugState("After update")
            
            // Fetch new artwork if needed
            if trackIdentifier != self.lastTrackIdentifier {
                self.lastTrackIdentifier = trackIdentifier
                self.cachedArtwork = nil
                
                if let artworkUrlString = artworkUrl, let url = URL(string: artworkUrlString) {
                    print("🎵 NowPlayingManager: Loading artwork from URL...")
                    self.loadArtwork(from: url) { [weak self] artwork in
                        guard let self = self, let artwork = artwork else { return }
                        
                        DispatchQueue.main.async {
                            self.cachedArtwork = artwork
                            var updatedInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                            updatedInfo[MPMediaItemPropertyArtwork] = artwork
                            MPNowPlayingInfoCenter.default().nowPlayingInfo = updatedInfo
                            print("🎵 NowPlayingManager: Artwork loaded and applied")
                        }
                    }
                }
            }
        }
    }
    
    /// Clears the Now Playing info
    func clearNowPlayingInfo() {
        print("🎵 NowPlayingManager: Clearing Now Playing info")
        DispatchQueue.main.async { [weak self] in
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            self?.lastTrackIdentifier = nil
            self?.cachedArtwork = nil
            self?.printDebugState("After clear")
        }
    }
    
    // MARK: - Debug
    
    private func printDebugState(_ context: String) {
        let session = AVAudioSession.sharedInstance()
        let commandCenter = MPRemoteCommandCenter.shared()
        let infoCenter = MPNowPlayingInfoCenter.default()
        
        print("🎵 NowPlayingManager: ═══ DEBUG STATE (\(context)) ═══")
        print("🎵   AVAudioSession:")
        print("🎵     Category: \(session.category.rawValue)")
        print("🎵     Mode: \(session.mode.rawValue)")
        print("🎵     Route outputs: \(session.currentRoute.outputs.map { $0.portName })")
        print("🎵     Is other audio playing: \(session.isOtherAudioPlaying)")
        print("🎵   MPRemoteCommandCenter:")
        print("🎵     playCommand.isEnabled: \(commandCenter.playCommand.isEnabled)")
        print("🎵     pauseCommand.isEnabled: \(commandCenter.pauseCommand.isEnabled)")
        print("🎵     nextTrackCommand.isEnabled: \(commandCenter.nextTrackCommand.isEnabled)")
        print("🎵   MPNowPlayingInfoCenter:")
        if let info = infoCenter.nowPlayingInfo {
            print("🎵     Has info: YES (\(info.count) keys)")
            if let title = info[MPMediaItemPropertyTitle] {
                print("🎵     Title: \(title)")
            }
        } else {
            print("🎵     Has info: NO (nil)")
        }
        print("🎵 ═══════════════════════════════════════════════")
    }
    
    // MARK: - Private
    
    private func setupRemoteCommands() {
        let commandCenter = MPRemoteCommandCenter.shared()
        
        print("🎵 NowPlayingManager: Setting up remote commands...")
        
        // Helper to attach targets (removes old first to prevent duplicates)
        func addTarget(_ command: MPRemoteCommand, cmd: String, name: String) {
            command.removeTarget(nil) // Remove any previous handlers
            command.isEnabled = true
            command.addTarget { [weak self] _ in
                print("🎵 NowPlayingManager: Remote command received: \(name)")
                self?.commandHandler?(cmd)
                return .success
            }
            print("🎵 NowPlayingManager: Registered command: \(name)")
        }
        
        addTarget(commandCenter.playCommand, cmd: "play", name: "play")
        addTarget(commandCenter.pauseCommand, cmd: "pause", name: "pause")
        addTarget(commandCenter.togglePlayPauseCommand, cmd: "toggle_play_pause", name: "togglePlayPause")
        addTarget(commandCenter.nextTrackCommand, cmd: "next", name: "nextTrack")
        addTarget(commandCenter.previousTrackCommand, cmd: "previous", name: "previousTrack")
        
        // Scrubbing
        commandCenter.changePlaybackPositionCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let positionEvent = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            print("🎵 NowPlayingManager: Remote command received: seek to \(positionEvent.positionTime)")
            self?.commandHandler?("seek:\(positionEvent.positionTime)")
            return .success
        }
        print("🎵 NowPlayingManager: Registered command: changePlaybackPosition")

        commandCenter.skipForwardCommand.isEnabled = false
        commandCenter.skipBackwardCommand.isEnabled = false
        
        print("🎵 NowPlayingManager: Remote commands setup complete")
    }
    
    private func loadArtwork(from url: URL, completion: @escaping (MPMediaItemArtwork?) -> Void) {
        URLSession.shared.dataTask(with: url) { data, _, error in
            if let error = error {
                print("🎵 NowPlayingManager: ❌ Failed to load artwork: \(error)")
                completion(nil)
                return
            }
            
            guard let data = data, let image = UIImage(data: data) else {
                print("🎵 NowPlayingManager: ❌ Failed to create image from data")
                completion(nil)
                return
            }
            
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            print("🎵 NowPlayingManager: ✅ Artwork created successfully (\(Int(image.size.width))x\(Int(image.size.height)))")
            completion(artwork)
        }.resume()
    }
}
