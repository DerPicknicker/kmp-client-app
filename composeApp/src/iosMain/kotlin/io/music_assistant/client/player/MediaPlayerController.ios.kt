@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.music_assistant.client.player

actual class MediaPlayerController actual constructor(platformContext: PlatformContext) {
    private var callback: MediaPlayerListener? = null
    private var isPlayingInternal: Boolean = false
    private var currentPositionMs: Long? = null
    private var durationMs: Long? = null

    actual fun prepare(
        pathSource: String,
        listener: MediaPlayerListener
    ) {
        callback = listener
        // No-op backend on iOS for now; signal ready immediately
        callback?.onReady()
    }

    actual fun start() {
        isPlayingInternal = true
    }

    actual fun pause() {
        isPlayingInternal = false
    }

    actual fun stop() {
        isPlayingInternal = false
        currentPositionMs = 0L
    }

    actual fun getCurrentPosition(): Long? = currentPositionMs

    actual fun getDuration(): Long? = durationMs

    actual fun seekTo(seconds: Long) {
        currentPositionMs = seconds * 1000L
    }

    actual fun isPlaying(): Boolean = isPlayingInternal

    actual fun release() {
        isPlayingInternal = false
        callback = null
        currentPositionMs = null
        durationMs = null
    }
}

actual class PlatformContext