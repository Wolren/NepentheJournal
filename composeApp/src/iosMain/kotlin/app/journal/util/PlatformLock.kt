package app.journal.util

import platform.Foundation.NSRecursiveLock

actual class PlatformLock actual constructor() {
    private val nativeLock = NSRecursiveLock()
    actual fun <T> withLock(action: () -> T): T {
        nativeLock.lock()
        try { return action() } finally { nativeLock.unlock() }
    }
}
