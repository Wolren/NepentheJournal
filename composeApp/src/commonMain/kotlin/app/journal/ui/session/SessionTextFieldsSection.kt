package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SessionTextFieldsSection(
    set: String, onSetChange: (String) -> Unit,
    setting: String, onSettingChange: (String) -> Unit,
    intention: String, onIntentionChange: (String) -> Unit,
    outcome: String, onOutcomeChange: (String) -> Unit,
    notes: String, onNotesChange: (String) -> Unit
) {
    OutlinedTextField(
        value = set,
        onValueChange = onSetChange,
        label = { Text("Set (mindset)") },
        placeholder = { Text("Your mental state before the session...") },
        minLines = 2, maxLines = 4,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = setting,
        onValueChange = onSettingChange,
        label = { Text("Setting (environment)") },
        placeholder = { Text("Location, atmosphere, company...") },
        minLines = 2, maxLines = 4,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = intention,
        onValueChange = onIntentionChange,
        label = { Text("Intention") },
        placeholder = { Text("Why are you having this session?") },
        minLines = 2, maxLines = 4,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = outcome,
        onValueChange = onOutcomeChange,
        label = { Text("Outcome") },
        placeholder = { Text("What happened? Insights, reflections...") },
        minLines = 3, maxLines = 6,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = notes,
        onValueChange = onNotesChange,
        label = { Text("Notes") },
        placeholder = { Text("Freeform notes, observations...") },
        minLines = 3, maxLines = 8,
        modifier = Modifier.fillMaxWidth()
    )
}
