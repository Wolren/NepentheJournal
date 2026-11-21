package app.journal.data

import kotlin.test.Test
import kotlin.test.assertSame

class DataInitializerStoreTest {

    @Test
    fun storeForReturnsSameInstance() {
        val repo = JournalRepository.instance

        assertSame(DataInitializer.storeFor(repo), DataInitializer.storeFor(repo))
    }
}
