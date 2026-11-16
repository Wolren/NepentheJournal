package app.journal.util

/** Native has no fork-join pool here, and a phone cold start cannot spare lanes anyway. */
internal actual fun <T, R> parallelMap(items: List<T>, transform: (T) -> R): List<R> = items.map(transform)
