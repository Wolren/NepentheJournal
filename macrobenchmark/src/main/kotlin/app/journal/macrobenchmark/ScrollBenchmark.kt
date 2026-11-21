package app.journal.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing for the substance list, the longest scrolling surface in the app.
 * Requires the baseline profile: run :baselineprofile first on a fresh checkout.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun flingSubstanceList() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Dashboard")), 10_000)
        device.findObject(By.text("Substances")).click()
        device.wait(Until.hasObject(By.textStartsWith("Search substances")), 10_000)
        val list = device.wait(Until.findObject(By.scrollable(true)), 10_000)
            ?: error("substance list is not scrollable")
        repeat(5) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE = "app.journal.nepenthe"
    }
}
