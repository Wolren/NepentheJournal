package app.journal.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Entity store backed by a mutable map that owns its own StateFlow.
 * Emits a fresh list snapshot after every mutation.
 *
 * NOT thread-safe — callers must hold their own lock for compound operations
 * that span multiple stores or indices.
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

    private val _flow = MutableStateFlow<List<T>>(initialEntities)
    val flow: StateFlow<List<T>> = _flow.asStateFlow()

    init {
        if (initialEntities.isNotEmpty()) {
            initialEntities.forEach { map[idOf(it)] = it }
        }
    }

    /** Insert or replace an entity by its ID. Returns the previous value, or null. */
    fun put(value: T): T? {
        val prev = map.put(idOf(value), value)
        emit()
        return prev
    }

    /** Look up an entity by ID. Returns null if missing. */
    fun get(id: String): T? = map[id]

    /** Remove an entity by ID. Returns the removed value, or null. */
    fun remove(id: String): T? {
        val prev = map.remove(id)
        if (prev != null) emit()
        return prev
    }

    /** Remove all entities matching [predicate]. Returns the removed list. */
    fun removeWhere(predicate: (T) -> Boolean): List<T> {
        val toRemove = map.values.filter(predicate)
        toRemove.forEach { map.remove(idOf(it)) }
        if (toRemove.isNotEmpty()) emit()
        return toRemove
    }

    /** Bulk-insert from a list, replacing existing entries by ID. Single emit. */
    fun applyAll(items: List<T>) {
        if (items.isEmpty()) return
        items.forEach { map[idOf(it)] = it }
        emit()
    }

    /** Insert or replace multiple entities by ID. Single emit. */
    fun putAll(items: List<T>) {
        if (items.isEmpty()) return
        items.forEach { map[idOf(it)] = it }
        emit()
    }

    /** Remove multiple entities by ID. Single emit. Returns count removed. */
    fun removeAll(ids: Collection<String>): Int {
        var count = 0
        for (id in ids) {
            if (map.remove(id) != null) count++
        }
        if (count > 0) emit()
        return count
    }

    /** Remove all entities. */
    fun clear() {
        if (map.isEmpty()) return
        map.clear()
        emit()
    }

    val all: List<T> get() = map.values.toList()
    val size: Int get() = map.size
    val keys: Set<String> get() = map.keys.toSet()

    /**
     * Execute a block with the mutable map for batch operations.
     * Single emit after the block completes.
     */
    fun batch(action: MutableMap<String, T>.() -> Unit) {
        map.action()
        emit()
    }

    /** Iterate over a snapshot of values. Safe during concurrent modification. */
    fun forEachValue(action: (T) -> Unit) {
        map.values.toList().forEach(action)
    }

    private fun emit() {
        _flow.value = map.values.toList()
    }
}
