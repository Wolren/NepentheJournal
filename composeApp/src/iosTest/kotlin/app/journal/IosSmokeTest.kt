package app.journal

import app.journal.model.SyncConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke test for the intermediate `iosTest` source set
 * (composeApp/src/iosTest, wired in build.gradle.kts by hand because the
 * default hierarchy template is disabled).
 *
 * It is NEVER executed on Windows: this host must not run ios compile or
 * test tasks. Configuration was verified on Windows with
 * `./gradlew compileKotlinDesktop` and `./gradlew tasks --group
 * verification`; compilation and execution belong to mac CI
 * (iosSimulatorArm64Test).
 */
class IosSmokeTest {

    @Test
    fun sourceSetCompilesAgainstCommonMain() {
        assertEquals(4984, SyncConfig.DEFAULT_PORT,
            "iosTest must see commonMain declarations")
    }

    @Test
    fun trivialAssertionRuns() {
        assertTrue(true, "kotlin.test must be on the iosTest classpath")
    }
}
