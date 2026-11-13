package app.journal.ui.pet

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PetSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun firstRunLeavesTheCompanionOff() {
        assertFalse(PetSettings().enabled)
    }

    @Test
    fun aStoredChoiceOverridesTheDefault() {
        val stored = json.decodeFromString(PetSettings.serializer(), """{"enabled":true,"slug":"lumen"}""")
        assertTrue(stored.enabled)
    }

    @Test
    fun aFileWithoutTheFlagFallsBackToOff() {
        val stored = json.decodeFromString(PetSettings.serializer(), """{"slug":"lumen"}""")
        assertFalse(stored.enabled)
    }
}
