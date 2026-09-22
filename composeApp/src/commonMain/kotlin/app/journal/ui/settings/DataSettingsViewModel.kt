package app.journal.ui.settings

import app.journal.data.DataInitializer
import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
import app.journal.data.JournalStore
import app.journal.export.CsvExporter
import app.journal.export.ExportImport
import app.journal.export.ZipExporter
import app.journal.log.Log
import app.journal.log.collectLogs
import app.journal.ui.components.userMessage
import app.journal.util.FilePicker
import app.journal.util.PlatformFile
import app.journal.util.currentTimeMillis
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Data maintenance operations behind the Settings screen: seed reload,
 * test data, JSON/CSV/ZIP export and import, snapshot backup and the
 * diagnostics crash-log export.
 *
 * Follows the [app.journal.ui.session.SessionListViewModel] pattern: the
 * constructor takes the [IJournalRepository], status surfaces are
 * [MutableStateFlow]s the composables only read, and every coroutine,
 * file picker, DataInitializer touch and status string lives here. The
 * composables call the methods; they hold no business logic.
 */
class DataSettingsViewModel(
    private val repo: IJournalRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    /** True while the seed reload runs; drives the reload button's enabled state. */
    val isFetching = MutableStateFlow(false)

    /** Status line under "Reset to defaults" in the Substance library card. */
    val fetchStatus = MutableStateFlow<String?>(null)

    /** Status line inside the Data card (export, import, backup, test data). */
    val dataStatus = MutableStateFlow<String?>(null)

    /** Status line under the diagnostics crash-log export. */
    val crashLogStatus = MutableStateFlow<String?>(null)

    // ==================== Substance library ====================

    fun reloadDefaultSubstances() {
        scope.launch {
            isFetching.value = true
            fetchStatus.value = "Reloading seed data..."
            try {
                DataInitializer.reloadDefaultSubstances(repo)
                fetchStatus.value = "Reloaded ${repo.substances.value.size} substances from seed"
            } catch (e: Exception) {
                fetchStatus.value = userMessage("Settings", "Reload failed", e)
            }
            isFetching.value = false
        }
    }

    // ==================== Developer ====================

    fun resetWithTestData() {
        scope.launch {
            try {
                DataInitializer.resetWithTestData(repo)
                dataStatus.value = "Test data loaded (${repo.sessions.value.size} sessions, ${repo.substances.value.size} substances)"
            } catch (e: Exception) {
                dataStatus.value = userMessage("Settings", "Test data failed", e)
            }
        }
    }

    fun exportCrashLogs() {
        scope.launch(CoroutineExceptionHandler { _, e ->
            Log.withTag("Settings").e(e) { "Diagnostics export failed" }
        }) {
            try {
                val appDir = PlatformFile.dataDir()
                val logs = collectLogs(appDir)
                val path = FilePicker.saveFile(
                    "nepenthe-crash-${currentTimeMillis()}.log", "Log files", listOf("log", "txt")
                )
                if (path != null) {
                    PlatformFile.writeText(path, logs)
                    Log.withTag("Settings").i { "Crash logs exported to $path" }
                    crashLogStatus.value = "Logs exported (${logs.length} chars)"
                }
            } catch (e: Exception) {
                crashLogStatus.value = userMessage("Settings", "Export failed", e)
            }
        }
    }

    // ==================== Data: JSON export / import ====================

    fun exportSessionsJson() {
        scope.launch {
            val path = FilePicker.saveFile("sessions-export.json", "JSON files", listOf("json"))
            if (path != null) {
                try {
                    val json = ExportImport.exportSessions(repo)
                    PlatformFile.writeText(path, json)
                    dataStatus.value = "Exported ${repo.sessions.value.size} sessions"
                } catch (e: Exception) {
                    dataStatus.value = userMessage("Data", "Export failed", e)
                }
            }
        }
    }

    fun importSessionsJson() {
        scope.launch {
            val path = FilePicker.openFile("JSON files", listOf("json"))
            if (path != null) {
                try {
                    // Check the file length BEFORE readText: the 50 MB
                    // guard inside ExportImport can only see the content
                    // once it is already in memory. size() == -1 means
                    // unknown, which falls through to the decode checks.
                    if (PlatformFile.size(path) > ExportImport.MAX_IMPORT_BYTES) {
                        dataStatus.value = "Import failed: file too large (max 50 MB)"
                        return@launch
                    }
                    val content = PlatformFile.readText(path)
                    val result = ExportImport.importSessionsDetailed(repo, content)
                    dataStatus.value =
                        if (result.error != null) "Import failed: ${result.error}"
                        else "Imported ${result.count} sessions"
                } catch (e: Exception) {
                    dataStatus.value = userMessage("Data", "Import failed", e)
                }
            }
        }
    }

    fun exportFullJournalJson() {
        scope.launch {
            val path = FilePicker.saveFile("nepenthe-journal.json", "JSON files", listOf("json"))
            if (path != null) {
                try {
                    val json = ExportImport.exportFullJournal(repo)
                    PlatformFile.writeText(path, json)
                    dataStatus.value = "Exported full journal backup (backup format; restore with Import backup)"
                } catch (e: Exception) {
                    dataStatus.value = userMessage("Data", "Full JSON export failed", e)
                }
            }
        }
    }

    fun importFullJournalJson() {
        scope.launch {
            val path = FilePicker.openFile("JSON files", listOf("json"))
            if (path != null) {
                try {
                    // Size check before readText (same as session import).
                    if (PlatformFile.size(path) > ExportImport.MAX_IMPORT_BYTES) {
                        dataStatus.value = "Import failed: file too large (max 50 MB)"
                        return@launch
                    }
                    val content = PlatformFile.readText(path)
                    val result = ExportImport.importFullJournal(repo, content)
                    dataStatus.value =
                        if (result.error != null) "Import failed: ${result.error}"
                        else "Imported full journal backup: ${result.sessions} sessions, " +
                            "${result.substances} substances, ${result.doses} doses"
                } catch (e: Exception) {
                    dataStatus.value = userMessage("Data", "Import failed", e)
                }
            }
        }
    }

    // ==================== Data: CSV / ZIP export ====================

    fun exportSessionsCsv() {
        scope.launch {
            val path = FilePicker.saveFile("sessions.csv", "CSV files", listOf("csv"))
            if (path != null) {
                try {
                    val csv = CsvExporter.exportSessionsCsv(repo)
                    PlatformFile.writeText(path, csv)
                    dataStatus.value = "Exported ${repo.sessions.value.size} sessions as CSV"
                } catch (e: Exception) {
                    dataStatus.value = userMessage("Data", "CSV export failed", e)
                }
            }
        }
    }

    fun exportDosesCsv() {
        scope.launch {
            val path = FilePicker.saveFile("doses.csv", "CSV files", listOf("csv"))
            if (path != null) {
                try {
                    val csv = CsvExporter.exportDosesCsv(repo)
                    PlatformFile.writeText(path, csv)
                    dataStatus.value = "Exported doses as CSV"
                } catch (e: Exception) {
                    dataStatus.value = userMessage("Data", "CSV export failed", e)
                }
            }
        }
    }

    fun exportSubstancesCsv() {
        scope.launch {
            val path = FilePicker.saveFile("substances.csv", "CSV files", listOf("csv"))
            if (path != null) {
                try {
                    val csv = CsvExporter.exportSubstancesCsv(repo)
                    PlatformFile.writeText(path, csv)
                    dataStatus.value = "Exported ${repo.substances.value.size} substances as CSV"
                } catch (e: Exception) {
                    dataStatus.value = userMessage("Data", "CSV export failed", e)
                }
            }
        }
    }

    fun exportZip() {
        scope.launch {
            val path = FilePicker.saveFile("nepenthe-export.zip", "ZIP archives", listOf("zip"))
            if (path != null) {
                try {
                    val count = ZipExporter.exportAll(repo as JournalRepository, path)
                    dataStatus.value = "Exported $count CSV files as zip"
                } catch (e: Exception) {
                    dataStatus.value = userMessage("Data", "ZIP export failed", e)
                }
            }
        }
    }

    // ==================== Data: snapshot backup ====================

    fun createBackup() {
        scope.launch {
            try {
                val store = JournalStore(repo as JournalRepository)
                store.save()
                dataStatus.value = "Backup saved to ${store.dataPath()}"
            } catch (e: Exception) {
                dataStatus.value = userMessage("Data", "Backup failed", e)
            }
        }
    }

    companion object {
        fun create(
            repo: IJournalRepository,
            scope: CoroutineScope,
        ): DataSettingsViewModel = DataSettingsViewModel(repo, scope)
    }
}
