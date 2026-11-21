package app.journal.ui.settings

import app.journal.data.JournalRepository
import app.journal.data.JournalStore
import java.io.File
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataSettingsViewModelTest {

    private fun withTempHome(test: (String) -> Unit) {
        val tmpDir = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "nepenthe-test-settings-${System.nanoTime()}"
        )
        tmpDir.mkdirs()
        val origHome = System.getProperty("user.home")
        System.setProperty("user.home", tmpDir.absolutePath)
        try {
            test(tmpDir.absolutePath)
        } finally {
            System.setProperty("user.home", origHome)
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun createBackupWritesAnAutoBackup() = runTest {
        withTempHome {
            val repo = JournalRepository()
            val storePath = JournalStore(repo).dataPath()
            val vm = DataSettingsViewModel.create(repo, this)
            vm.createBackup()
            advanceUntilIdle()

            val autoDir = File(File(storePath).parentFile, ".auto")
            assertTrue(
                autoDir.listFiles()?.isNotEmpty() == true,
                "the backup must exist in ${autoDir.absolutePath}"
            )
            assertEquals("Backup created in the .auto folder", vm.dataStatus.value)
        }
    }
}
