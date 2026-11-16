package app.journal.util

/**
 * Maps [items] with [transform], using the platform's parallel lanes where it has
 * them. Encounter order is preserved so callers can rely on index-aligned results.
 */
internal expect fun <T, R> parallelMap(items: List<T>, transform: (T) -> R): List<R>
