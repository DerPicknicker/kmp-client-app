@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.music_assistant.client.nowplaying

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSString
import platform.Foundation.create

/**
 * Kotlin-Swift bridge using NSNotifications
 */
object NowPlayingBridgeInterop {
    
    private const val UPDATE_NOTIFICATION = "UpdateNowPlayingNotification"
    
    fun updateNowPlaying(
        title: String,
        artist: String,
        album: String,
        durationSeconds: Double,
        elapsedSeconds: Double,
        isPlaying: Boolean,
        artworkUrl: String?
    ) {
        // Post a notification that Swift will observe
        val userInfo = mapOf<Any?, Any?>(
            "title" to title,
            "artist" to artist,
            "album" to album,
            "duration" to durationSeconds,
            "elapsed" to elapsedSeconds,
            "isPlaying" to isPlaying,
            "artworkUrl" to (artworkUrl ?: "")
        )
        
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = UPDATE_NOTIFICATION,
            `object` = null,
            userInfo = userInfo
        )
    }
}
