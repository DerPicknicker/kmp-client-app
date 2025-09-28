@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.music_assistant.client.player

import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.play
import platform.AVFoundation.pause
import platform.AVFoundation.currentTime
import platform.AVFoundation.seekToTime
import platform.AVFoundation.duration
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.Foundation.NSError
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr

actual class MediaPlayerController actual constructor(platformContext: PlatformContext) {
    private var player: AVPlayer? = null
    private var playerItem: AVPlayerItem? = null
    private var endObserver: Any? = null
    private var listener: MediaPlayerListener? = null
    private var isPlayingInternal: Boolean = false
    private var readyTimer: NSTimer? = null

    actual fun prepare(
        pathSource: String,
        listener: MediaPlayerListener
    ) {
        this.listener = listener
        runOnMain {
            releaseInternal()
            configureAudioSession()
            val url = toNSURL(pathSource)
            val item = AVPlayerItem(uRL = url)
            playerItem = item
            player = AVPlayer(playerItem = item)

            // Notify completion when item finishes
            endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = item,
                queue = null
            ) { _ ->
                isPlayingInternal = false
                this.listener?.onAudioCompleted()
            }

            // Signal ready only once the item is actually ready
            observeItemReadiness(item)
        }
    }

    actual fun start() {
        runOnMain {
            player?.play()
            isPlayingInternal = true
        }
    }

    actual fun pause() {
        runOnMain {
            player?.pause()
            isPlayingInternal = false
        }
    }

    actual fun stop() {
        runOnMain {
            player?.pause()
            player?.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
            isPlayingInternal = false
        }
    }

    actual fun getCurrentPosition(): Long? {
        val seconds = player?.currentTime()?.let { CMTimeGetSeconds(it) } ?: return null
        return if (!seconds.isNaN() && !seconds.isInfinite() && seconds >= 0) {
            (seconds * 1000.0).toLong()
        } else null
    }

    actual fun getDuration(): Long? {
        val seconds = playerItem?.duration?.let { CMTimeGetSeconds(it) } ?: return null
        return if (!seconds.isNaN() && !seconds.isInfinite() && seconds > 0) {
            (seconds * 1000.0).toLong()
        } else null
    }

    actual fun seekTo(seconds: Long) {
        runOnMain {
            player?.seekToTime(CMTimeMakeWithSeconds(seconds.toDouble(), 600))
        }
    }

    actual fun isPlaying(): Boolean = isPlayingInternal

    actual fun release() {
        runOnMain {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        readyTimer?.invalidate()
        readyTimer = null
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        player?.pause()
        player = null
        playerItem = null
        isPlayingInternal = false
        listener = null
    }

    private fun configureAudioSession() {
        try {
            val session = AVAudioSession.sharedInstance()
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                // Category and mode
                session.setCategory(AVAudioSessionCategoryPlayback, error = err.ptr)
                session.setMode(AVAudioSessionModeDefault, error = err.ptr)
                // Activate session
                session.setActive(true, error = err.ptr)
            }
        } catch (_: Throwable) {
            // Best-effort; ignore failures on simulator
        }
    }

    private fun observeItemReadiness(item: AVPlayerItem) {
        readyTimer?.invalidate()
        readyTimer = NSTimer.scheduledTimerWithTimeInterval(0.1, repeats = true) { timer ->
            when (item.status) {
                AVPlayerItemStatusReadyToPlay -> {
                    timer?.invalidate()
                    readyTimer = null
                    this.listener?.onReady()
                }
                AVPlayerItemStatusFailed -> {
                    timer?.invalidate()
                    readyTimer = null
                    val err = item.error
                    this.listener?.onError(if (err != null) Exception(err.localizedDescription ?: "AVPlayerItem failed") else null)
                }
                else -> {
                    // keep waiting
                }
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue(), block)
    }

    private fun toNSURL(pathSource: String): NSURL {
        return if (pathSource.contains("://")) {
            NSURL.URLWithString(pathSource)!!
        } else {
            NSURL.fileURLWithPath(pathSource)
        }
    }
}

actual class PlatformContext