@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.music_assistant.client.player

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSClassFromString
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import co.touchlab.kermit.Logger

/**
 * Kotlin wrapper for the Swift AudioSessionHelper
 * This allows us to call Swift code from Kotlin to properly configure AVAudioSession
 */
object AudioSessionManager {
    private val log = Logger.withTag("AudioSessionManager")
    
    /**
     * Get the Swift AudioSessionHelper singleton instance
     */
    private fun getAudioSessionHelper(): NSObject? {
        return try {
            val helperClass = NSClassFromString("iosApp.AudioSessionHelper")
            val sharedSelector = NSSelectorFromString("shared")
            helperClass?.performSelector(sharedSelector) as? NSObject
        } catch (e: Exception) {
            log.e { "Failed to get AudioSessionHelper: ${e.message}" }
            null
        }
    }
    
    /**
     * Configure the audio session for background playback
     */
    fun configureAudioSession() {
        dispatch_async(dispatch_get_main_queue()) {
            try {
                val helper = getAudioSessionHelper()
                if (helper != null) {
                    val selector = NSSelectorFromString("configureAudioSession")
                    helper.performSelector(selector)
                    log.i { "Audio session configured via Swift helper" }
                } else {
                    log.e { "AudioSessionHelper not available" }
                }
            } catch (e: Exception) {
                log.e { "Failed to configure audio session: ${e.message}" }
            }
        }
    }
    
    /**
     * Prepare for playback - ensures audio session is active and controls are enabled
     */
    fun prepareForPlayback() {
        dispatch_async(dispatch_get_main_queue()) {
            try {
                val helper = getAudioSessionHelper()
                if (helper != null) {
                    val selector = NSSelectorFromString("prepareForPlayback")
                    helper.performSelector(selector)
                    log.i { "Prepared for playback via Swift helper" }
                } else {
                    log.e { "AudioSessionHelper not available" }
                }
            } catch (e: Exception) {
                log.e { "Failed to prepare for playback: ${e.message}" }
            }
        }
    }
    
    /**
     * Deactivate the audio session
     */
    fun deactivateAudioSession() {
        dispatch_async(dispatch_get_main_queue()) {
            try {
                val helper = getAudioSessionHelper()
                if (helper != null) {
                    val selector = NSSelectorFromString("deactivateAudioSession")
                    helper.performSelector(selector)
                    log.i { "Audio session deactivated via Swift helper" }
                } else {
                    log.e { "AudioSessionHelper not available" }
                }
            } catch (e: Exception) {
                log.e { "Failed to deactivate audio session: ${e.message}" }
            }
        }
    }
    
    /**
     * Enable remote controls
     */
    fun enableRemoteControls() {
        dispatch_async(dispatch_get_main_queue()) {
            try {
                val helper = getAudioSessionHelper()
                if (helper != null) {
                    val selector = NSSelectorFromString("enableRemoteControls")
                    helper.performSelector(selector)
                    log.i { "Remote controls enabled via Swift helper" }
                } else {
                    log.e { "AudioSessionHelper not available" }
                }
            } catch (e: Exception) {
                log.e { "Failed to enable remote controls: ${e.message}" }
            }
        }
    }
}
