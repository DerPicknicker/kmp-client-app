package io.music_assistant.client.player

import kotlinx.cinterop.COpaquePointer
import platform.Foundation.NSObject
import platform.Foundation.addObserver
import platform.Foundation.removeObserver

// A simple KVO Subscription holder
class KVObservation(
    val target: NSObject,
    val keyPath: String,
    val observer: NSObject
) {
    fun invalidate() {
        target.removeObserver(observer, forKeyPath = keyPath)
    }
}

// Wrapper for a KVO observer to avoid Obj-C ceremony
class Observer(val onEvent: (String) -> Unit) : NSObject() {
    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<*, *>?,
        context: COpaquePointer?
    ) {
        keyPath?.let(onEvent)
    }
}

// Extension to simplify adding an observer
fun NSObject.observe(
    keyPath: String,
    using: (String) -> Unit
): KVObservation {
    val observer = Observer(using)
    addObserver(observer, forKeyPath = keyPath, options = 0U, context = null)
    return KVObservation(this, keyPath, observer)
}
