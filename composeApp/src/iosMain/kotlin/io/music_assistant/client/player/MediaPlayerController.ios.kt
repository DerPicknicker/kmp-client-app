@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.music_assistant.client.player

import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.play
import platform.AVFoundation.pause
import platform.AVFoundation.rate
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.reasonForWaitingToPlay
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
import co.touchlab.kermit.Logger

actual class MediaPlayerController actual constructor(platformContext: PlatformContext) {
    private var player: AVPlayer? = null
    private var playerItem: AVPlayerItem? = null
    private var endObserver: Any? = null
    private var stallObserver: Any? = null
    private var listener: MediaPlayerListener? = null
    private val log = Logger.withTag("MediaPlayerController")
    private var observations = mutableListOf<KVObservation>()

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
            player = AVPlayer(playerItem = item).apply {
                // Allow network playback even on cellular
                allowsExternalPlayback = true
            }

            // Notify completion when item finishes
            endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = item,
                queue = null
            ) { _ ->
                this.listener?.onAudioCompleted()
            }

            // Try to recover from stalls by nudging playback
            stallObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemPlaybackStalledNotification,
                `object` = item,
                queue = null
            ) { _ ->
                log.w { "Playback stalled; trying to resume" }
                player?.play()
            }

            // KVO observations
            observations.add(item.observe("status") { handlePlayerStateChange() })
            observations.add(player!!.observe("timeControlStatus") { handlePlayerStateChange() })
            observations.add(player!!.observe("rate") { handlePlayerStateChange() })
        }
    }

    actual fun start() {
        runOnMain {
            player?.play()
        }
    }

    actual fun pause() {
        runOnMain {
            player?.pause()
        }
    }

    actual fun stop() {
        runOnMain {
            player?.pause()
            player?.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
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
            player?.seekToTime(CMTimeMakeWithSeconds(seconds.toDouble() / 1000.0, 600))
        }
    }

    actual fun isPlaying(): Boolean = player?.rate ?: 0.0f > 0.0f

    actual fun release() {
        runOnMain {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        observations.forEach { it.invalidate() }
        observations.clear()
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        stallObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        stallObserver = null
        player?.pause()
        player = null
        playerItem = null
        listener = null
    }

    private fun configureAudioSession() {
        try {
            val session = AVAudioSession.sharedInstance()
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                session.setCategory(AVAudioSessionCategoryPlayback, error = err.ptr)
                session.setMode(AVAudioSessionModeDefault, error = err.ptr)
                session.setActive(true, null)
                err.value?.let { log.e(it.localizedDescription) }
            }
        } catch (e: Throwable) {
            log.e("Failed to configure audio session", e)
        }
    }

    private fun handlePlayerStateChange() {
        val itemStatus = playerItem?.status ?: return
        val playerStatus = player?.timeControlStatus ?: return

        when (itemStatus) {
            AVPlayerItemStatusReadyToPlay -> {
                log.i { "Player ready to play" }
                listener?.onReady()

                // Log detailed status when ready
                when (playerStatus) {
                    AVPlayerTimeControlStatusPlaying -> log.i { "Status: Playing" }
                    AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> {
                        val reason = player?.reasonForWaitingToPlay
                        log.i { "Status: Waiting to play ($reason)" }
                    }
                    else -> log.i { "Status: Paused/Other" }
                }
            }
            AVPlayerItemStatusFailed -> {
                val error = playerItem?.error
                log.e { "AVPlayerItem failed: ${error?.localizedDescription}" }
                listener?.onError(error?.let { e -> Exception(e.localizedDescription) })
            }
            else -> {
                // Waiting for status
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