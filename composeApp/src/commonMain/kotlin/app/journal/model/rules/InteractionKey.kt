package app.journal.model.rules

/**
 * Normalized key for a pairwise interaction lookup.
 * Always stores the smaller ID first so that (A,B) and (B,A) map to the same entry.
 */
data class InteractionKey(val value: String) {
    companion object {
        fun of(a: String, b: String): InteractionKey {
            val key = if (a < b) "$a|$b" else "$b|$a"
            return InteractionKey(key)
        }
    }
}
