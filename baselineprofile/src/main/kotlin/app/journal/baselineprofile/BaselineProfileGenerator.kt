package app.journal.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup profile for the shipped app: cold start, wait for the journal gate, then the
 * first dashboard composition. Every step here is on the launch path; keep it that way.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg(PACKAGE)), 5_000)
        // The dashboard paints right after the gate opens; give the first frame room.
        Thread.sleep(1_500)
    }

    private companion object {
        const val PACKAGE = "app.journal.nepenthe"
    }
}
