package app.journal.data.repository

import app.journal.util.Hlc
import app.journal.util.HybridLogicalClock
import app.journal.util.currentTimeMillis

/**
 * One hybrid logical clock per repository. Local writes take a fresh stamp; sync
 * applies only observe (never restamp), so both peers converge on the same value.
 *
 * Thread safety: every call runs under the repository lock. The generator state is
 * in memory; the load path observes every stored stamp, which seeds it on restart.
 */
internal class EntityClocks {

    private val generator = HybridLogicalClock()

    /**
     * Stamp for a local write at [atLeastMs]. The physical half is the wall clock or
     * the row's own updatedAt, whichever is larger, so a future-dated caller
     * timestamp keeps the clock order consistent with the timestamp it carries.
     */
    fun next(atLeastMs: Long = 0L): String {
        val wall = currentTimeMillis()
        return generator.now(if (atLeastMs > wall) atLeastMs else wall).encoded
    }

    /** Receive step for a stamp read from a peer or from disk; malformed text is ignored. */
    fun observe(encoded: String) {
        Hlc.parseOrNull(encoded)?.let(generator::observe)
    }
}
