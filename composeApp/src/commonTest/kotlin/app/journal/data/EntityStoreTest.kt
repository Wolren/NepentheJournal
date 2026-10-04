package app.journal.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    fun concurrentPutAndReadIsConsistent() = runBlocking {
        // Coroutines instead of java.lang.Thread: this source set also compiles
        // for Kotlin/Native (iosSimulatorArm64Test), which has no Thread class.
        // Dispatchers.Default is a real multi-threaded pool on both JVM and
        // Native, so the writes below still race across cores the way the old
        // four-Thread version did.
        val store = EntityStore(idOf)
        coroutineScope {
            repeat(4) { i ->
                launch(Dispatchers.Default) {
                    val keys = (0 until 64).map { j -> "e:$i:$j" }
                    keys.forEach { k -> store.put(TestEntity(k, "first-$i", i)) }
                    keys.forEach { k -> store.put(TestEntity(k, "Entity-$i-$k", i * 64)) }
                }
            }
        }

        // Every EntityStore operation takes the store lock (see the class docs:
        // the old unsynchronized map could lose entries under concurrent resize,
        // which is exactly what the lock was added to prevent).
        //
        // 4 writers x 64 DISTINCT keys = 256 entries, well past a HashMap's
        // default threshold of 12, so the map really resizes while all four
        // writers are inside it. Four writers hammering 4 keys (the original
        // shape) never resizes at all and so never exercised the race this
        // exists for: measured against a lockless put it caught the mutation
        // 0/25 runs; this shape catches it 25/25. Do not shrink the key count
        // back down.
        //
        // Nothing here gets a range or a null guard: all 256 keys must survive,
        // and each must hold the final pass of its own writer. Key e:$i:$j is
        // written by writer i only, so the expected final value is
        // deterministic, not "whatever was left behind".
        assertEquals(256, store.size, "no key may be lost to concurrent put")
        for (i in 0 until 4) {
            for (j in 0 until 64) {
                val key = "e:$i:$j"
                val entity = assertNotNull(store.get(key), "key $key must survive its writes")
                assertEquals(key, entity.id, "entry stored under $key must carry that same id")
                assertEquals(
                    "Entity-$i-$key", entity.name,
                    "$key must hold writer $i's final pass, not an earlier one"
                )
                assertEquals(
                    i * 64, entity.value,
                    "$key's value must belong to the write its name describes"
                )
            }
        }
        assertEquals(256, store.flow.value.size, "the emitted snapshot must match the store")
        assertEquals(
            (0 until 4).flatMap { i -> (0 until 64).map { j -> "e:$i:$j" } }.toSet(),
            store.all.map { it.id }.toSet(),
            "the snapshot must expose exactly the written keys"
        )
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
