package app.journal.ui.settings.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.journal.ui.components.*

@Composable
internal fun AboutCardContent(aboutExpanded: Boolean, onToggle: () -> Unit) {
    CollapsibleSettingsCard(expanded = aboutExpanded, onToggle = onToggle,
        icon = Icons.Default.Info, title = "About"
    ) {
        Text("Nepenthe Journal v0.1.0")
        Text("Offline-first psychoactive substance session tracker",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("by Wolren", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("GNU GPLv3 License", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text("Compose Multiplatform + Ktor (P2P sync)",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Derived from PsychonautWiki Journal by Isaak Hanimann",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("All data stored locally on device. No cloud, no accounts, no tracking.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("Pledge", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(2.dp))
                SelectableText(text = "Your data is yours. This app will never have ads, subscriptions, or telemetry. No accounts, no cloud, no tracking. Always.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val uriHandler = LocalUriHandler.current
            AppOutlinedButton(onClick = { uriHandler.openUri("https://github.com/Wolren/NepentheJournal") },
                modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("Source", maxLines = 1)
            }
            AppOutlinedButton(onClick = { uriHandler.openUri("https://ko-fi.com/wolren") },
                modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FavoriteBorder, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                Spacer(Modifier.width(4.dp)); Text("Ko-fi", maxLines = 1, color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
internal fun LegalCardContent(legalExpanded: Boolean, onToggle: () -> Unit) {
    CollapsibleSettingsCard(expanded = legalExpanded, onToggle = onToggle,
        icon = Icons.Default.Dangerous, title = "Legal",
        modifier = Modifier
    ) {
        Text("Medical disclaimer", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        SelectableText(text = "This app is not a medical device and does not diagnose, treat, cure, or prevent any medical condition. The substance reference data is sourced from PsychonautWiki and is provided for harm reduction and informational purposes only.",
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
        Spacer(Modifier.height(12.dp))
        Text("Healthcare reminder", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        SelectableText(text = "If you have concerns about your health or substance use, consult a qualified healthcare professional. In an emergency, call emergency services immediately (EU: 112, US: 911, UK: 999).",
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
        Spacer(Modifier.height(12.dp))
        Text("License", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        SelectableText(text = "Nepenthe Journal is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.",
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
    }
}

@Composable
internal fun PrivacyCardContent(privacyExpanded: Boolean, onToggle: () -> Unit) {
    CollapsibleSettingsCard(expanded = privacyExpanded, onToggle = onToggle,
        icon = Icons.Default.Shield, title = "Privacy"
    ) {
        Text("Privacy and data", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        SelectableText(text = "All journal data is stored locally on your device. There is no connected server: nothing is uploaded, synced, or sent without your explicit action.\n\nThe app contains no analytics, no telemetry, and no tracking software. No data is collected or transmitted automatically.\n\nYou can manually export your full journal data at any time via \"Settings > Data (JSON, CSV, or ZIP)\". Sharing those exports is entirely at your discretion.\n\nOptional P2P sync transmits data directly between your own devices over your local network only. No data passes through any external relay.\n\nEverything is free, forever: no ads, no subscriptions, no accounts.\n\nFull privacy policy: PRIVACY.md in the app repository.",
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
    }
}

@Composable
internal fun DeveloperCardContent(vm: app.journal.ui.settings.DataSettingsViewModel) {
    val crashLogStatus by vm.crashLogStatus.collectAsState()
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, null, tint = MaterialTheme.colorScheme.primary)
                Text("Developer", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text("Load demo/test data with varied sessions, substance combos, and timeline events for UI debugging.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            AppOutlinedButton(onClick = { vm.resetWithTestData() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Load test data")
            }
            Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
            Text("Diagnostics", style = MaterialTheme.typography.labelLarge)
            Text("Export the app's rolling crash log for debugging.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            AppOutlinedButton(onClick = { vm.exportCrashLogs() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.BugReport, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Export crash logs")
            }
            crashLogStatus?.let { msg ->
                Spacer(Modifier.height(4.dp))
                Text(msg, style = MaterialTheme.typography.labelSmall,
                    color = if (msg.startsWith("Logs exported")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}
