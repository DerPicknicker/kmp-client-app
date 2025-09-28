@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.music_assistant.client.player

// AVAudioSession imports removed - API not available in Kotlin/Native yet
// The audio session is configured automatically by AVPlayer
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
import platform.Foundation.NSURL
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import co.touchlab.kermit.Logger
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPNowPlayingPlaybackStatePaused
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.MediaPlayer.MPNowPlayingSession
import platform.Foundation.NSArray

actual class MediaPlayerController actual constructor(platformContext: PlatformContext) {
    private var player: AVPlayer? = null
    private var playerItem: AVPlayerItem? = null
    private var endObserver: Any? = null
    private var nowPlayingSession: MPNowPlayingSession? = null
    private var listener: MediaPlayerListener? = null
    private val log = Logger.withTag("MediaPlayerController")

    actual fun prepare(
        pathSource: String,
        listener: MediaPlayerListener
    ) {
        this.listener = listener
        runOnMain {
            releaseInternal()
            // Audio session is configured by Swift code in ContentView
            val url = toNSURL(pathSource)
            val item = AVPlayerItem(uRL = url)
            playerItem = item
            player = AVPlayer(playerItem = item)

            // Register this AVPlayer with an MPNowPlayingSession so iOS
            // considers it the active Now Playing participant
            try {
                val avp = player
                if (avp != null) {
                    nowPlayingSession = MPNowPlayingSession(players = listOf(avp))
                    // Activation happens automatically when possible on iOS; no explicit call needed
                    log.i { "MPNowPlayingSession created" }
                }
            } catch (_: Throwable) { }

            // Notify completion when item finishes
            endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = item,
                queue = null
            ) { _ ->
                log.i { "Audio completed" }
                this.listener?.onAudioCompleted()

                // Note: AVAudioSession deactivation happens automatically when playback ends
                log.i { "Playback completed - AVAudioSession should deactivate automatically" }
            }


            // Signal ready immediately; AVPlayer will buffer as needed
            listener.onReady()
        }
    }

    actual fun start() {
        runOnMain {
            log.i { "Starting playback, rate before: ${player?.rate}" }
            
            player?.play()
            log.i { "Playback started, rate after: ${player?.rate}" }

            // Immediately reflect playing state in Now Playing for proper lock screen controls
            try {
                val center = MPNowPlayingInfoCenter.defaultCenter()
                val current = player?.currentTime()?.let { CMTimeGetSeconds(it) } ?: 0.0
                val info = (center.nowPlayingInfo as? Map<Any?, Any?>)?.toMutableMap() ?: mutableMapOf()
                info[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
                info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = current
                center.nowPlayingInfo = info
                center.playbackState = MPNowPlayingPlaybackStatePlaying
                log.i { "Updated Now Playing to Playing (elapsed=${current}s)" }
            } catch (_: Throwable) { }
        }
    }

    actual fun pause() {
        runOnMain {
            log.i { "Pausing playback, rate before: ${player?.rate}" }
            player?.pause()
            log.i { "Playback paused, rate after: ${player?.rate}" }

            // Note: AVAudioSession deactivation is handled by the OS when playback stops
            // The key is to update MPNowPlayingInfoCenter with correct playback rate
            try {
                val center = MPNowPlayingInfoCenter.defaultCenter()
                val current = player?.currentTime()?.let { CMTimeGetSeconds(it) } ?: 0.0
                val info = (center.nowPlayingInfo as? Map<Any?, Any?>)?.toMutableMap() ?: mutableMapOf()
                info[MPNowPlayingInfoPropertyPlaybackRate] = 0.0
                info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = current
                center.nowPlayingInfo = info
                center.playbackState = MPNowPlayingPlaybackStatePaused
                log.i { "Updated Now Playing to Paused (elapsed=${current}s)" }
            } catch (_: Throwable) { }
            log.i { "Playback paused - Control Center should update via MPNowPlayingInfoCenter" }
        }
    }

    actual fun stop() {
        runOnMain {
            player?.pause()
            player?.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
            
            log.i { "Playback stopped" }
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
            // Keep Now Playing elapsed time in sync
            try {
                val center = MPNowPlayingInfoCenter.defaultCenter()
                val info = (center.nowPlayingInfo as? Map<Any?, Any?>)?.toMutableMap() ?: mutableMapOf()
                info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = seconds.toDouble() / 1000.0
                center.nowPlayingInfo = info
            } catch (_: Throwable) { }
        }
    }

    actual fun isPlaying(): Boolean {
        val rate = player?.rate ?: 0.0f
        val playing = rate > 0.0f
        log.d { "isPlaying() - rate: $rate, playing: $playing" }
        return playing
    }

    actual fun release() {
        runOnMain {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        player?.pause()
        player = null
        playerItem = null
        listener = null
        // Audio session cleanup handled by Swift code
    }

    // Audio session configuration is now handled by AudioSessionManager
    // which calls the Swift AudioSessionHelper for proper AVAudioSession setup


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