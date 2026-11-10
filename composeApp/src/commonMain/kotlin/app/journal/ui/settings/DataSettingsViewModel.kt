package app.journal.ui.settings

import app.journal.data.DataInitializer
import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
import app.journal.data.JournalStore
import app.journal.export.CsvExporter
import app.journal.export.EncryptedBackup
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
    val isFetching = MutableStateFlow(false)

    val fetchStatus = MutableStateFlow<String?>(null)

    val dataStatus = MutableStateFlow<String?>(null)

    val crashLogStatus = MutableStateFlow<String?>(null)


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
                }            }
        }
    }

    fun importFullJournalJson() {
        scope.launch {
            val path = FilePicker.openFile("JSON files", listOf("json"))
            if (path != null) {
                try {
                    if (PlatformFile.size(path) > ExportImport.MAX_IMPORT_BYTES) {
                        dataStatus.value = "Import failed: file too large (max 50 MB)"
                        return@launch
                    }
                    val content = PlatformFile.readText(path)
                    val result = ExportImport.importFullJournal(repo, content)
                    dataStatus.value =
                        if (result.error != null) "Import failed: ${result.error}"
                        else "Imported full journal backup: ${result.sessions} sessions, " +
                            "${result.substances} substances, ${result.doses} doses, " +
                            "${result.medications} medications"
                } catch (e: Exception) {
                    dataStatus.value = userMessage("Data", "Import failed", e)
                }
            }
        }
    }

    /** Writes a zstd-compressed, passphrase-sealed backup that is safe to store off-device. */
    fun exportEncryptedBackup(passphrase: CharArray) {
        scope.launch {
            if (!EncryptedBackup.isSupported) {
                dataStatus.value = "Encrypted backups are not available on this platform yet"
                return@launch
            }
            val name = "nepenthe-backup-${currentTimeMillis()}.${EncryptedBackup.EXTENSION}"
            val path = FilePicker.saveFile(name, "Encrypted backup", listOf(EncryptedBackup.EXTENSION))
            if (path == null) return@launch
            try {
                val json = ExportImport.exportFullJournal(repo)
                val sealed = EncryptedBackup.seal(json, passphrase)
                PlatformFile.writeBytes(path, sealed)
                val percent = if (json.isEmpty()) 0 else sealed.size * 100 / json.length
                dataStatus.value =
                    "Encrypted backup written (${sealed.size / 1024} KB, $percent% of the JSON size)"
            } catch (e: Exception) {
                dataStatus.value = userMessage("Data", "Encrypted backup failed", e)
            }
        }
    }

    /** Restores a sealed backup; a wrong passphrase fails the tag check before any data is touched. */
    fun importEncryptedBackup(passphrase: CharArray) {
        scope.launch {
            if (!EncryptedBackup.isSupported) {
                dataStatus.value = "Encrypted backups are not available on this platform yet"
                return@launch
            }
            val path = FilePicker.openFile("Encrypted backup", listOf(EncryptedBackup.EXTENSION))
            if (path == null) return@launch
            try {
                if (PlatformFile.size(path) > ExportImport.MAX_IMPORT_BYTES) {
                    dataStatus.value = "Restore failed: file too large (max 50 MB)"
                    return@launch
                }
                val json = EncryptedBackup.open(PlatformFile.readBytes(path), passphrase)
                val result = ExportImport.importFullJournal(repo, json)
                dataStatus.value = if (result.error != null) "Restore failed: ${result.error}"
                else "Restored encrypted backup: ${result.sessions} sessions, " +
                    "${result.substances} substances, ${result.doses} doses"
            } catch (e: Exception) {
                dataStatus.value = "Restore failed: wrong passphrase or damaged file"
            }
        }
    }


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
