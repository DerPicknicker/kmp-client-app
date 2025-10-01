package io.music_assistant.client.nowplaying

import co.touchlab.kermit.Logger
import io.music_assistant.client.data.MainDataSource
import io.music_assistant.client.ui.compose.main.PlayerAction

/**
 * Singleton handler for remote commands from iOS Control Center.
 * Swift code calls these methods when remote commands are received.
 */
object RemoteCommandHandler {
    private var dataSource: MainDataSource? = null
    private var nowPlayingManager: NowPlayingManager? = null
    private val log = Logger.withTag("RemoteCommandHandler")
    
    fun initialize(dataSource: MainDataSource, nowPlayingManager: NowPlayingManager) {
        this.dataSource = dataSource
        this.nowPlayingManager = nowPlayingManager
        log.i { "RemoteCommandHandler initialized" }
    }
    
    fun handlePlayPause() {
        log.i { "handlePlayPause called from Swift" }
        val player = nowPlayingManager?.getCurrentPlayer()
        if (player != null) {
            dataSource?.playerAction(player, PlayerAction.TogglePlayPause)
        } else {
            log.w { "No current player for play/pause" }
        }
    }
    
    fun handlePlay() {
        log.i { "handlePlay called from Swift" }
        val player = nowPlayingManager?.getCurrentPlayer()
        if (player != null) {
            dataSource?.playerAction(player, PlayerAction.TogglePlayPause)
        } else {
            log.w { "No current player for play" }
        }
    }
    
    fun handlePause() {
        log.i { "handlePause called from Swift" }
        val player = nowPlayingManager?.getCurrentPlayer()
        if (player != null) {
            dataSource?.playerAction(player, PlayerAction.TogglePlayPause)
        } else {
            log.w { "No current player for pause" }
        }
    }
    
    fun handleNext() {
        log.i { "handleNext called from Swift" }
        val player = nowPlayingManager?.getCurrentPlayer()
        if (player != null) {
            dataSource?.playerAction(player, PlayerAction.Next)
        } else {
            log.w { "No current player for next" }
        }
    }
    
    fun handlePrevious() {
        log.i { "handlePrevious called from Swift" }
        val player = nowPlayingManager?.getCurrentPlayer()
        if (player != null) {
            dataSource?.playerAction(player, PlayerAction.Previous)
        } else {
            log.w { "No current player for previous" }
        }
    }
    
    fun handleSeek(positionSeconds: Double) {
        log.i { "handleSeek called from Swift: $positionSeconds" }
        val player = nowPlayingManager?.getCurrentPlayer()
        if (player != null) {
            dataSource?.playerAction(player, PlayerAction.SeekTo(positionSeconds.toLong()))
        } else {
            log.w { "No current player for seek" }
        }
    }
}

