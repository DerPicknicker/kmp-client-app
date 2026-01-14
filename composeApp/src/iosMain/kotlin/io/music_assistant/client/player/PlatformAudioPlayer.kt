package io.music_assistant.client.player

/**
 * Interface for platform-specific audio player implementation.
 * This allows Swift (or other iOS logic) to provide the actual player.
 */
interface PlatformAudioPlayer {
    fun prepareStream(
        codec: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        listener: MediaPlayerListener
    )
    fun writeRawPcm(data: ByteArray)
    fun stopRawPcmStream()
    fun setVolume(volume: Int)
    fun setMuted(muted: Boolean)
    fun dispose()
}

/**
 * Singleton provider to bridge Kotlin and Swift.
 * Swift should assign its implementation to `player` at startup.
 */
object PlatformPlayerProvider {
    var player: PlatformAudioPlayer? = null
}
