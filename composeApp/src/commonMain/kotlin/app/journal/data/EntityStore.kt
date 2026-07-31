package app.journal.data

import app.journal.util.PlatformLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Entity store backed by a mutable map that owns its own StateFlow.
 * Emits a fresh list snapshot after every mutation.
 *
 * Single operations are internally locked, so concurrent put/get/remove
 * cannot corrupt the underlying map (the previous unsynchronized HashMap
 * could lose entries or corrupt its structure under concurrent resize).
 * Compound operations that span multiple stores or indices must STILL hold
 * an external lock (see JournalRepository). Lock order is always
 * repo-lock → store-lock; the store never acquires the repo lock.
 *
 * @param T entity type
 * @param idOf function extracting a stable string ID from each entity
 * @param initialEntities optional seed data loaded on construction
 */
internal class EntityStore<T>(
    private val idOf: (T) -> String,
    initialEntities: List<T> = emptyList(),
) {
    private val map = mutableMapOf<String, T>()
    private val lock = PlatformLock()

    private val _flow = MutableStateFlow<List<T>>(initialEntities)
    val flow: StateFlow<List<T>> = _flow.asStateFlow()

    init {
        if (initialEntities.isNotEmpty()) {
            initialEntities.forEach { map[idOf(it)] = it }
        }
    }

    /** Insert or replace an entity by its ID. Returns the previous value, or null. */
    fun put(value: T): T? = lock.withLock {
        val prev = map.put(idOf(value), value)
        emit()
        prev
    }

    /** Look up an entity by ID. Returns null if missing. */
    fun get(id: String): T? = lock.withLock { map[id] }

    /** Remove an entity by ID. Returns the removed value, or null. */
    fun remove(id: String): T? = lock.withLock {
        val prev = map.remove(id)
        if (prev != null) emit()
        prev
    }

    /** Remove all entities matching [predicate]. Returns the removed list. */
    fun removeWhere(predicate: (T) -> Boolean): List<T> = lock.withLock {
        val toRemove = map.values.filter(predicate)
        toRemove.forEach { map.remove(idOf(it)) }
        if (toRemove.isNotEmpty()) emit()
        toRemove
    }

    /** Insert or replace multiple entities by ID. Single emit. */
    fun putAll(items: List<T>) {
        if (items.isEmpty()) return
        lock.withLock {
            items.forEach { map[idOf(it)] = it }
            emit()
        }
    }

    /** Remove multiple entities by ID. Single emit. Returns count removed. */
    fun removeAll(ids: Collection<String>): Int = lock.withLock {
        var count = 0
        for (id in ids) {
            if (map.remove(id) != null) count++
        }
        if (count > 0) emit()
        count
    }

    /** Remove all entities. */
    fun clear() = lock.withLock {
        if (map.isEmpty()) return@withLock
        map.clear()
        emit()
    }

    val all: List<T> get() = lock.withLock { map.values.toList() }
    val size: Int get() = lock.withLock { map.size }
    val keys: Set<String> get() = lock.withLock { map.keys.toSet() }

    /**
     * Execute a block with the mutable map for batch operations.
     * Single emit after the block completes.
     */
    fun batch(action: MutableMap<String, T>.() -> Unit) = lock.withLock {
        map.action()
        emit()
    }

    /** Iterate over a snapshot of values. Safe during concurrent modification. */
    fun forEachValue(action: (T) -> Unit) {
        lock.withLock { map.values.toList() }.forEach(action)
    }

    private fun emit() {
        _flow.value = map.values.toList()
    }
}
