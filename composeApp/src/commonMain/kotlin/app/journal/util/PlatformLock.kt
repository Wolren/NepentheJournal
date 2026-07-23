package app.journal.util

expect class PlatformLock() {
    fun <T> withLock(action: () -> T): T
}
