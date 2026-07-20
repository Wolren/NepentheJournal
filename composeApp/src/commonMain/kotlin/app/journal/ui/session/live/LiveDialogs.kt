package app.journal.ui.session.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.ClassBasedWarning
import app.journal.data.InteractionWarningLevel
import app.journal.ui.components.*

@Composable
internal fun InteractionWarningsBanner(warnings: List<ClassBasedWarning>) {
    val dangerWarnings = warnings.filter { it.level == InteractionWarningLevel.DANGER }
    val cautionWarnings = warnings.filter { it.level == InteractionWarningLevel.CAUTION }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (dangerWarnings.isNotEmpty()) {
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFD32F2F).copy(alpha = 0.12f)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFD32F2F), modifier = Modifier.size(18.dp))
                        Text("Dangerous Combinations", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F))
                    }
                    dangerWarnings.forEach { w -> Text(w.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 24.dp, top = 4.dp)) }
                }
            }
        }
        if (cautionWarnings.isNotEmpty()) {
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800).copy(alpha = 0.12f)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(18.dp))
                        Text("Caution", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                    }
                    cautionWarnings.forEach { w -> Text(w.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 24.dp, top = 4.dp)) }
                }
            }
        }
    }
}

@Composable
internal fun LiveSessionEditDialog(
    title: String, setText: String, settingText: String, intention: String,
    onTitleChange: (String) -> Unit, onSetChange: (String) -> Unit,
    onSettingChange: (String) -> Unit, onIntentionChange: (String) -> Unit,
    onSave: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = onTitleChange, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = setText, onValueChange = onSetChange, label = { Text("Set (mindset)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = settingText, onValueChange = onSettingChange, label = { Text("Setting (environment)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = intention, onValueChange = onIntentionChange, label = { Text("Intention") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { AppTextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun CrisisResourcesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Emergency, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                Text("Get Help Now")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("If you are experiencing a medical emergency or need immediate support:", style = MaterialTheme.typography.bodyMedium)
                CrisisResourceCard("Emergency Services", "112 (EU) / 911 (US)", "Call immediately if someone is in physical danger.")
                CrisisResourceCard("Poison Control", "1-800-222-1222 (US)", "For overdoses, bad reactions, and interactions.")
                CrisisResourceCard("Fireside Project", "62-FIRESIDE (623-473-7433)", "Free psychedelic peer support line (US), 11am-11pm PT.")
                CrisisResourceCard("Suicide & Crisis Lifeline (US)", "988 (call or text)", "24/7 support for suicidal thoughts, self-harm, or crisis.")
            }
        },
        confirmButton = { AppTextButton(onClick = onDismiss) { Text("I understand") } }
    )
}

@Composable
private fun CrisisResourceCard(label: String, contact: String, detail: String) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(contact, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
