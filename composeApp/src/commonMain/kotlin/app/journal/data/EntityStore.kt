package app.journal.data

import app.journal.util.PlatformLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Entity store backed by a mutable map that owns its own StateFlow.
 * Emits a fresh list snapshot after every state-changing operation.
 *
 * Emission policy: [put] emits on every call (the caller may have changed
 * fields even when the id is unchanged); [putAll], [remove], [removeWhere]
 * and [clear] skip the emission when they changed nothing, so batch callers
 * never trigger a recomposition for a no-op.
 *
 * Single operations are internally locked, so concurrent put/get/remove
 * cannot corrupt the underlying map (the previous unsynchronized HashMap
 * could lose entries or corrupt its structure under concurrent resize).
 * Compound operations that span multiple stores or indices must STILL hold
 * an external lock (see JournalRepository). Lock order is always
 * repo-lock -> store-lock; the store never acquires the repo lock.
 * (PlatformLock is reentrant, so repo-held code may re-enter a store.)
 *
 * @param T entity type
 * @param idOf function extracting a stable string ID from each entity
 */
internal class EntityStore<T>(
    private val idOf: (T) -> String,
) {
    private val map = mutableMapOf<String, T>()
    private val lock = PlatformLock()

    private val _flow = MutableStateFlow<List<T>>(emptyList())
    val flow: StateFlow<List<T>> = _flow.asStateFlow()

    fun put(value: T): T? = lock.withLock {
        val prev = map.put(idOf(value), value)
        emit()
        prev
    }

    fun get(id: String): T? = lock.withLock { map[id] }

    fun remove(id: String): T? = lock.withLock {
        val prev = map.remove(id)
        if (prev != null) emit()
        prev
    }

    fun removeWhere(predicate: (T) -> Boolean): List<T> = lock.withLock {
        val toRemove = map.values.filter(predicate)
        toRemove.forEach { map.remove(idOf(it)) }
        if (toRemove.isNotEmpty()) emit()
        toRemove
    }

    fun putAll(items: List<T>) {
        if (items.isEmpty()) return
        lock.withLock {
            items.forEach { map[idOf(it)] = it }
            emit()
        }
    }

    fun clear() = lock.withLock {
        if (map.isEmpty()) return@withLock
        map.clear()
        emit()
    }

    val all: List<T> get() = lock.withLock { map.values.toList() }
    val size: Int get() = lock.withLock { map.size }

    fun forEachValue(action: (T) -> Unit) {
        lock.withLock { map.values.toList() }.forEach(action)
    }

    private fun emit() {
        _flow.value = map.values.toList()
    }
}
