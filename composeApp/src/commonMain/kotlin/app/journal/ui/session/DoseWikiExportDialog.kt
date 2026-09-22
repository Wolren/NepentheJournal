package app.journal.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.data.IJournalRepository
import app.journal.export.DoseWikiTripReport
import app.journal.export.buildDoseWikiReport
import app.journal.export.check
import app.journal.export.toJsonString
import app.journal.ui.components.userMessage
import app.journal.util.FilePicker
import app.journal.util.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * dose.wiki export dialog. The app is offline, so this only writes the
 * canonical trip report file. Consent and age confirmation happen on
 * dose.wiki when the file is uploaded, so the dialog asks for neither:
 * the flags stay false in the file and the site collects them.
 * Only content problems (title, substance) block the export.
 */
@Composable
fun DoseWikiExportDialog(
    repo: IJournalRepository,
    sessionId: String,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit
) {
    val session = remember(sessionId) { repo.getSession(sessionId) }
    if (session == null) { onDismiss(); return }
    val person = remember(session) { session.personId?.let { repo.getPerson(it) } }
    val doses = remember(sessionId) { repo.dosesForSession(sessionId) }
    val notes = remember(sessionId) { repo.notesForSession(sessionId) }
    val events = remember(sessionId) { repo.eventsForSession(sessionId) }
    val substancesById = remember {
        repo.substances.value.associateBy { it.id }
    }

    var email by remember { mutableStateOf(person?.contactEmail ?: "") }

    fun preview() = buildDoseWikiReport(
        session = session,
        person = person?.copy(
            contactEmail = email.trim().ifEmpty { null }
        ),
        doses = doses,
        substancesById = substancesById,
        notes = notes,
        events = events,
        publishConsent = false,
        ageConfirmed = false
    )

    val check = remember(email) { preview().check() }
    // Consent and age are collected on the site, so only content errors block.
    val contentErrors = check.errors.filter {
        it != DoseWikiTripReport.ERR_CONSENT && it != DoseWikiTripReport.ERR_AGE
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export trip report") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Saves the canonical dose.wiki file. Consent and age " +
                        "confirmation happen on dose.wiki when you upload it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (contentErrors.isNotEmpty()) {
                    contentErrors.forEach {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (check.warnings.isNotEmpty()) {
                    check.warnings.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Contact email (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = contentErrors.isEmpty(),
                onClick = {
                    scope.launch {
                        try {
                            val report = preview()
                            val slug = session.title.trim().lowercase()
                                .replace(Regex("[^a-z0-9]+"), "-").trim('-')
                                .ifEmpty { "untitled-report" }
                            val path = FilePicker.saveFile(
                                "trip-report-$slug.json", "JSON files", listOf("json")
                            )
                            if (path != null) {
                                PlatformFile.writeText(path, report.toJsonString())
                                onStatus("Trip report exported")
                            }
                        } catch (e: Exception) {
                            onStatus(userMessage("DoseWiki", "Export failed", e))
                        } finally {
                            onDismiss()
                        }
                    }
                }
            ) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
