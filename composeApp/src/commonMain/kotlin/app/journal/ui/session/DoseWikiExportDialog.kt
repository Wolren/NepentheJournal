package app.journal.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.data.IJournalRepository
import app.journal.export.DoseWikiTripReport
import app.journal.export.buildDoseWikiReport
import app.journal.export.check
import app.journal.export.toJsonString
import app.journal.util.FilePicker
import app.journal.util.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * dose.wiki export dialog. Shows the exact consent and age wording from the
 * TR-1 spec beside the checkboxes, lists validation errors and warnings, and
 * writes the canonical trip report JSON. Nothing is posted anywhere: the file
 * is the handoff, for the sandbox or the manual form.
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

    var consent by remember { mutableStateOf(false) }
    var ageOk by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(person?.contactEmail ?: "") }
    var mayContact by remember { mutableStateOf(person?.mayContact == true) }

    fun preview(withConsent: Boolean, withAge: Boolean) = buildDoseWikiReport(
        session = session,
        person = person?.copy(
            contactEmail = email.trim().ifEmpty { null },
            mayContact = mayContact
        ),
        doses = doses,
        substancesById = substancesById,
        notes = notes,
        events = events,
        publishConsent = withConsent,
        ageConfirmed = withAge
    )

    val check = remember(consent, ageOk, email, mayContact) {
        preview(consent, ageOk).check()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export trip report") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Canonical dose.wiki format. Save the file, then send it to the " +
                        "sandbox or paste it into the submission form.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (check.errors.isNotEmpty()) {
                    check.errors.forEach {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (check.warnings.isNotEmpty()) {
                    check.warnings.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(checked = consent, onCheckedChange = { consent = it })
                    Spacer(Modifier.width(8.dp))
                    Text(DoseWikiTripReport.CONSENT_TEXT, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(checked = ageOk, onCheckedChange = { ageOk = it })
                    Spacer(Modifier.width(8.dp))
                    Text(DoseWikiTripReport.AGE_TEXT, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Contact email (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = mayContact, onCheckedChange = { mayContact = it })
                    Text(DoseWikiTripReport.CONTACT_TEXT, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = check.ok,
                onClick = {
                    scope.launch {
                        try {
                            val report = preview(consent, ageOk)
                            val finalCheck = report.check()
                            if (!finalCheck.ok) {
                                onStatus("Export blocked: ${finalCheck.errors.first()}")
                                return@launch
                            }
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
                            onStatus("Export failed: ${e.message}")
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
