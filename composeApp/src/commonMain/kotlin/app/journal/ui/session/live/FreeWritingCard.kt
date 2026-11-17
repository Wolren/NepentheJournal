package app.journal.ui.session.live

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.ui.components.AppCard
import app.journal.ui.components.appCardPadding

/**
 * Scratchpad for a running session: type anything, no prompt and no structure.
 * The screen owns [value] so a recycled list row never drops a keystroke.
 */
@Composable
internal fun FreeWritingCard(
    value: String,
    onValueChange: (String) -> Unit,
    saving: Boolean,
) {
    AppCard(modifier = Modifier.fillMaxWidth(), contentPadding = appCardPadding(dense = true)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.EditNote, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "Free writing",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (value.isNotBlank()) {
                Text(
                    if (saving) "Saving..." else "Saved",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("Type anything. Markdown is kept as written.") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("live-freeform")
        )
    }
}
