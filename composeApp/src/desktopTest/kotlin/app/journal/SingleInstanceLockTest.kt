package app.journal

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SingleInstanceLockTest {

    @Test
    fun aSecondLockCannotBeHeldAtTheSameTime() {
        val dir = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "nepenthe-instance-${System.nanoTime()}",
        ).apply { mkdirs() }
        try {
            val first = SingleInstanceLock.acquire(dir)
            assertNotNull(first, "the first instance must acquire the data directory")
            assertNull(
                SingleInstanceLock.acquire(dir),
                "a second instance must not acquire the data directory",
            )
            first.close()
            val after = SingleInstanceLock.acquire(dir)
            assertNotNull(after, "closing the first lock must free it")
            after.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
