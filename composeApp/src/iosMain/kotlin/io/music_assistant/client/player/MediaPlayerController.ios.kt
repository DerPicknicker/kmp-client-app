@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.music_assistant.client.player

import io.music_assistant.client.player.sendspin.model.AudioCodec

/**
 * MediaPlayerController - iOS stub for Sendspin
 *
 * Handles raw PCM audio streaming for Sendspin protocol.
 * TODO: Implement using AVAudioEngine or AudioQueue
 */
actual class MediaPlayerController actual constructor(platformContext: PlatformContext) {
    private var isPrepared: Boolean = false

    // Sendspin streaming methods
    actual fun prepareStream(
        codec: AudioCodec,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?,
        listener: MediaPlayerListener
    ) {
        val player = PlatformPlayerProvider.player
        if (player != null) {
            player.prepareStream(codec.name.lowercase(), sampleRate, channels, bitDepth, codecHeader, listener)
            isPrepared = true
        } else {
            println("MediaPlayerController: No PlatformAudioPlayer registered!")
            listener.onError(Exception("Audio Player implementation missing"))
        }
    }

    actual fun writeRawPcm(data: ByteArray): Int {
        val player = PlatformPlayerProvider.player
        if (player != null) {
            player.writeRawPcm(data)
            return data.size
        }
        return 0
    }

    actual fun stopRawPcmStream() {
        PlatformPlayerProvider.player?.stopRawPcmStream()
        isPrepared = false
    }

    actual fun setVolume(volume: Int) {
        PlatformPlayerProvider.player?.setVolume(volume)
    }

    actual fun setMuted(muted: Boolean) {
        PlatformPlayerProvider.player?.setMuted(muted)
    }

    actual fun release() {
        PlatformPlayerProvider.player?.dispose()
        isPrepared = false
    }

    actual fun getCurrentSystemVolume(): Int {
        // TODO: Add getVolume to interface if needed, for now return dummy
        return 100
    }
}

actual class PlatformContext
