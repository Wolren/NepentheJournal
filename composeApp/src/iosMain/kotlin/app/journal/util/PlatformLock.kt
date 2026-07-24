package app.journal.util

import platform.Foundation.NSLock

actual class PlatformLock actual constructor() {
    private val nativeLock = NSLock()
    actual fun <T> withLock(action: () -> T): T {
        nativeLock.lock()
        try { return action() } finally { nativeLock.unlock() }
    }
}
