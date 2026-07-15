package app.journal.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.data.JournalRepository
import app.journal.data.JournalStore
import app.journal.data.DataInitializer
import app.journal.log.Log
import app.journal.log.collectLogs
import app.journal.model.SyncConfig
import app.journal.sync.*
import app.journal.ui.theme.*
import app.journal.util.CsvExporter
import app.journal.util.ExportImport
import app.journal.util.PlatformFile
import app.journal.util.ZipExporter
import app.journal.ui.components.*
import app.journal.util.FilePicker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(syncEngine: SyncEngine) {
    val repo = remember { JournalRepository.instance }
    val sessionCount by repo.totalSessionCount.collectAsState(initial = 0)
    val substanceCount by repo.totalSubstanceCount.collectAsState(initial = 0)

    val themeManager = remember { ThemeManager.instance }
    val themeConfig by themeManager.config.collectAsState()

    var themeExpanded by remember { mutableStateOf(false) }
    var editBaseTheme by remember(themeConfig) { mutableStateOf(themeConfig.baseTheme) }
    var editPrimary by remember(themeConfig) { mutableStateOf(themeConfig.primaryColor) }
    var editSecondary by remember(themeConfig) { mutableStateOf(themeConfig.secondaryColor) }
    var editTertiary by remember(themeConfig) { mutableStateOf(themeConfig.tertiaryColor) }
    var editBgColor by remember(themeConfig) { mutableStateOf<Long>(themeConfig.backgroundColor ?: 0xFF0E1511L) }
    var editSurfaceColor by remember(themeConfig) { mutableStateOf(themeConfig.surfaceColor ?: 0xFF16211AL) }
    var editBgImage by remember(themeConfig) { mutableStateOf(themeConfig.backgroundImagePath) }
    var editBgOpacity by remember(themeConfig) { mutableStateOf(0.5f) }
    var editCardStyle by remember(themeConfig) { mutableStateOf(themeConfig.cardStyle) }
    var editCornerRadius by remember(themeConfig) { mutableStateOf(themeConfig.cornerRadius) }
    var editFontScale by remember(themeConfig) { mutableStateOf(themeConfig.fontScale) }
    var editAnimationScale by remember(themeConfig) { mutableStateOf(themeConfig.animationScale) }

    fun applyTheme() {
        themeManager.update(ThemeConfig(
            baseTheme = editBaseTheme,
            primaryColor = editPrimary,
            secondaryColor = editSecondary,
            tertiaryColor = editTertiary,
            backgroundColor = editBgColor,
            surfaceColor = editSurfaceColor,
            backgroundImagePath = editBgImage?.takeIf { it.isNotBlank() },
            backgroundOpacity = editBgOpacity,
            cardStyle = editCardStyle,
            cornerRadius = editCornerRadius,
            fontScale = editFontScale,
            animationScale = editAnimationScale
        ))
    }

    val status by syncEngine.observeStatus().collectAsState(initial = SyncStatusSnapshot(
        isHosting = false, hostAddress = null,
        activeConnections = emptyList(), lastSyncAt = null,
        pendingConflicts = 0, lastError = null
    ))
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("4984") }
    var manualToken by remember { mutableStateOf("") }
    var continuousSync by remember { mutableStateOf(false) }
    var syncExpanded by remember { mutableStateOf(false) }
    var isStartingHost by remember { mutableStateOf(false) }
    var isStoppingHost by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var dataExpanded by remember { mutableStateOf(false) }
    var aboutExpanded by remember { mutableStateOf(false) }
    var legalExpanded by remember { mutableStateOf(false) }
    var privacyExpanded by remember { mutableStateOf(false) }
    var libraryExpanded by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf(listOf("Sync engine ready")) }
    var trustedDevices by remember { mutableStateOf(syncEngine.trustedDevices()) }
    var dataStatus by remember { mutableStateOf<String?>(null) }

    // Refresh trusted devices when hosting or connections change
    LaunchedEffect(status.pairedDeviceCount, status.isHosting) {
        trustedDevices = syncEngine.trustedDevices()
    }

    val isIpValid = manualHost.isBlank() || manualHost.matches(Regex("^[\\\\.\\\\d]+$"))
    val isPortValid = (manualPort.toIntOrNull() ?: 0) in 1..65535
    val hostError = if (manualHost.isNotBlank() && !isIpValid) "Invalid IP format" else null
    val portError = if (manualPort.isNotBlank() && !isPortValid) "Port must be 1-65535" else null

    fun userMessage(msg: String): String = when {
        msg.contains("Connection refused") -> "Device not reachable. Check IP and port."
        msg.contains("timed out") -> "Connection timed out. Device may be offline."
        msg.contains("Certificate pinning failed") -> "Device certificate changed. Re-pair required."
        msg.contains("Invalid or expired token") -> "Pairing token expired or wrong. Generate a new one."
        msg.contains("Authentication failed") -> "Sync auth failed. Try re-pairing."
        msg.contains("Not paired") -> "Not paired with this device. Enter a pairing token."
        msg.contains("keytool") || msg.contains("Certificate") -> "TLS setup failed. Restart the app."
        msg.contains("port") && msg.contains("available") -> "Port already in use. Try a different port."
        else -> msg
    }

    fun formatTimestamp(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 60 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> "${days / 7}w ago"
        }
    }

    var fetchStatus by remember { mutableStateOf<String?>(null) }
    var isFetching by remember { mutableStateOf(false) }
    var crashLogStatus by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ================ THEME ================
        item {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp).animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 2000f))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { themeExpanded = !themeExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Theme", style = MaterialTheme.typography.titleMedium)
                        }
                        AppTonalButton(onClick = { themeExpanded = !themeExpanded }) {
                            Text("Manage")
                        }
                    }
                    if (themeExpanded) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        // Base selection
                        Text("Base", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = editBaseTheme == BaseTheme.DARK,
                                onClick = {
                                    editBaseTheme = BaseTheme.DARK
                                    editPrimary = ThemeDefaults.Dark.primaryColor
                                    editSecondary = ThemeDefaults.Dark.secondaryColor
                                    editTertiary = ThemeDefaults.Dark.tertiaryColor
                                    editBgColor = 0xFF0E1511L
                                    editSurfaceColor = 0xFF16211AL
                                    applyTheme()
                                },
                                label = { Text("Dark") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = editBaseTheme == BaseTheme.LIGHT,
                                onClick = {
                                    editBaseTheme = BaseTheme.LIGHT
                                    editPrimary = ThemeDefaults.Light.primaryColor
                                    editSecondary = ThemeDefaults.Light.secondaryColor
                                    editTertiary = ThemeDefaults.Light.tertiaryColor
                                    editBgColor = 0xFFF3F8EFL
                                    editSurfaceColor = 0xFFFFFFFFL
                                    applyTheme()
                                },
                                label = { Text("Light") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = editBaseTheme == BaseTheme.SYSTEM,
                                onClick = { editBaseTheme = BaseTheme.SYSTEM; applyTheme() },
                                label = { Text("System") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = editBaseTheme == BaseTheme.CUSTOM,
                                onClick = { editBaseTheme = BaseTheme.CUSTOM },
                                label = { Text("Custom") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Customization only shows when Custom is selected
                        if (editBaseTheme == BaseTheme.CUSTOM) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            Text("Colors", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(4.dp))
                            ColorPickerField("Primary", editPrimary, onPick = { editPrimary = it; applyTheme() })
                            QuickSwatches(current = editPrimary, onPick = { editPrimary = it; applyTheme() })
                            ColorPickerField("Secondary", editSecondary, onPick = { editSecondary = it; applyTheme() })
                            QuickSwatches(current = editSecondary, onPick = { editSecondary = it; applyTheme() })
                            ColorPickerField("Tertiary", editTertiary, onPick = { editTertiary = it; applyTheme() })
                            QuickSwatches(current = editTertiary, onPick = { editTertiary = it; applyTheme() })
                            Spacer(Modifier.height(8.dp))
                            ColorPickerField("Background", editBgColor, onPick = { editBgColor = it; applyTheme() })
                            Spacer(Modifier.height(4.dp))
                            ColorPickerField("Surface", editSurfaceColor, onPick = { editSurfaceColor = it; applyTheme() })
                            Spacer(Modifier.height(12.dp))
                            Text("Background image", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = editBgImage ?: "",
                                onValueChange = { editBgImage = it; applyTheme() },
                                placeholder = { Text("Image URL or file path") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = if (editBgImage != null) {
                                    { AppIconButton(onClick = { editBgImage = null; applyTheme() }, icon = Icons.Default.Close, contentDescription = "Clear") }
                                } else null
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Opacity", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = editBgOpacity,
                                    onValueChange = { editBgOpacity = it; applyTheme() },
                                    modifier = Modifier.weight(1f).height(16.dp),
                                    valueRange = 0f..1f
                                )
                                Text("%.0f%%".format(editBgOpacity * 100), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Cards & shapes", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Style: ", style = MaterialTheme.typography.bodySmall)
                                CardStyle.entries.forEach { style ->
                                    FilterChip(selected = editCardStyle == style,
                                        onClick = { editCardStyle = style; applyTheme() },
                                        label = { Text(style.name.lowercase(), style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.padding(end = 4.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Corners: ", style = MaterialTheme.typography.bodySmall)
                                CornerRadius.entries.forEach { radius ->
                                    FilterChip(selected = editCornerRadius == radius,
                                        onClick = { editCornerRadius = radius; applyTheme() },
                                        label = { Text(radius.name.lowercase(), style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.padding(end = 4.dp))
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Typography", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Font scale", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(8.dp))
                                Slider(
                                    value = editFontScale,
                                    onValueChange = { editFontScale = it; applyTheme() },
                                    modifier = Modifier.weight(1f).height(16.dp),
                                    valueRange = 0.8f..1.3f,
                                    steps = 9
                                )
                                Text("%.0f%%".format(editFontScale * 100), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Animations", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Speed", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(8.dp))
                                Slider(
                                    value = editAnimationScale,
                                    onValueChange = { editAnimationScale = it; applyTheme() },
                                    modifier = Modifier.weight(1f).height(16.dp),
                                    valueRange = 0f..1f,
                                    steps = 4
                                )
                                val label = when {
                                    editAnimationScale == 0f -> "Off"
                                    editAnimationScale <= 0.25f -> "0.25×"
                                    editAnimationScale <= 0.5f -> "0.5×"
                                    editAnimationScale <= 0.75f -> "0.75×"
                                    else -> "1×"
                                }
                                Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                            }
                        }

                        // Bottom actions — only in Custom mode
                        if (editBaseTheme == BaseTheme.CUSTOM) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppButton(
                                    onClick = { themeExpanded = false },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Apply") }
                                AppTonalButton(
                                    onClick = {
                                        editBaseTheme = ThemeDefaults.Dark.baseTheme
                                        editPrimary = ThemeDefaults.Dark.primaryColor
                                        editSecondary = ThemeDefaults.Dark.secondaryColor
                                        editTertiary = ThemeDefaults.Dark.tertiaryColor
                                        editBgColor = 0xFF0E1511L
                                        editSurfaceColor = 0xFF16211AL
                                        editCardStyle = ThemeDefaults.Dark.cardStyle
                                        editCornerRadius = ThemeDefaults.Dark.cornerRadius
                                        editBgImage = null
                                        editBgOpacity = 0.3f
                                        editFontScale = 1.0f
                                        editAnimationScale = 1.0f
                                        applyTheme()
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Reset") }
                            }
                        }
                    }
                }
            }
        }

        // ================ PREFERENCES ================
        item {
            val prefExpanded = remember { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { prefExpanded.value = !prefExpanded.value },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Preferences", style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(
                            if (prefExpanded.value) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (prefExpanded.value) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        val useShulgin by repo.useShulginRating.collectAsState()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Rating scale", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (useShulgin) "Shulgin scale (+/-, +, ++, +++, ++++)"
                                    else "Numeric scale (1-10)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = useShulgin,
                                onCheckedChange = { enabled ->
                                    repo.setShulginRating(enabled)
                                    val store = JournalStore(repo)
                                    store.save()
                                }
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        val showTrend by repo.showSessionsTrendChart.collectAsState()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sessions per week chart",
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (showTrend) "Shows weekly trend on Dashboard"
                                    else "Hidden by default",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = showTrend,
                                onCheckedChange = { enabled ->
                                    repo.setShowSessionsTrendChart(enabled)
                                    val store = JournalStore(repo)
                                    store.save()
                                }
                            )
                        }
                    }
                }
            }
        }

        // ================ DATA ================
        item {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp).animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 2000f))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { dataExpanded = !dataExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Data", style = MaterialTheme.typography.titleMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$sessionCount sessions | $substanceCount substances",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp))
                            Icon(
                                if (dataExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (dataExpanded) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("Export / Import", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppOutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val path = FilePicker.saveFile("sessions-export.json", "JSON files", listOf("json"))
                                        if (path != null) {
                                            try {
                                                val json = ExportImport.exportSessions(repo)
                                                PlatformFile.writeText(path, json)
                                                dataStatus = "Exported ${repo.sessions.value.size} sessions"
                                            } catch (e: Exception) {
                                                dataStatus = "Export failed: ${e.message}"
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Export", maxLines = 1)
                            }
                            AppOutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val path = FilePicker.openFile("JSON files", listOf("json"))
                                        if (path != null) {
                                            try {
                                                val content = PlatformFile.readText(path)
                                                val count = ExportImport.importSessions(repo, content)
                                                dataStatus = "Imported $count sessions"
                                            } catch (e: Exception) {
                                                dataStatus = "Import failed: ${e.message}"
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Import", maxLines = 1)
                            }
                        }
                        val statusMsg = dataStatus
                        if (statusMsg != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(statusMsg, style = MaterialTheme.typography.labelSmall,
                                color = if (statusMsg.startsWith("Import") || statusMsg.startsWith("Export"))
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("CSV Export (analysis)", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        Text("One row per entity, R/Pandas-friendly format.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppOutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val path = FilePicker.saveFile("sessions.csv", "CSV files", listOf("csv"))
                                        if (path != null) {
                                            try {
                                                val csv = CsvExporter.exportSessionsCsv(repo)
                                                PlatformFile.writeText(path, csv)
                                                dataStatus = "Exported ${repo.sessions.value.size} sessions as CSV"
                                            } catch (e: Exception) {
                                                dataStatus = "CSV export failed: ${e.message}"
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Sessions CSV", maxLines = 1)
                            }
                            AppOutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val path = FilePicker.saveFile("doses.csv", "CSV files", listOf("csv"))
                                        if (path != null) {
                                            try {
                                                val csv = CsvExporter.exportDosesCsv(repo)
                                                PlatformFile.writeText(path, csv)
                                                dataStatus = "Exported doses as CSV"
                                            } catch (e: Exception) {
                                                dataStatus = "CSV export failed: ${e.message}"
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Doses CSV", maxLines = 1)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppOutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val path = FilePicker.saveFile("substances.csv", "CSV files", listOf("csv"))
                                        if (path != null) {
                                            try {
                                                val csv = CsvExporter.exportSubstancesCsv(repo)
                                                PlatformFile.writeText(path, csv)
                                                dataStatus = "Exported ${repo.substances.value.size} substances as CSV"
                                            } catch (e: Exception) {
                                                dataStatus = "CSV export failed: ${e.message}"
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Substances CSV", maxLines = 1)
                            }
                            AppOutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val path = FilePicker.saveFile("nepenthe-export.zip", "ZIP archives", listOf("zip"))
                                        if (path != null) {
                                            try {
                                                val count = ZipExporter.exportAll(repo, path)
                                                dataStatus = "Exported $count CSV files as zip"
                                            } catch (e: Exception) {
                                                dataStatus = "ZIP export failed: ${e.message}"
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("All (Zip)", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        // ================ OBSIDIAN VAULT ================
        item { ObsidianSettingsCard() }

        // ================ DEVICE SYNC ================
        item {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp).animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 2000f))) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Device Sync", style = MaterialTheme.typography.titleMedium)
                        }
                        AppTonalButton(onClick = { syncExpanded = !syncExpanded }) {
                            Text(if (syncExpanded) "Hide" else "Manage")
                        }
                    }
                    if (syncExpanded) {
                        Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))

                        // ---- Status overview ----
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column {
                                Text("Active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(if (status.isHosting) Icons.Default.Wifi else Icons.Default.WifiOff, null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (status.isHosting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (status.isHosting) "Active" else "Off",
                                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                            Column {
                                Text("Paired", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${status.pairedDeviceCount}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text("Last Sync", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(status.lastSyncAt?.let { formatTimestamp(it) } ?: "Never",
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text("Conflicts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${status.pendingConflicts}",
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                    color = if (status.pendingConflicts > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                        }

                        Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))

                        // ---- Hosting ----
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text("Hosting", style = MaterialTheme.typography.titleMedium)
                            }
                            if (isStartingHost || isStoppingHost) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                AppTonalButton(
                                    onClick = {
                                        scope.launch {
                                            if (status.isHosting) {
                                                isStoppingHost = true
                                                try {
                                                    syncEngine.stopHosting()
                                                    logLines = listOf("Stopped hosting") + logLines
                                                } catch (e: Exception) {
                                                    logLines = listOf("Stop failed: ${userMessage(e.message ?: "")}") + logLines
                                                } finally { isStoppingHost = false }
                                            } else {
                                                isStartingHost = true
                                                try {
                                                    val cfg = SyncConfig(
                                                        id = "config:local", createdAt = 0L, updatedAt = 0L, deviceOrigin = "desktop",
                                                        deviceId = "desktop-main", displayName = "Windows Desktop",
                                                        listenerPort = manualPort.toIntOrNull() ?: 4985, continuousSync = continuousSync, enableDeltaSync = true
                                                    )
                                                    syncEngine.startHosting(cfg).fold(
                                                        onSuccess = { logLines = listOf("Hosting on port ${it.port}") + logLines },
                                                        onFailure = { logLines = listOf("Host start failed: ${userMessage(it.message ?: "")}") + logLines }
                                                    )
                                                } catch (e: Exception) {
                                                    logLines = listOf("Error: ${userMessage(e.message ?: "")}") + logLines
                                                } finally { isStartingHost = false }
                                            }
                                        }
                                    },
                                    colors = if (status.isHosting) ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ) else null
                                ) {
                                    Icon(if (status.isHosting) Icons.Default.Cancel else Icons.Default.Wifi, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (status.isHosting) "Stop" else "Start")
                                }
                            }
                        }

                        if (status.isHosting) {
                            Spacer(Modifier.height(8.dp))
                            Text("Other devices connect to: ${status.hostAddress ?: "..."}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }

                        // ---- Pairing token ----
                        val pairingToken = status.pairingToken
                        if (status.isHosting && pairingToken != null) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Pairing Token", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Spacer(Modifier.height(4.dp))
                                        Text(pairingToken, style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold, letterSpacing = 8.sp,
                                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text("Enter this on the device you want to pair",
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                    }
                                    AppIconButton(onClick = {
                                        clipboard.setText(AnnotatedString(pairingToken))
                                        logLines = listOf("Token copied to clipboard") + logLines
                                    }, icon = Icons.Default.ContentCopy, contentDescription = "Copy token",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }

                        // ---- Continuous sync toggle ----
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Continuous sync", style = MaterialTheme.typography.bodyMedium)
                                Text("Automatically sync with paired devices", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = continuousSync, onCheckedChange = { continuousSync = it })
                        }

                        Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))

                        // ---- Connect to device ----
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Text("Connect to Device", style = MaterialTheme.typography.titleMedium)
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = manualHost, onValueChange = { manualHost = it },
                                placeholder = { Text("IP address") }, singleLine = true,
                                isError = hostError != null, supportingText = hostError?.let { { Text(it) } },
                                modifier = Modifier.weight(2f))
                            OutlinedTextField(value = manualPort, onValueChange = { manualPort = it.filter { c -> c.isDigit() }.take(5) },
                                placeholder = { Text("Port") }, singleLine = true,
                                isError = portError != null, supportingText = portError?.let { { Text(it) } },
                                modifier = Modifier.weight(1f))
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = manualToken, onValueChange = { manualToken = it.uppercase().take(6) },
                            placeholder = { Text("Pairing token from host") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text(if (manualToken.isEmpty()) "Required for first-time pairing" else "${manualToken.length}/6 characters") })

                        Spacer(Modifier.height(12.dp))
                        if (isSyncing) {
                            Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        } else {
                            AppButton(onClick = {
                                scope.launch {
                                    val host = manualHost.trim()
                                    val port = manualPort.toIntOrNull()
                                    if (host.isEmpty()) { logLines = listOf("Enter a host IP address") + logLines; return@launch }
                                    if (port == null || port !in 1..65535) { logLines = listOf("Enter a valid port (1-65535)") + logLines; return@launch }
                                    isSyncing = true
                                    try {
                                        val peer = DiscoveredPeer(deviceId = null, displayName = host, host = host, port = port,
                                            isTrusted = false, fingerprint = null, pairingToken = manualToken.ifBlank { null })
                                        syncEngine.syncWith(peer, continuousSync).fold(
                                            onSuccess = { logLines = listOf("Connected to $host:$port") + logLines },
                                            onFailure = { logLines = listOf("Sync failed: ${userMessage(it.message ?: "")}") + logLines }
                                        )
                                    } catch (e: Exception) {
                                        logLines = listOf("Error: ${userMessage(e.message ?: "")}") + logLines
                                    } finally { isSyncing = false }
                                }
                            }, enabled = manualHost.isNotBlank() && isPortValid && isIpValid,
                                icon = Icons.Default.Sync, modifier = Modifier.fillMaxWidth()) {
                                Text("Sync Now")
                            }
                        }

                        // ---- Trusted devices ----
                        if (trustedDevices.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text("Trusted Devices", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(4.dp))
                            trustedDevices.forEach { device ->
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.Person, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(device.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("Paired ${formatTimestamp(device.pairedAt)}" +
                                                (device.lastSeenAt?.let { " - seen ${formatTimestamp(it)}" } ?: ""),
                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    AppIconButton(onClick = {
                                        scope.launch {
                                            syncEngine.revokeTrustedDevice(device.deviceId)
                                            trustedDevices = syncEngine.trustedDevices()
                                            logLines = listOf("Revoked ${device.displayName}") + logLines
                                        }
                                    }, icon = Icons.Default.LinkOff, contentDescription = "Revoke ${device.displayName}", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        // ---- Event log ----
                        Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                        Text("Event Log", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        logLines.take(15).forEach { line ->
                            val prefix = when {
                                line.startsWith("+") -> '+'
                                line.startsWith("!") -> '!'
                                else -> '-'
                            }
                            val displayText = line.removePrefix("+").removePrefix("!").removePrefix("-")
                            val color = when (prefix) {
                                '+' -> MaterialTheme.colorScheme.primary
                                '!' -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 1.dp)) {
                                Text("$prefix", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = color)
                                Text(displayText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (logLines.isEmpty()) {
                            Text("No events yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // ================ SUBSTANCE LIBRARY ================
        item {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp).animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 2000f))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { libraryExpanded = !libraryExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Substance library", style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(
                            if (libraryExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (libraryExpanded) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("Reloads the default PsychonautWiki substance database from the bundled seed resource, overwriting any changes made to built-in substances. User-created substances and all journal entries (sessions, doses, notes) are preserved.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Substance data sourced from PsychonautWiki + PubChem + TripSit + Wikidata. Run scripts/matrix_build.py to refresh the seed.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton(
                                onClick = {
                                    scope.launch {
                                        isFetching = true
                                        fetchStatus = "Reloading seed data..."
                                        try {
                                            DataInitializer.reloadDefaultSubstances(repo)
                                            fetchStatus = "Reloaded ${repo.substances.value.size} substances from seed"
                                        } catch (e: Exception) {
                                            fetchStatus = "Reload failed: ${e.message}"
                                        }
                                        isFetching = false
                                    }
                                },
                                enabled = !isFetching,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reset to defaults")
                            }
                        }
                        val fetchMsg = fetchStatus
                        if (fetchMsg != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(fetchMsg, style = MaterialTheme.typography.labelSmall,
                                color = if (fetchMsg.startsWith("Loaded")) MaterialTheme.colorScheme.primary
                                else if (fetchMsg.startsWith("Fetch failed")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ================ ABOUT ================
        item {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp).animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 2000f))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { aboutExpanded = !aboutExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                            Text("About", style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(
                            if (aboutExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (aboutExpanded) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("Nepenthe Journal v0.1.0")
                        Text("Offline-first psychoactive substance session tracker",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("by Wolren", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("GNU GPLv3 License", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text("Compose Multiplatform + Ktor (P2P sync)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Derived from PsychonautWiki Journal by Isaak Hanimann",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("All data stored locally on device. No cloud, no accounts, no tracking.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Spacer(Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Pledge", style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.height(2.dp))
                                SelectableText(
                                    text = "Your data is yours. This app will never have ads, " +
                                    "subscriptions, or telemetry. No accounts, no cloud, " +
                                    "no tracking. Always.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val uriHandler = LocalUriHandler.current
                            AppOutlinedButton(
                                onClick = { uriHandler.openUri("https://github.com/Wolren/NepentheJournal") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Source", maxLines = 1)
                            }
                            AppOutlinedButton(
                                onClick = { uriHandler.openUri("https://ko-fi.com/wolren") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FavoriteBorder, null, modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                                Spacer(Modifier.width(4.dp))
                                Text("Ko-fi", maxLines = 1, color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                            }
                        }
                    }
                }
            }
        }

        // ================ LEGAL ================
        item {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp).animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 2000f))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { legalExpanded = !legalExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dangerous, null, tint = MaterialTheme.colorScheme.error)
                            Text("Legal", style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(
                            if (legalExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (legalExpanded) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        Text("Medical disclaimer", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        SelectableText(
                            text = "This app is not a medical device and does not diagnose, treat, cure, " +
                            "or prevent any medical condition. The substance reference data is sourced " +
                            "from PsychonautWiki and is provided for harm reduction and informational " +
                            "purposes only.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )

                        Spacer(Modifier.height(12.dp))

                        Text("Healthcare reminder", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        SelectableText(
                            text = "If you have concerns about your health or substance use, consult " +
                            "a qualified healthcare professional. In an emergency, call emergency " +
                            "services immediately (EU: 112, US: 911, UK: 999).",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )

                        Spacer(Modifier.height(12.dp))

                        Text("License", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        SelectableText(
                            text = "Nepenthe Journal is free software: you can redistribute it and/or modify " +
                            "it under the terms of the GNU General Public License as published by " +
                            "the Free Software Foundation, either version 3 of the License, or " +
                            "(at your option) any later version.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }

        // ================ PRIVACY ================
        item {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp).animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 2000f))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { privacyExpanded = !privacyExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Privacy", style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(
                            if (privacyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (privacyExpanded) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        Text("Privacy & data", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        SelectableText(
                            text = "All journal data is stored locally on your device. " +
                            "There is no connected server — nothing is uploaded, synced, or sent " +
                            "without your explicit action.\n\n" +
                            "The app contains no analytics, no telemetry, and no tracking software. " +
                            "No data is collected or transmitted automatically.\n\n" +
                            "You can manually export your full journal data at any time via " +
                            "\"Settings > Data (JSON, CSV, or ZIP)\". Sharing those exports is " +
                            "entirely at your discretion — nothing leaves your device until " +
                            "you explicitly choose to export and share it.\n\n" +
                            "Optional P2P sync transmits data directly between your own devices " +
                            "over your local network only. No data passes through any external relay.\n\n" +
                            "Full privacy policy: PRIVACY.md in the app repository.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )

                        Spacer(Modifier.height(12.dp))

                        Text("Business model pledge", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        SelectableText(
                            text = "This app will never include:\n\n" +
                            "  - Advertisements of any kind\n" +
                            "  - Subscription tiers or paid features\n" +
                            "  - Telemetry, analytics, or crash reporting\n" +
                            "  - Account requirements or cloud dependency\n\n" +
                            "All functionality is and will always be free. " +
                            "This is a firm commitment, not a current-state description.\n\n" +
                            "You own your data. Manual export is always available — " +
                            "nothing leaves your device unless you explicitly choose to share it.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }

        // ================ DEVELOPER ================
        item {
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
                    AppOutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    app.journal.data.DataInitializer.resetWithTestData(repo)
                                    dataStatus = "Test data loaded (${repo.sessions.value.size} sessions, ${repo.substances.value.size} substances)"
                                } catch (e: Exception) {
                                    dataStatus = "Test data failed: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Load test data")
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Diagnostics", style = MaterialTheme.typography.labelLarge)
                    Text("Export the app's rolling crash log for debugging. Logs are stored locally and never sent anywhere.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    AppOutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val appDir = PlatformFile.dataDir()
                                    val logs = collectLogs(appDir)
                                    val path = FilePicker.saveFile(
                                        "nepenthe-crash-${app.journal.util.currentTimeMillis()}.log",
                                        "Log files", listOf("log", "txt")
                                    )
                                    if (path != null) {
                                        PlatformFile.writeText(path, logs)
                                        Log.withTag("Settings").i { "Crash logs exported to $path" }
                                        crashLogStatus = "Logs exported (${logs.length} chars)"
                                    }
                                } catch (e: Exception) {
                                    crashLogStatus = "Export failed: ${e.message}"
                                    Log.withTag("Settings").e(e) { "Crash log export failed" }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.BugReport, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export crash logs")
                    }
                    val crashMsg = crashLogStatus
                    if (crashMsg != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(crashMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (crashMsg.startsWith("Export") || crashMsg.startsWith("Logs"))
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // ================ FOOTER NOTICE ================
        item {
            Text(
                "No ads. No subscriptions. No tracking. Your data is yours.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun QuickSwatches(current: Long, onPick: (Long) -> Unit) {
    val swatches = listOf(
        0xFF4CAF50L, 0xFF2E7D32L, 0xFF81C784L, 0xFFAED581L, 0xFF1B5E20L,
        0xFF1565C0L, 0xFF0D47A1L, 0xFF7B1FA2L, 0xFF4A148CL, 0xFFFFCC80L
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        swatches.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { swatch ->
                    val isSelected = current == swatch
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(swatch))
                            .clickable { onPick(swatch) }
                            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                            .then(Modifier.border(1.dp, Color.Black.copy(alpha = 0.2f), CircleShape)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) Icon(Icons.Default.Check, null,
                            tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
