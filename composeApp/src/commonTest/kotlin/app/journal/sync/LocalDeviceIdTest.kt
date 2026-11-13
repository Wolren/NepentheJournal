package app.journal.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalDeviceIdTest {

    @Test
    fun aStoredIdIsReused() {
        assertEquals("device-abc", LocalDeviceId.chooseDeviceId("device-abc", legacyInUse = true))
    }

    @Test
    fun anInstallWithPairingsKeepsTheIdItsPeersHold() {
        assertEquals(LocalDeviceId.LEGACY, LocalDeviceId.chooseDeviceId(null, legacyInUse = true))
    }

    @Test
    fun aFreshInstallGetsAnIdOfItsOwn() {
        val first = LocalDeviceId.chooseDeviceId(null, legacyInUse = false)
        val second = LocalDeviceId.chooseDeviceId(null, legacyInUse = false)
        assertNotEquals(LocalDeviceId.LEGACY, first)
        assertTrue(first.startsWith("device-"), "unexpected id $first")
        assertNotEquals(first, second)
    }

    @Test
    fun aBlankStoredValueCountsAsAbsent() {
        assertEquals(LocalDeviceId.LEGACY, LocalDeviceId.chooseDeviceId("   ", legacyInUse = true))
    }
}
