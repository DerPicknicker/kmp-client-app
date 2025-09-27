package io.music_assistant.client.nowplaying

import io.music_assistant.client.data.MainDataSource
import io.music_assistant.client.data.model.client.PlayerData
import io.music_assistant.client.ui.compose.main.PlayerAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
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
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatus
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage
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

    init {
        // Start observing player state and configure command center
        configureRemoteCommandCenter()
        observePlayers()
    }

    private fun observePlayers() {
        launch {
            dataSource.playersData.collectLatest { list ->
                // Choose a player to reflect in Now Playing: prefer playing, else first with an item
                val withItems = list.filter { it.queue?.currentItem != null }
                val active = withItems.firstOrNull { it.player.isPlaying } ?: withItems.firstOrNull()
                currentPlayer = active
                if (active == null) {
                    clearNowPlaying()
                } else {
                    updateNowPlaying(active, multiplePlayers = withItems.size > 1)
                }
            }
        }
    }

    private fun configureRemoteCommandCenter() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()

        fun MPRemoteCommand.setHandler(handler: (MPRemoteCommandEvent?) -> MPRemoteCommandHandlerStatus) {
            addTargetWithHandler { event -> handler(event) }
        }

        center.togglePlayPauseCommand.enabled = true
        center.togglePlayPauseCommand.setHandler {
            currentPlayer?.let { dataSource.playerAction(it, PlayerAction.TogglePlayPause) }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.playCommand.enabled = true
        center.playCommand.setHandler {
            currentPlayer?.let { dataSource.playerAction(it, PlayerAction.TogglePlayPause) }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.pauseCommand.enabled = true
        center.pauseCommand.setHandler {
            currentPlayer?.let { dataSource.playerAction(it, PlayerAction.TogglePlayPause) }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.nextTrackCommand.enabled = true
        center.nextTrackCommand.setHandler {
            currentPlayer?.let { dataSource.playerAction(it, PlayerAction.Next) }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.previousTrackCommand.enabled = true
        center.previousTrackCommand.setHandler {
            currentPlayer?.let { dataSource.playerAction(it, PlayerAction.Previous) }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.changePlaybackPositionCommand?.let { command ->
            command.enabled = true
            command.addTargetWithHandler { event ->
                val evt = event as? MPChangePlaybackPositionCommandEvent
                val seconds = evt?.positionTime ?: return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed
                val ms = (seconds * 1000.0).toLong()
                currentPlayer?.let { dataSource.playerAction(it, PlayerAction.SeekTo(ms)) }
                MPRemoteCommandHandlerStatusSuccess
            }
        }
    }

    private fun updateNowPlaying(playerData: PlayerData, multiplePlayers: Boolean) {
        val serverUrl = dataSource.apiClient.serverInfo.value?.baseUrl
        val track = playerData.queue?.currentItem?.track
        val title = track?.name
        val artist = track?.subtitle
        val album = track?.album?.name
        val durationMs = track?.duration?.toLong()?.let { it * 1000 }
        val elapsedMs = playerData.queue?.elapsedTime?.toLong()?.let { it * 1000 }
        val playing = playerData.player.isPlaying
        val imageUrl = track?.imageInfo?.url(serverUrl)

        val info = mutableMapOf<Any?, Any?>()
        title?.let { info[MPMediaItemPropertyTitle] = it }
        artist?.let { info[MPMediaItemPropertyArtist] = it }
        album?.let { info[MPMediaItemPropertyAlbumTitle] = it }
        durationMs?.let { info[MPMediaItemPropertyPlaybackDuration] = it.toDouble() / 1000.0 }
        elapsedMs?.let { info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = it.toDouble() / 1000.0 }
        info[MPNowPlayingInfoPropertyPlaybackRate] = if (playing) 1.0 else 0.0

        // Apply base metadata immediately
        setNowPlayingInfo(info)

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
        val url = NSURL.URLWithString(urlString) ?: run { onResult(null); return }
        // Fetch synchronously off the main thread to avoid blocking UI
        launch(Dispatchers.Default) {
            val data = NSData.dataWithContentsOfURL(url)
            val image = data?.let { UIImage(data = it) }
            val artwork = image?.let { img -> MPMediaItemArtwork(image = img) }
            onResult(artwork)
        }
    }
}


