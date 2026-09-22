package app.journal.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.UninitializedPropertyAccessException
import kotlin.test.assertFailsWith

/**
 * Local JVM unit tests for the Android `JournalStore` actual
 * (composeApp/src/androidMain/kotlin/app/journal/data/JournalStoreAndroid.kt).
 * Run with: ./gradlew :composeApp:testDebugUnitTest
 *
 * BLOCKER (audit 4, item 6), documented instead of faked: the behaviors the
 * hardening plan asks for here (corrupt load sets lastLoadHadIssues /
 * lastLoadIssueSummary, restoreFromBackup reloads a good .bak, .auto rotation
 * keeps 10) all go through `dataPath()`, which resolves
 * `File(NepentheApp.appContext.filesDir, ".nepenthe")`. In a local unit test:
 *   - `android.content.Context` comes from AGP's mockable android.jar, where
 *     every method is a stub, so no filesDir can be obtained;
 *   - `NepentheApp.instance` is `private set` and `NepentheApp` is final, so
 *     there is no seam to inject a temp directory;
 *   - Robolectric is excluded by the test plan for this wave.
 * Nothing in those three behaviors is reachable without a Context seam in
 * production source (which this wave may not touch).
 *
 * [loadCannotRunWithoutAnAndroidContext] pins the blocker deliberately: when
 * a Context seam (or Robolectric) lands it fails, and the failure message is
 * the instruction to replace it with the real corrupt-load / restore /
 * rotation coverage.
 */
class JournalStoreAndroidTest {

    @Test
    fun issueFlagsStartCleanBeforeAnyLoad() {
        val store = JournalStore(JournalRepository())
        assertFalse(store.lastLoadHadIssues,
            "a store that has never loaded must not claim recovery issues")
        assertEquals("", store.lastLoadIssueSummary,
            "a store that has never loaded must have an empty issue summary")
    }

    @Test
    fun loadResetsTheIssueFlagsBeforeItReachesTheContext() {
        val store = JournalStore(JournalRepository())
        // load() clears both flags first and only then touches dataPath(),
        // so the Context requirement is what surfaces here, not recovery.
        val thrown = assertFailsWith<UninitializedPropertyAccessException> {
            store.load()
        }
        assertTrue(thrown.message?.contains("instance") == true,
            "expected the Android Context seam to be the missing piece, got: ${thrown.message}")
        assertFalse(store.lastLoadHadIssues,
            "flags must stay clean when the load never started reading a file")
        assertEquals("", store.lastLoadIssueSummary,
            "summary must stay empty when the load never started reading a file")
    }
}
