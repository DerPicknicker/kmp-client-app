@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.music_assistant.client.nowplaying

import co.touchlab.kermit.Logger
import io.music_assistant.client.data.MainDataSource
import io.music_assistant.client.data.model.client.PlayerData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * iOS Now Playing and Remote Command Center integration.
 * Delegates all iOS work to Swift NowPlayingBridge.
 */
class NowPlayingManager(
    private val dataSource: MainDataSource,
) : CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext = Dispatchers.Main + job

    private var currentPlayer: PlayerData? = null
    private val log = Logger.withTag("NowPlaying")
    
    // Cache to prevent unnecessary updates
    private var lastUpdateKey: String = ""

    fun getCurrentPlayer(): PlayerData? = currentPlayer

    init {
        RemoteCommandHandler.initialize(dataSource, this)
        log.i { "NowPlayingManager initialized" }
        observePlayers()
    }

    private fun observePlayers() {
        launch {
            // Debounce and only update when something meaningful changes
            dataSource.playersData.debounce(500).collectLatest { list ->
                val builtin = list.firstOrNull { it.player.isBuiltin }
                val active = builtin ?: list.firstOrNull { it.player.isPlaying } ?: list.firstOrNull()
                currentPlayer = active
                active?.let { updateNowPlaying(it) }
            }
        }
    }

    private fun updateNowPlaying(playerData: PlayerData) {
        val serverUrl = dataSource.apiClient.serverInfo.value?.baseUrl
        val track = playerData.queue?.currentItem?.track
        val title = track?.name ?: playerData.player.name
        val artist = track?.subtitle ?: ""
        val album = track?.album?.name ?: ""
        val durationSec = track?.duration?.toDouble() ?: 180.0
        val elapsedSec = (playerData.queue?.elapsedTime?.toDouble() ?: 0.0).coerceAtLeast(0.0)
        
        // For builtin player, use actual playback state
        val isPlaying = if (playerData.player.id == dataSource.getBuiltinPlayerId()) {
            dataSource.isBuiltinPlayerActuallyPlaying()
        } else {
            playerData.player.isPlaying
        }
        
        val imageUrl = track?.imageInfo?.url(serverUrl) ?: ""
        
        // Create a key from meaningful properties (not elapsed time)
        val updateKey = "$title|$artist|$album|$isPlaying|$imageUrl"
        
        // Only update if something meaningful changed
        if (updateKey == lastUpdateKey) {
            return
        }
        lastUpdateKey = updateKey

        log.i { "$title - ${if (isPlaying) "Playing" else "Paused"}" }

        // Call Swift bridge via interop
        NowPlayingBridgeInterop.updateNowPlaying(
            title = title,
            artist = artist,
            album = album,
            durationSeconds = durationSec,
            elapsedSeconds = elapsedSec,
            isPlaying = isPlaying,
            artworkUrl = imageUrl
        )
    }
}
