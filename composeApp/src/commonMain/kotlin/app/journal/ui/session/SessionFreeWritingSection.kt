package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.journal.ui.components.SectionHeader

/** Free-form writing for the session body: no prompt, no structure, markdown kept as typed. */
@Composable
internal fun SessionFreeWritingSection(
    freeform: String,
    onFreeformChange: (String) -> Unit
) {
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
    SectionHeader("Free writing")
    Text(
        "Anything goes: notes, fragments, headings, bullet lists. Markdown reaches Obsidian unchanged.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = freeform,
        onValueChange = onFreeformChange,
        placeholder = { Text("Type anything...") },
        minLines = 8,
        modifier = Modifier.fillMaxWidth().testTag("session-freeform")
    )
}
