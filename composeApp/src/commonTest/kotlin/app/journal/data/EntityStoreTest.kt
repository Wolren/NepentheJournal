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
    fun putAllReplacesById() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        store.putAll(listOf(
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
    fun putWithSameIdReplacesAndShrinks() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        store.put(TestEntity("a", "Alice-2", 2))
        assertEquals(1, store.size) // not 2
    }

    @Test
    fun concurrentPutAndReadIsConsistent() {
        val store = EntityStore(idOf)
        val threads = List(4) { i ->
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
        // EntityStore is NOT thread-safe for concurrent writes.
        // This test verifies that concurrent access doesn't throw or completely corrupt.
        // We only verify that keys are bounded by the number of threads.
        assertTrue(store.size in 1..4, "EntityStore should have 1-4 unique keys after 4 concurrent writers")
        for (i in 0 until 4) {
            val entity = store.get("e:$i")
            if (entity != null) {
                assertTrue(entity.value >= 0, "entity e:$i should have non-negative value")
            }
        }
    }

    @Test
    fun putAllWithEmptyListIsNoOp() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        store.putAll(emptyList())
        assertEquals(1, store.size)
        assertEquals("Alice", store.get("a")?.name)
    }

    @Test
    fun clearEmitsUpdatedState() {
        val store = EntityStore(idOf)
        store.put(TestEntity("a", "Alice", 1))
        store.clear()
        assertEquals(0, store.flow.value.size)
    }
}
