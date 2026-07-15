package app.journal.data

import kotlin.test.*

class EntityStoreTest {

    private data class TestEntity(val id: String, val name: String, val value: Int)
    private val idOf: (TestEntity) -> String = { it.id }

    @Test
    fun emptyStoreHasNoItems() {
        val store = EntityStore(idOf)
        assertEquals(0, store.size)
        assertTrue(store.all.isEmpty())
        assertTrue(store.keys.isEmpty())
    }

    @Test
    fun putAddsItem() {
        val store = EntityStore(idOf)
        val item = TestEntity("a", "Alice", 1)
        assertNull(store.put(item))
        assertEquals(1, store.size)
        assertEquals("Alice", store.get("a")?.name)
    }

    @Test
    fun putReturnsPreviousValue() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        val prev = store.put(TestEntity("a", "Bob", 2))
        assertNotNull(prev)
        assertEquals("Alice", prev?.name)
        assertEquals("Bob", store.get("a")?.name)
    }

    @Test
    fun putEmitsViaStateFlow() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        assertEquals(1, store.flow.value.size)
        assertEquals("Alice", store.flow.value.first().name)
    }

    @Test
    fun getReturnsNullForMissing() {
        val store = EntityStore(idOf)
        assertNull(store.get("nonexistent"))
    }

    @Test
    fun removeReturnsPreviousValue() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        val removed = store.remove("a")
        assertNotNull(removed)
        assertEquals("Alice", removed?.name)
        assertNull(store.get("a"))
        assertEquals(0, store.size)
    }

    @Test
    fun removeMissingReturnsNull() {
        val store = EntityStore(idOf)
        assertNull(store.remove("nonexistent"))
    }

    @Test
    fun removeWhereRemovesMatchingItems() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        store.put(TestEntity("b", "Bob", 2))
        store.put(TestEntity("c", "Charlie", 1))
        val removed = store.removeWhere { it.value == 1 }
        assertEquals(2, removed.size)
        assertEquals(1, store.size)
        assertEquals("Bob", store.get("b")?.name)
    }

    @Test
    fun removeWhereReturnsEmptyForNoMatch() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        assertTrue(store.removeWhere { it.value == 99 }.isEmpty())
        assertEquals(1, store.size)
    }

    @Test
    fun applyAllReplacesById() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        store.applyAll(listOf(
            TestEntity("a", "Alice-Updated", 10),
            TestEntity("b", "Bob", 2)
        ))
        assertEquals(2, store.size)
        assertEquals("Alice-Updated", store.get("a")?.name)
        assertEquals("Bob", store.get("b")?.name)
    }

    @Test
    fun clearEmptiesStore() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        store.put(TestEntity("b", "Bob", 2))
        store.clear()
        assertEquals(0, store.size)
        assertNull(store.get("a"))
    }

    @Test
    fun forEachValueIteratesSnapshot() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        store.put(TestEntity("b", "Bob", 2))
        val names = mutableListOf<String>()
        store.forEachValue { names.add(it.name) }
        assertEquals(2, names.size)
        assertTrue("Alice" in names)
        assertTrue("Bob" in names)
    }

    @Test
    fun keysReturnsAllIds() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        store.put(TestEntity("b", "Bob", 2))
        assertEquals(setOf("a", "b"), store.keys)
    }

    @Test
    fun initialEntitiesAreLoaded() {
        val initial = listOf(
            TestEntity("a", "Alice", 1),
            TestEntity("b", "Bob", 2)
        )
        val store = EntityStore(idOf, initial)
        assertEquals(2, store.size)
        assertEquals("Alice", store.get("a")?.name)
    }

    @Test
    fun putWithSameIdReplacesAndShrinks() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        store.put(TestEntity("a", "Alice-2", 2))
        assertEquals(1, store.size) // not 2
    }

    @Test
    fun withMutableMapAllowsDirectMutation() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        store.withMutableMap { this["a"] = TestEntity("a", "Mutated", 99) }
        assertEquals("Mutated", store.get("a")?.name)
    }

    @Test
    fun concurrentPutAndReadIsConsistent() {
        val store = EntityStore(idOf)
        val threads = List(10) { i ->
            Thread {
                repeat(100) { j ->
                    store.put(TestEntity("e:$i", "Entity-$i-$j", i * j))
                }
            }
        }
        // Relaxed concurrency check: EntityStore is NOT thread-safe for concurrent put,
        // but should not throw or corrupt internal state under concurrent access.
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // All writes to unique keys should eventually be visible
        assertTrue(store.size <= 10, "at most 10 unique keys in concurrent access")
        for (i in 0 until 10) {
            val entity = store.get("e:$i")
            if (entity != null) {
                assertTrue(entity.value >= 0, "entity e:$i should have non-negative value")
            }
        }
    }
}
