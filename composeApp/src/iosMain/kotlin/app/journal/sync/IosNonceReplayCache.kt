package app.journal.sync

import app.journal.util.PlatformLock
import app.journal.util.currentTimeMillis

/**
 * iOS nonce replay cache with a 45s window and oldest first eviction.
 *
 * Same contract as the JVM SyncAuthenticator nonce cache: a nonce is valid
 * only inside the timestamp window and only once. When the cache exceeds
 * [maxEntries], entries outside the window are pruned first, then the
 * oldest entries are evicted one at a time until under the limit. The cache
 * is never cleared wholesale: a full clear would reopen a replay window for
 * every nonce seen in the last 45 seconds.
 */
class IosNonceReplayCache(private val maxEntries: Int = 5_000) {

    private val iosSyncLock = PlatformLock()
    private val seen = LinkedHashMap<String, Long>()

    /**
     * Check the timestamp window and record a nonce.
     * Returns true only when the timestamp is inside the window and the
     * nonce was not seen before.
     */
    fun checkAndRecord(nonce: String, timestamp: Long): Boolean = iosSyncLock.withLock {
        val now = currentTimeMillis()
        if (kotlin.math.abs(now - timestamp) > TIMESTAMP_WINDOW_MS) {
            false
        } else if (seen.containsKey(nonce)) {
            false
        } else {
            if (seen.size >= maxEntries) {
                val cutoff = now - TIMESTAMP_WINDOW_MS
                val expired = seen.entries.filter { it.value < cutoff }.map { it.key }
                for (key in expired) seen.remove(key)
                while (seen.size >= maxEntries) {
                    val oldest = seen.entries.minByOrNull { it.value }?.key ?: break
                    seen.remove(oldest)
                }
            }
            seen[nonce] = timestamp
            true
        }
    }

    fun size(): Int = iosSyncLock.withLock { seen.size }

    companion object {
        /** 45s window: 30s validity plus 15s clock skew allowance. */
        const val TIMESTAMP_WINDOW_MS = 45_000L
    }
}
