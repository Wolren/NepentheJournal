package app.journal.util

/**
 * Hybrid logical clock value from Kulkarni et al., "Logical Physical Clocks and
 * Consistent Snapshots in Globally Distributed Databases" (2014): a physical
 * millisecond plus a logical counter.
 *
 * Wire form is "<physicalMs>:<counter>". The node id half of the HLC tuple is the
 * entity's deviceOrigin and is applied as the final tiebreak in
 * app.journal.data.repository.EntityOrder. A blank string means "no stamp", which is
 * a row written before snapshot version 10; ordering then falls back to updatedAt.
 */
data class Hlc(val physicalMs: Long, val counter: Long) : Comparable<Hlc> {

    init {
        require(physicalMs >= 0L) { "physicalMs must be non-negative" }
        require(counter >= 0L) { "counter must be non-negative" }
    }

    val encoded: String get() = "$physicalMs:$counter"

    override fun compareTo(other: Hlc): Int {
        if (physicalMs != other.physicalMs) return physicalMs.compareTo(other.physicalMs)
        return counter.compareTo(other.counter)
    }

    companion object {
        /** Longest accepted encoded form: 19 digits, ':', 19 digits. */
        const val MAX_ENCODED_LENGTH = 39

        /** Clamps negative input so callers with junk timestamps cannot build an invalid value. */
        fun of(physicalMs: Long, counter: Long = 0L): Hlc =
            Hlc(physicalMs.coerceAtLeast(0L), counter.coerceAtLeast(0L))

        /** Parses "<physicalMs>:<counter>"; null for blank, malformed, over-long or negative text. */
        fun parseOrNull(text: String): Hlc? {
            if (text.isEmpty() || text.length > MAX_ENCODED_LENGTH) return null
            val colon = text.indexOf(':')
            if (colon <= 0 || colon == text.length - 1) return null
            if (text.indexOf(':', colon + 1) >= 0) return null
            for (index in text.indices) {
                if (index == colon) continue
                if (!text[index].isDigit()) return null
            }
            val physical = text.substring(0, colon).toLongOrNull() ?: return null
            val counter = text.substring(colon + 1).toLongOrNull() ?: return null
            return Hlc(physical, counter)
        }
    }
}

/**
 * Monotonic hybrid logical clock. [now] is the write step, [observe] the receive
 * step; both stay monotonic when the wall clock steps backward.
 *
 * Not thread-safe: the repository lock guards the single instance owned by
 * EntityClocks, and tests drive it from one thread.
 */
class HybridLogicalClock {

    private var physicalMs: Long = 0L
    private var counter: Long = 0L

    val current: Hlc get() = Hlc(physicalMs, counter)

    /**
     * Next write stamp. [nowMs] normally is the wall clock; callers may pass a
     * larger value (an entity's own future-dated updatedAt) to keep the clock
     * consistent with the timestamp the row carries.
     */
    fun now(nowMs: Long = currentTimeMillis()): Hlc {
        val wall = nowMs.coerceAtLeast(0L)
        when {
            wall > physicalMs -> {
                physicalMs = wall
                counter = 0L
            }
            counter == Long.MAX_VALUE -> {
                physicalMs += 1L
                counter = 0L
            }
            else -> counter += 1L
        }
        return Hlc(physicalMs, counter)
    }

    /** Receive step: advance past [remote] so the next local write is causally later. */
    fun observe(remote: Hlc) {
        val nextPhysical = if (remote.physicalMs > physicalMs) remote.physicalMs else physicalMs
        val baseCounter = when {
            nextPhysical == physicalMs && nextPhysical == remote.physicalMs ->
                if (counter > remote.counter) counter else remote.counter
            nextPhysical == physicalMs -> counter
            else -> remote.counter
        }
        if (baseCounter == Long.MAX_VALUE) {
            physicalMs = nextPhysical + 1L
            counter = 0L
        } else {
            physicalMs = nextPhysical
            counter = baseCounter + 1L
        }
    }
}
