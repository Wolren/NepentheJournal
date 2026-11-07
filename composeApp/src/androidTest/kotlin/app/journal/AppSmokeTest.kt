package app.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import app.journal.export.EncryptedBackup
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke: the paths no unit test reaches, being the Activity start, the
 * first frame that must arrive before the bundled library finishes, and the
 * tabs. The library build runs on an application scope, so this waits for it.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String, timeoutMs: Long = 60_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun dashboardRendersThenSubstanceListOpens() {
        waitFor("Dashboard")
        rule.onNodeWithText("Substances").performClick()
        waitFor("Search substances")
        rule.onNodeWithText("Search substances", substring = true).assertIsDisplayed()
    }

    @Test
    fun settingsTabShowsSyncCard() {
        waitFor("Dashboard")
        rule.onNodeWithText("Settings").performClick()
        waitFor("Device Sync")
        rule.onNodeWithText("Device Sync", substring = true).assertIsDisplayed()
    }

    /** The device path of the sealed backup: zstd-jni native load plus Android's PBKDF2. */
    @Test
    fun sealedBackupRoundTripsOnTheDevice() {
        val json = """{"version":7,"note":"device round trip"}"""
        val passphrase = "device passphrase".toCharArray()
        val sealed = EncryptedBackup.seal(json, passphrase)
        assertTrue("the Android build must ship zstd", EncryptedBackup.isSupported)
        assertTrue("sealed backup must carry the NEPB1 magic", EncryptedBackup.looksSealed(sealed))
        assertEquals("round trip on device", json, EncryptedBackup.open(sealed, passphrase))
    }

    /** A nepenthe://quicklog link must land a session and its dose in the live repository. */
    @Test
    fun quickLogLinkLogsSessionAndDose() {
        val repo = app.journal.data.JournalRepository.instance
        val beforeIds = repo.sessions.value.map { it.id }.toSet()
        val link = "nepenthe://quicklog?substance=Caffeine&amount=100&unit=mg&notes=from+link"
        rule.activity.runOnUiThread {
            rule.activity.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(link)
                )
            )
        }
        rule.waitUntil(30_000) { repo.sessions.value.any { it.id !in beforeIds } }
        val session = repo.sessions.value.first { it.id !in beforeIds }
        rule.waitUntil(10_000) { repo.doses.value.any { it.sessionId == session.id } }
        val dose = repo.doses.value.first { it.sessionId == session.id }
        assertEquals("amount from the link", 100.0, dose.amount, 0.001)
        assertEquals("unit from the link", "mg", dose.unit)
    }
    /** Direct proof that the quick-log writer lands both entities on the device repository. */
    @Test
    fun quickLogApplyWritesDoseOnDevice() {
        val repo = app.journal.data.JournalRepository.instance
        // A fresh CI device builds the bundled library in the background; the writer needs its rows.
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(120_000) {
                app.journal.data.DataInitializer.libraryReadyFlow.first { it }
            }
        }
        val request = app.journal.data.QuickLog.parse("nepenthe://quicklog?substance=Caffeine&amount=100&unit=mg")!!
        val outcome = app.journal.data.QuickLog.apply(repo, request)
        assertTrue("apply must log, got: $outcome", outcome is app.journal.data.QuickLogOutcome.Logged)
        val sessionId = (outcome as app.journal.data.QuickLogOutcome.Logged).sessionId
        assertTrue(
            "dose must be attached to session $sessionId, doses=${repo.doses.value.size}",
            repo.doses.value.any { it.sessionId == sessionId && it.amount == 100.0 }
        )
    }
}