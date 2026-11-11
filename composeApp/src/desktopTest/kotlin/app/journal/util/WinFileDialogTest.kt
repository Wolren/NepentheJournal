package app.journal.util

import kotlin.test.Test
import kotlin.test.assertTrue

class WinFileDialogTest {

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Test
    fun theModernDialogCanBeCreated() {
        if (!isWindows()) return
        assertTrue(WinFileDialog.available(), "CoCreateInstance on IFileOpenDialog failed")
    }

    @Test
    fun theDialogTakesItsTitleFilterAndOptions() {
        if (!isWindows()) return
        assertTrue(WinFileDialog.selfTest(), "configuring the dialog failed")
    }
}
