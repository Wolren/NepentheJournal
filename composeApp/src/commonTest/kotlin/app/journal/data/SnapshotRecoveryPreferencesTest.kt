package app.journal.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotRecoveryPreferencesTest {

    @Test
    fun recoveryKeepsVersionAndDisplayPreferences() {
        val json = """{"version":9,"savedAt":1,"confirmDeletes":false,"autoBackupOnClose":false,"dashboardHeatmap":false,"sessions":[{"id":1}]}"""

        val snapshot = recoverSnapshot(Json.parseToJsonElement(json))

        assertEquals(9, snapshot.version)
        assertFalse(snapshot.confirmDeletes)
        assertFalse(snapshot.autoBackupOnClose)
        assertFalse(snapshot.dashboardHeatmap)
        assertTrue(snapshot.dashboardTolerance)
    }
}
