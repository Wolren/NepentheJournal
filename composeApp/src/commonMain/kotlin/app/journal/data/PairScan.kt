package app.journal.data

/**
 * Scan all unique pairs in [items] with a [matcher] callback.
 * Returns all non-null results from [matcher].
 */
inline fun <T, R> scanPairs(
    items: List<T>,
    matcher: (Int, T, Int, T) -> R?
): List<R> {
    val result = mutableListOf<R>()
    for (i in items.indices) {
        for (j in i + 1 until items.size) {
            matcher(i, items[i], j, items[j])?.let { result.add(it) }
        }
    }
    return result
}
