package app.journal.data

import app.journal.model.AutosaveDelay
import app.journal.model.StartTab
import app.journal.serde.AppJson
import app.journal.util.TimeDisplayMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The preferences added in wave5 must default to the behaviour that predates
 * them, so a file written by an older build loads unchanged, and must survive
 * the snapshot round-trip the store and sync both use.
 */
class PreferencesPersistenceTest {

    @Test
    fun defaultsKeepTheBehaviourThatPredatesTheSettings() {
        val repo = JournalRepository()
        assertEquals(StartTab.DASHBOARD, repo.startTab.value)
        assertEquals(TimeDisplayMode.RELATIVE, repo.timeDisplayMode.value)
        assertTrue(repo.dashboardHeatmap.value)
        assertTrue(repo.dashboardTolerance.value)
        assertTrue(repo.dashboardTopSubstances.value)
        assertTrue(repo.confirmDeletes.value)
        assertEquals(AutosaveDelay.FAST, repo.autosaveDelay.value)
        assertEquals(600L, repo.autosaveDelay.value.delayMs, "FAST keeps the old 600 ms draft autosave")
        assertTrue(repo.autoBackupOnClose.value)
    }

    @Test
    fun everyPreferenceRoundTripsThroughTheSnapshot() {
        val repo = JournalRepository()
        repo.setStartTab(StartTab.SESSIONS)
        repo.setTimeDisplayMode(TimeDisplayMode.CLOCK)
        repo.setDashboardHeatmap(false)
        repo.setDashboardTolerance(false)
        repo.setDashboardTopSubstances(false)
        repo.setConfirmDeletes(false)
        repo.setAutosaveDelay(AutosaveDelay.SLOW)
        repo.setAutoBackupOnClose(false)

        val text = AppJson.json.encodeToString(repo.fullSnapshot())
        val restored = JournalRepository()
        AppJson.apply(restored, AppJson.json.decodeFromString<JournalSnapshot>(text))

        assertEquals(StartTab.SESSIONS, restored.startTab.value)
        assertEquals(TimeDisplayMode.CLOCK, restored.timeDisplayMode.value)
        assertFalse(restored.dashboardHeatmap.value)
        assertFalse(restored.dashboardTolerance.value)
        assertFalse(restored.dashboardTopSubstances.value)
        assertFalse(restored.confirmDeletes.value)
        assertEquals(AutosaveDelay.SLOW, restored.autosaveDelay.value)
        assertFalse(restored.autoBackupOnClose.value)
    }

    @Test
    fun aFileWrittenBeforeTheSettingsLoadsTheDefaults() {
        val legacy = """{"version":9,"savedAt":1,"welcomeCompleted":true}"""
        val restored = JournalRepository()
        AppJson.apply(restored, AppJson.json.decodeFromString<JournalSnapshot>(legacy))

        assertEquals(StartTab.DASHBOARD, restored.startTab.value)
        assertEquals(TimeDisplayMode.RELATIVE, restored.timeDisplayMode.value)
        assertTrue(restored.dashboardHeatmap.value)
        assertTrue(restored.dashboardTolerance.value)
        assertTrue(restored.dashboardTopSubstances.value)
        assertTrue(restored.confirmDeletes.value)
        assertEquals(AutosaveDelay.FAST, restored.autosaveDelay.value)
        assertTrue(restored.autoBackupOnClose.value)
    }
}
