@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.music_assistant.client.player

// Simple KVO implementation for iOS without NSObject inheritance issues
// This is a workaround for the NSObject import issues

class KVObservation(
    val target: Any,
    val keyPath: String,
    val observer: Any
) {
    fun invalidate() {
        // Simplified stub - in a real implementation this would remove the observer
    }
}

class SimpleObserver(val onEvent: (String) -> Unit) {
    // Simplified stub - in a real implementation this would handle KVO callbacks
}

fun Any.observe(
    keyPath: String,
    using: (String) -> Unit
): KVObservation {
    val observer = SimpleObserver(using)
    return KVObservation(this, keyPath, observer)
}
