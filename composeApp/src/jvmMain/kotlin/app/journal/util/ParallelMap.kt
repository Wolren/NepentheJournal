package app.journal.util

import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory

/**
 * Below [PARALLEL_THRESHOLD] the fork-join split costs more than the work it saves.
 * The pool keeps two lanes free: startup runs this while the UI thread still frames.
 */
internal actual fun <T, R> parallelMap(items: List<T>, transform: (T) -> R): List<R> {
    if (items.size < PARALLEL_THRESHOLD) return items.map(transform)
    return POOL.submit<List<R>> { items.parallelStream().map { transform(it) }.toList() }.get()
}

private const val PARALLEL_THRESHOLD = 128

private val POOL: ForkJoinPool = run {
    val lanes = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 8)
    val factory = ForkJoinWorkerThreadFactory { pool: ForkJoinPool ->
        object : ForkJoinWorkerThread(pool) {
            init {
                priority = Thread.MIN_PRIORITY + 2
            }
        }
    }
    ForkJoinPool(lanes, factory, null, false)
}
