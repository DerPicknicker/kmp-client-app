@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.music_assistant.client.player

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.ptr
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.kCMTimeZero
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatus
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual class MediaPlayerController actual constructor(platformContext: PlatformContext) {
    private var player: AVPlayer? = null
    private var playerItem: AVPlayerItem? = null
    private var callback: MediaPlayerListener? = null

    private fun runOnMain(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue(), block)
    }

    private fun configureAudioSession() {
        try {
            AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
            AVAudioSession.sharedInstance().setActive(true, null)
        } catch (e: Exception) {
            // Log error
            println("Failed to set up audio session: ${e.message}")
        }
    }

    private fun observeRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.playCommand.addTargetWithHandler {
            start()
            MPRemoteCommandHandlerStatus.MPRemoteCommandHandlerStatusSuccess
        }
        center.pauseCommand.addTargetWithHandler {
            pause()
            MPRemoteCommandHandlerStatus.MPRemoteCommandHandlerStatusSuccess
        }
        center.togglePlayPauseCommand.addTargetWithHandler {
            if (isPlaying()) pause() else start()
            MPRemoteCommandHandlerStatus.MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun updateNowPlaying(elapsedSeconds: Double? = null, durationSeconds: Double? = null, rate: Double? = null) {
        val infoCenter = MPNowPlayingInfoCenter.defaultCenter()
        val nowPlayingInfo = mutableMapOf<Any?, Any>()
        nowPlayingInfo[MPMediaItemPropertyTitle] = "Music Assistant" // Placeholder
        elapsedSeconds?.let { nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = it }
        durationSeconds?.let { nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = it }
        rate?.let { nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = it }

        infoCenter.nowPlayingInfo = infoCenter.nowPlayingInfo?.let {
            it.toMutableMap().apply { putAll(nowPlayingInfo) }
        } ?: nowPlayingInfo
    }

    actual fun prepare(
        pathSource: String,
        listener: MediaPlayerListener
    ) {
        callback = listener
        runOnMain {
            try {
                configureAudioSession()
                val url = NSURL.URLWithString(pathSource)
                val item = AVPlayerItem(url = url!!)
                playerItem = item
                player = AVPlayer(playerItem = item)

                // Observe end of playback
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = AVPlayerItemDidPlayToEndTimeNotification,
                    `object` = item,
                    queue = NSOperationQueue.mainQueue,
                    usingBlock = { _: NSNotification? ->
                        callback?.onAudioCompleted()
                        updateNowPlaying(rate = 0.0)
                    }
                )

                // Early now playing setup
                updateNowPlaying(elapsedSeconds = 0.0, durationSeconds = durationSeconds(), rate = 0.0)

                // Signal ready immediately; AVPlayer will buffer as needed
                callback?.onReady()
            } catch (t: Throwable) {
                callback?.onError(t)
            }
        }
    }

    actual fun start() {
        runOnMain {
            player?.play()
            updateNowPlaying(elapsedSeconds = positionSeconds(), durationSeconds = durationSeconds(), rate = 1.0)
        }
    }

    actual fun pause() {
        runOnMain {
            player?.pause()
            updateNowPlaying(elapsedSeconds = positionSeconds(), durationSeconds = durationSeconds(), rate = 0.0)
        }
    }

    actual fun stop() {
        runOnMain {
            player?.pause()
            player?.seekToTime(kCMTimeZero)
            updateNowPlaying(elapsedSeconds = 0.0, durationSeconds = durationSeconds(), rate = 0.0)
        }
    }

    private fun positionSeconds(): Double? = player?.currentTime()?.let { CMTimeGetSeconds(it) }
    private fun durationSeconds(): Double? = playerItem?.duration?.let { CMTimeGetSeconds(it) }?.takeIf { it.isFinite() && it > 0 }

    actual fun getCurrentPosition(): Long? = positionSeconds()?.let { (it * 1000).toLong() }
    actual fun getDuration(): Long? = durationSeconds()?.let { (it * 1000).toLong() }

    actual fun seekTo(seconds: Long) {
        runOnMain {
            val time = CMTimeMakeWithSeconds(seconds.toDouble(), preferredTimescale = 600)
            player?.seekToTime(time)
            updateNowPlaying(
                elapsedSeconds = seconds.toDouble(),
                durationSeconds = durationSeconds(),
                rate = if (isPlaying()) 1.0 else 0.0
            )
        }
    }

    actual fun isPlaying(): Boolean = player?.rate != 0.0 && player?.error == null

    actual fun release() {
        runOnMain {
            player?.pause()
            player = null
            playerItem = null
            updateNowPlaying(rate = 0.0)
            callback = null
        }
    }

    init {
        // Enable remote commands once per controller instance
        observeRemoteCommands()
    }
}

actual class PlatformContext