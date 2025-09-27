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
import platform.MediaPlayer.MPNowPlayingPlaybackStatePaused
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatus
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.refTo
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.core.toByteArray
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
    private val log = Logger.withTag("NowPlaying")

    init {
        // Start observing player state and configure command center
        configureRemoteCommandCenter()
        observePlayers()
    }

    private fun observePlayers() {
        launch {
            dataSource.playersData.collectLatest { list ->
                // Prefer local builtin player; otherwise prefer any playing player; otherwise first
                val builtin = list.firstOrNull { it.player.isBuiltin }
                val active = builtin ?: list.firstOrNull { it.player.isPlaying } ?: list.firstOrNull()
                currentPlayer = active
                active?.let { updateNowPlaying(it, multiplePlayers = list.size > 1) }
            }
        }
    }

    private fun configureRemoteCommandCenter() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        log.i { "Configuring remote command center" }

        fun MPRemoteCommand.setHandler(handler: (MPRemoteCommandEvent?) -> MPRemoteCommandHandlerStatus) {
            addTargetWithHandler { event -> handler(event) }
        }

        center.togglePlayPauseCommand.enabled = true
        center.togglePlayPauseCommand.setHandler {
            log.i { "Toggle play/pause" }
            currentPlayer?.let { dataSource.playerAction(it, PlayerAction.TogglePlayPause) }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.playCommand.enabled = true
        center.playCommand.setHandler {
            log.i { "Play" }
            currentPlayer?.let { dataSource.playerAction(it, PlayerAction.TogglePlayPause) }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.pauseCommand.enabled = true
        center.pauseCommand.setHandler {
            log.i { "Pause" }
            currentPlayer?.let { dataSource.playerAction(it, PlayerAction.TogglePlayPause) }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.nextTrackCommand.enabled = true
        center.nextTrackCommand.setHandler {
            log.i { "Next" }
            currentPlayer?.let { dataSource.playerAction(it, PlayerAction.Next) }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.previousTrackCommand.enabled = true
        center.previousTrackCommand.setHandler {
            log.i { "Previous" }
            currentPlayer?.let { dataSource.playerAction(it, PlayerAction.Previous) }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.changePlaybackPositionCommand?.let { command ->
            command.enabled = true
            command.addTargetWithHandler { event ->
                val evt = event as? MPChangePlaybackPositionCommandEvent
                val seconds = evt?.positionTime ?: return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed
                val sec = seconds.toLong()
                log.i { "Seek to $sec s" }
                currentPlayer?.let { dataSource.playerAction(it, PlayerAction.SeekTo(sec)) }
                MPRemoteCommandHandlerStatusSuccess
            }
        }
    }

    private fun updateNowPlaying(playerData: PlayerData, multiplePlayers: Boolean) {
        val serverUrl = dataSource.apiClient.serverInfo.value?.baseUrl
        val track = playerData.queue?.currentItem?.track
        val title = track?.name ?: playerData.player.name
        val artist = track?.subtitle ?: ""
        val album = track?.album?.name ?: ""
        val durationMs = track?.duration?.toLong()?.let { it * 1000 }
        val elapsedMs = playerData.queue?.elapsedTime?.toLong()?.let { it * 1000 } ?: 0L
        val playing = playerData.player.isPlaying
        val imageUrl = track?.imageInfo?.url(serverUrl)

        val info = mutableMapOf<Any?, Any?>()
        title?.let { info[MPMediaItemPropertyTitle] = it }
        if (artist.isNotEmpty()) info[MPMediaItemPropertyArtist] = artist
        if (album.isNotEmpty()) info[MPMediaItemPropertyAlbumTitle] = album
        durationMs?.let { info[MPMediaItemPropertyPlaybackDuration] = it.toDouble() / 1000.0 }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsedMs.toDouble() / 1000.0
        info[MPNowPlayingInfoPropertyPlaybackRate] = if (playing) 1.0 else 0.0
        info[MPNowPlayingInfoPropertyIsLiveStream] = false

        // Apply base metadata immediately
        log.i { "Update Now Playing: title=$title, artist=$artist, album=$album, playing=$playing" }
        setNowPlayingInfo(info)
        // Explicitly set playback state to help presentation
        MPNowPlayingInfoCenter.defaultCenter().playbackState = if (playing) MPNowPlayingPlaybackStatePlaying else MPNowPlayingPlaybackStatePaused

        // Load artwork asynchronously if available
        if (!imageUrl.isNullOrBlank()) {
            fetchArtwork(imageUrl) { artwork ->
                if (artwork != null) {
                    info[MPMediaItemPropertyArtwork] = artwork
                    setNowPlayingInfo(info)
                }
            }
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

    private fun fetchArtwork(urlString: String, onResult: (MPMediaItemArtwork?) -> Unit) {
        // Lightweight fetch using Ktor Darwin
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            try {
                val client = HttpClient(Darwin)
                val channel = client.get(urlString).bodyAsChannel()
                val bytes = channel.readRemaining().readBytes()
                client.close()
                val artwork = bytesToArtwork(bytes)
                onResult(artwork)
            } catch (_: Throwable) {
                onResult(null)
            }
        }
    }

    private fun bytesToArtwork(bytes: ByteArray): MPMediaItemArtwork? {
        return try {
            val data: NSData = memScoped { NSData.create(bytes = bytes.refTo(0), length = bytes.size.toULong()) }
            val image = UIImage(data = data)
            image?.let { MPMediaItemArtwork(image = it) }
        } catch (_: Throwable) {
            null
        }
    }
}


