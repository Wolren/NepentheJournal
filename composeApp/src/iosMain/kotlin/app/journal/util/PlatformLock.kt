package app.journal.util

import kotlin.native.concurrent.Mutex

actual class PlatformLock actual constructor() {
    private val nativeLock = Mutex()
    actual fun <T> withLock(action: () -> T): T {
        nativeLock.lock()
        try { return action() } finally { nativeLock.unlock() }
    }
}
