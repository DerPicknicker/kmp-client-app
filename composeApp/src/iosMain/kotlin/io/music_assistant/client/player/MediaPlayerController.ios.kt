@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.music_assistant.client.player

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.AVFoundation.AVAudioSession
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.CMTimeMakeWithSeconds
import platform.AVFoundation.kCMTimeZero
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
import platform.Foundation.NSMutableDictionary
import platform.Foundation.setValue

actual class MediaPlayerController actual constructor(platformContext: PlatformContext) {
    private var player: AVPlayer? = null
    private var playerItem: AVPlayerItem? = null
    private var callback: MediaPlayerListener? = null

    private fun runOnMain(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue(), block)
    }

    private fun configureAudioSession() {
        memScoped {
            val session = AVAudioSession.sharedInstance()
            val error: CPointer<ObjCObjectVar<platform.Foundation.NSError?>> = alloc<ObjCObjectVar<platform.Foundation.NSError?>>().ptr
            // Use legacy API for broad iOS compatibility
            session.setCategory(AVAudioSessionCategoryPlayback, error)
            session.setActive(true, error)
        }
    }

    private fun observeRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.playCommand.addTargetWithHandler {
            start()
            MPRemoteCommandHandlerStatusSuccess
        }
        center.pauseCommand.addTargetWithHandler {
            pause()
            MPRemoteCommandHandlerStatusSuccess
        }
        center.togglePlayPauseCommand.addTargetWithHandler {
            if (isPlaying()) pause() else start()
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun updateNowPlaying(elapsedSeconds: Double? = null, durationSeconds: Double? = null, rate: Double? = null) {
        val infoCenter = MPNowPlayingInfoCenter.defaultCenter()
        val map = (infoCenter.nowPlayingInfo?.toMutableMap() ?: mutableMapOf()).toMutableMap()
        val dict = NSMutableDictionary()
        // title placeholder; real metadata can be wired later from queue
        dict.setValue("Music Assistant", forKey = MPMediaItemPropertyTitle)
        elapsedSeconds?.let { dict.setValue(it, forKey = MPNowPlayingInfoPropertyElapsedPlaybackTime) }
        durationSeconds?.let { dict.setValue(it, forKey = MPMediaItemPropertyPlaybackDuration) }
        rate?.let { dict.setValue(it, forKey = MPNowPlayingInfoPropertyPlaybackRate) }
        // Merge existing fields to keep artwork if set elsewhere
        map.forEach { (k, v) -> dict.setValue(v, forKey = k as String) }
        infoCenter.nowPlayingInfo = dict
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
            updateNowPlaying(elapsedSeconds = seconds.toDouble(), durationSeconds = durationSeconds(), rate = if (isPlaying()) 1.0 else 0.0)
        }
    }

    actual fun isPlaying(): Boolean = (player?.rate ?: 0f) > 0f

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