@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.music_assistant.client.nowplaying

import co.touchlab.kermit.Logger
import io.music_assistant.client.data.MainDataSource
import io.music_assistant.client.data.model.client.PlayerData
import io.music_assistant.client.ui.compose.main.PlayerAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.Foundation.NSData
import platform.Foundation.create
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyIsLiveStream
import platform.MediaPlayer.MPNowPlayingInfoPropertyMediaType
import platform.MediaPlayer.MPNowPlayingInfoMediaTypeAudio
import platform.MediaPlayer.MPNowPlayingPlaybackStatePaused
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatus
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.call.body
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS Now Playing and Remote Command Center integration.
 * Listens to MainDataSource and exposes media controls on Lock Screen, Control Center, Dynamic Island.
 */
class NowPlayingManager(
    private val dataSource: MainDataSource,
) : CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext = Dispatchers.Main + job

    private var currentPlayer: PlayerData? = null
    private var cachedArtworkUrl: String? = null
    private var cachedArtwork: MPMediaItemArtwork? = null
    private val log = Logger.withTag("NowPlaying")
    
    fun getCurrentPlayer(): PlayerData? = currentPlayer

    init {
        // Configure audio session first to ensure Control Center integration
        configureAudioSession()
        // Set up Swift-based command handlers with Kotlin callbacks
        setupRemoteCommandHandlers()
        // Set up initial Now Playing info to activate Control Center
        setupInitialNowPlayingInfo()
        observePlayers()
    }

    private fun observePlayers() {
        launch {
            dataSource.playersData.collectLatest { list ->
                // Prefer local builtin player; otherwise prefer any playing player; otherwise first
                val builtin = list.firstOrNull { it.player.isBuiltin }
                val active = builtin ?: list.firstOrNull { it.player.isPlaying } ?: list.firstOrNull()
                currentPlayer = active

                // Swift handles command enabling - just update Now Playing info
                active?.let { updateNowPlaying(it, multiplePlayers = list.size > 1) }
            }
        }
    }

    private fun configureAudioSession() {
        // Audio session is configured by Swift code in ContentView
        log.i { "Audio session configuration handled by Swift layer" }
        
        // Set up the app as "now playing" even before audio starts
        dispatch_async(dispatch_get_main_queue()) {
            val infoCenter = MPNowPlayingInfoCenter.defaultCenter()
            // Initialize with minimal info to activate Control Center
            infoCenter.playbackState = MPNowPlayingPlaybackStatePaused
            infoCenter.nowPlayingInfo = mapOf(
                MPMediaItemPropertyTitle to "Music Assistant",
                MPMediaItemPropertyArtist to "Ready to play",
                MPMediaItemPropertyPlaybackDuration to 0.0,
                MPNowPlayingInfoPropertyElapsedPlaybackTime to 0.0,
                MPNowPlayingInfoPropertyPlaybackRate to 0.0
            )
            log.i { "Initial Now Playing info set to activate Control Center" }
        }
    }
    
    private fun setupRemoteCommandHandlers() {
        // Initialize the singleton handler that Swift will call
        RemoteCommandHandler.initialize(dataSource, this)
        log.i { "Remote command handler initialized - Swift will call Kotlin methods" }
    }
    
    private fun setupInitialNowPlayingInfo() {
        // Swift handles command setup - we just need to wait for initialization
        log.i { "Swift AudioSessionHelper will configure commands and Now Playing" }
    }

    private fun updateNowPlaying(playerData: PlayerData, multiplePlayers: Boolean) {
        val serverUrl = dataSource.apiClient.serverInfo.value?.baseUrl
        val track = playerData.queue?.currentItem?.track
        val title = track?.name ?: playerData.player.name
        val artist = track?.subtitle ?: ""
        val album = track?.album?.name ?: ""
        val durationMs = track?.duration?.toLong()?.takeIf { it > 0 }?.let { it * 1000 }
        // Additional validation: ensure duration is never negative
        val safeDurationMs = durationMs?.coerceAtLeast(1L) ?: 180000L
        val elapsedMs = playerData.queue?.elapsedTime?.toLong()?.let { it * 1000 }
            ?.coerceAtLeast(0L) ?: 0L
        val playing = playerData.player.isPlaying
        val imageUrl = track?.imageInfo?.url(serverUrl)

        log.i { "Now Playing update: title=$title, artist=$artist, durationMs=$durationMs, elapsedMs=$elapsedMs, playing=$playing" }

        val info = mutableMapOf<Any?, Any?>()
        title?.let { info[MPMediaItemPropertyTitle] = it }
        if (artist.isNotEmpty()) info[MPMediaItemPropertyArtist] = artist
        if (album.isNotEmpty()) info[MPMediaItemPropertyAlbumTitle] = album

        // Use the safe duration value
        info[MPMediaItemPropertyPlaybackDuration] = safeDurationMs.toDouble() / 1000.0

        if (durationMs != null && durationMs <= 0) {
            log.w { "Invalid duration: $durationMs ms, using safe duration: $safeDurationMs ms" }
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsedMs.toDouble() / 1000.0
        info[MPNowPlayingInfoPropertyPlaybackRate] = if (playing) 1.0 else 0.0
        // Explicitly mark as local, long-form audio
        info[MPNowPlayingInfoPropertyIsLiveStream] = false
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaTypeAudio

        // Don't add non-standard keys - they can prevent controls from showing

        // Update Now Playing info - Swift handles command enabling
        dispatch_async(dispatch_get_main_queue()) {
            val infoCenter = MPNowPlayingInfoCenter.defaultCenter()
            
            // Set playback state
            if (playing) {
                infoCenter.playbackState = MPNowPlayingPlaybackStatePlaying
                log.i { "Set playback state to Playing" }
            } else {
                infoCenter.playbackState = MPNowPlayingPlaybackStatePaused
                log.i { "Set playback state to Paused" }
            }
            
            // Update the Now Playing info with all metadata
            infoCenter.nowPlayingInfo = info
            log.i { "Updated Now Playing info: title=$title, playing=$playing, duration=${safeDurationMs/1000}s" }
        }

        // Load artwork asynchronously if available; reuse cached artwork to avoid flicker
        if (!imageUrl.isNullOrBlank()) {
            if (cachedArtwork != null && cachedArtworkUrl == imageUrl) {
                info[MPMediaItemPropertyArtwork] = cachedArtwork!!
                setNowPlayingInfo(info)
            } else {
                fetchArtwork(imageUrl) { artwork ->
                    if (artwork != null) {
                        cachedArtworkUrl = imageUrl
                        cachedArtwork = artwork
                        info[MPMediaItemPropertyArtwork] = artwork
                        setNowPlayingInfo(info)
                    } else {
                        // keep prior cached artwork if fetch fails
                        cachedArtwork?.let {
                            info[MPMediaItemPropertyArtwork] = it
                            setNowPlayingInfo(info)
                        }
                    }
                }
            }
        } else {
            // If no image for this item, prefer to keep previous artwork to avoid empty flashes
            cachedArtwork?.let {
                info[MPMediaItemPropertyArtwork] = it
            }
            setNowPlayingInfo(info)
        }
    }

    private fun clearNowPlaying() {
        dispatch_async(dispatch_get_main_queue()) {
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        }
    }

    private fun setNowPlayingInfo(info: Map<Any?, *>) {
        dispatch_async(dispatch_get_main_queue()) {
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
        }
    }

    @OptIn(kotlinx.cinterop.BetaInteropApi::class)
    private fun fetchArtwork(urlString: String, onResult: (MPMediaItemArtwork?) -> Unit) {
        // Lightweight fetch using Ktor Darwin
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val client = HttpClient(Darwin)
                val bytes: ByteArray = client.get(urlString).body()
                client.close()
                val artwork = bytesToArtwork(bytes)
                onResult(artwork)
            } catch (_: Throwable) {
                onResult(null)
            }
        }
    }

    @OptIn(kotlinx.cinterop.BetaInteropApi::class)
    private fun bytesToArtwork(bytes: ByteArray): MPMediaItemArtwork? {
        return try {
            val data: NSData = bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
            val image = UIImage(data = data)
            image?.let { MPMediaItemArtwork(image = it) }
        } catch (_: Throwable) {
            null
        }
    }
}


