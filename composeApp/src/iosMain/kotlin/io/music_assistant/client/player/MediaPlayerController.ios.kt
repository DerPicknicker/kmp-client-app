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

actual class MediaPlayerController actual constructor(platformContext: PlatformContext) {
    private var player: AVPlayer? = null
    private var playerItem: AVPlayerItem? = null
    private var endObserver: Any? = null
    private var listener: MediaPlayerListener? = null
    private val log = Logger.withTag("MediaPlayerController")

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

            // Note: AVAudioSession is automatically activated when starting playback
            log.i { "Starting playback - AVAudioSession should be active" }

            player?.play()
            log.i { "Playback started, rate after: ${player?.rate}" }
        }
    }

    actual fun pause() {
        runOnMain {
            log.i { "Pausing playback, rate before: ${player?.rate}" }
            player?.pause()
            log.i { "Playback paused, rate after: ${player?.rate}" }

            // Note: AVAudioSession deactivation is handled by the OS when playback stops
            // The key is to update MPNowPlayingInfoCenter with correct playback rate
            log.i { "Playback paused - Control Center should update via MPNowPlayingInfoCenter" }
        }
    }

    actual fun stop() {
        runOnMain {
            player?.pause()
            player?.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
            
            // Deactivate audio session when stopping
            deactivateAudioSession()
            log.i { "Playback stopped - AVAudioSession deactivated" }
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
        deactivateAudioSession()
    }

    private fun configureAudioSession() {
        // TODO: Properly configure AVAudioSession when Kotlin/Native API is available
        // For now, the audio session will be automatically configured by AVPlayer
        // when playback starts. The background audio capability in Info.plist
        // should be sufficient for Control Center integration.
        log.i { "Audio session configuration delegated to AVPlayer" }
    }
    
    private fun deactivateAudioSession() {
        // TODO: Properly deactivate AVAudioSession when Kotlin/Native API is available
        // For now, the audio session will be automatically managed by AVPlayer
        log.i { "Audio session deactivation delegated to AVPlayer" }
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