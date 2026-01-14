@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.music_assistant.client.player

import io.music_assistant.client.player.sendspin.model.AudioCodec

/**
 * MediaPlayerController - Sendspin audio player
 *
 * Handles raw PCM audio streaming for Sendspin protocol.
 * Built-in player (ExoPlayer) has been removed - Sendspin is now the only playback method.
 */
expect class MediaPlayerController(platformContext: PlatformContext) {

    // Sendspin streaming
    fun prepareStream(
        codec: AudioCodec,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?,
        listener: MediaPlayerListener
    )

    fun writeRawPcm(data: ByteArray): Int

    fun stopRawPcmStream()

    // Volume control (0-100)
    fun setVolume(volume: Int)

    // Mute control
    fun setMuted(muted: Boolean)

    // Get current system volume (0-100)
    fun getCurrentSystemVolume(): Int

    fun release()
}

expect class PlatformContext