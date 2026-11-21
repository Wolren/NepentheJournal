package app.journal.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Short journey across the three main tabs: dashboard, substance list, settings.
 * Each step waits for a screen marker, so a broken navigation fails the benchmark.
 */
@RunWith(AndroidJUnit4::class)
class JourneyBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun tabJourney() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Dashboard")), 10_000)

        device.findObject(By.text("Substances")).click()
        device.wait(Until.hasObject(By.textStartsWith("Search substances")), 10_000)

        device.findObject(By.text("Dashboard")).click()
        device.waitForIdle()

        device.findObject(By.text("Settings")).click()
        device.wait(Until.hasObject(By.textStartsWith("Settings")), 10_000)

        device.findObject(By.text("Dashboard")).click()
        device.wait(Until.hasObject(By.text("Dashboard")), 10_000)
    }

    private companion object {
        const val PACKAGE = "app.journal.nepenthe"
    }
}
