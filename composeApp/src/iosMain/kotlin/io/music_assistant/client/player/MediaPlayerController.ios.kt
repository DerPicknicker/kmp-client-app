@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.music_assistant.client.player

import co.touchlab.kermit.Logger

// TODO: Implement iOS MediaPlayerController with AVFoundation
// For now, create a stub implementation to resolve compilation issues

actual class MediaPlayerController actual constructor(platformContext: PlatformContext) {
    private var listener: MediaPlayerListener? = null
    private val log = Logger.withTag("MediaPlayerController")

    actual fun prepare(
        pathSource: String,
        listener: MediaPlayerListener
    ) {
        this.listener = listener
        log.i { "iOS MediaPlayerController prepare() - stub implementation" }
        // TODO: Implement AVPlayer setup
        listener.onReady()
    }

    actual fun start() {
        log.i { "iOS MediaPlayerController start() - stub implementation" }
        // TODO: Implement AVPlayer.play()
    }

    actual fun pause() {
        log.i { "iOS MediaPlayerController pause() - stub implementation" }
        // TODO: Implement AVPlayer.pause()
    }

    actual fun stop() {
        log.i { "iOS MediaPlayerController stop() - stub implementation" }
        // TODO: Implement AVPlayer stop
    }

    actual fun getCurrentPosition(): Long? {
        log.i { "iOS MediaPlayerController getCurrentPosition() - stub implementation" }
        // TODO: Implement position tracking
        return 0L
    }

    actual fun getDuration(): Long? {
        log.i { "iOS MediaPlayerController getDuration() - stub implementation" }
        // TODO: Implement duration tracking
        return 0L
    }

    actual fun seekTo(seconds: Long) {
        log.i { "iOS MediaPlayerController seekTo() - stub implementation" }
        // TODO: Implement seeking
    }

    actual fun isPlaying(): Boolean {
        log.i { "iOS MediaPlayerController isPlaying() - stub implementation" }
        // TODO: Implement playing state tracking
        return false
    }

    actual fun release() {
        log.i { "iOS MediaPlayerController release() - stub implementation" }
        listener = null
        // TODO: Implement cleanup
    }
}

actual class PlatformContext