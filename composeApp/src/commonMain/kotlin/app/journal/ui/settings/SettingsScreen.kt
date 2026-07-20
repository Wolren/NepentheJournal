package app.journal.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.journal.sync.*
import app.journal.ui.theme.*
import app.journal.ui.components.*
import app.journal.util.isDesktopPlatform
import app.journal.util.PlatformFile
import kotlinx.coroutines.launch
import app.journal.ui.settings.detail.*

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
            baseTheme = editBaseTheme, primaryColor = editPrimary, secondaryColor = editSecondary,
            tertiaryColor = editTertiary, backgroundColor = editBgColor, surfaceColor = editSurfaceColor,
            backgroundImagePath = editBgImage?.takeIf { it.isNotBlank() }, backgroundOpacity = editBgOpacity,
            cardStyle = editCardStyle, cornerRadius = editCornerRadius, fontScale = editFontScale,
            animationScale = editAnimationScale
        ))
    }

    val status by syncEngine.observeStatus().collectAsState(initial = SyncStatusSnapshot(
        isHosting = false, hostAddress = null, activeConnections = emptyList(), lastSyncAt = null,
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

    LaunchedEffect(status.pairedDeviceCount, status.isHosting) {
        trustedDevices = syncEngine.trustedDevices()
    }

    val isIpValid = manualHost.isBlank() || manualHost.matches(Regex("""^[\.\d]+$"""))
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
        val seconds = diff / 1000; val minutes = seconds / 60; val hours = minutes / 60; val days = hours / 24
        return when {
            seconds < 60 -> "just now"; minutes < 60 -> "${minutes}m ago"; hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"; else -> "${days / 7}w ago"
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
        item { ThemeContent(
            themeConfig = themeConfig, themeManager = themeManager,
            editBaseTheme = editBaseTheme, editPrimary = editPrimary, editSecondary = editSecondary,
            editTertiary = editTertiary, editBgColor = editBgColor, editSurfaceColor = editSurfaceColor,
            editBgImage = editBgImage, editBgOpacity = editBgOpacity, editCardStyle = editCardStyle,
            editCornerRadius = editCornerRadius, editFontScale = editFontScale, editAnimationScale = editAnimationScale,
            onBaseThemeChange = { editBaseTheme = it },
            onPrimaryChange = { editPrimary = it }, onSecondaryChange = { editSecondary = it },
            onTertiaryChange = { editTertiary = it }, onBgColorChange = { editBgColor = it },
            onSurfaceColorChange = { editSurfaceColor = it }, onBgImageChange = { editBgImage = it },
            onBgOpacityChange = { editBgOpacity = it }, onCardStyleChange = { editCardStyle = it },
            onCornerRadiusChange = { editCornerRadius = it }, onFontScaleChange = { editFontScale = it },
            onAnimationScaleChange = { editAnimationScale = it }, applyTheme = { applyTheme() }
        ) }

        // ================ PREFERENCES ================
        item {
            val prefExpanded = remember { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { prefExpanded.value = !prefExpanded.value },
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Preferences", style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(if (prefExpanded.value) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    if (prefExpanded.value) {
                        Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                        val useShulgin by repo.useShulginRating.collectAsState()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Rating scale", style = MaterialTheme.typography.bodyMedium)
                                Text(if (useShulgin) "Shulgin scale (+/-, +, ++, +++, ++++)"
                                    else "Numeric scale (1-10)",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = useShulgin, onCheckedChange = { repo.setShulginRating(it) })
                        }
                        Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                        val showTrend by repo.showSessionsTrendChart.collectAsState()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Sessions per week chart", style = MaterialTheme.typography.bodyMedium)
                                Text(if (showTrend) "Shows weekly trend on Dashboard"
                                    else "Hidden by default",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = showTrend, onCheckedChange = { repo.setShowSessionsTrendChart(it) })
                        }
                    }
                }
            }
        }

        // ================ DATA ================
        item {
            DataSettingsContent(
                repo = repo, sessionCount = sessionCount, substanceCount = substanceCount,
                statusText = dataStatus, dataExpanded = dataExpanded,
                scope = scope, onDataExpanded = { dataExpanded = !dataExpanded },
                onStatusChange = { dataStatus = it }
            )
        }

        // ================ OBSIDIAN VAULT ================
        item { ObsidianSettingsCard() }

        // ================ DEVICE SYNC ================
        item {
            SyncSettingsContent(
                syncEngine = syncEngine, status = status, logLines = logLines, trustedDevices = trustedDevices,
                manualHost = manualHost, manualPort = manualPort, manualToken = manualToken,
                continuousSync = continuousSync, hostError = hostError, portError = portError,
                isPortValid = isPortValid, isIpValid = isIpValid, isSyncing = isSyncing,
                syncExpanded = syncExpanded, isStartingHost = isStartingHost, isStoppingHost = isStoppingHost,
                scope = scope, clipboard = clipboard, formatTimestamp = { formatTimestamp(it) },
                userMessage = { userMessage(it) },
                onManualHostChange = { manualHost = it }, onManualPortChange = { manualPort = it },
                onManualTokenChange = { manualToken = it }, onContinuousSyncChange = { continuousSync = it },
                onSyncExpanded = { syncExpanded = !syncExpanded },
                onLogLine = { logLines = listOf(it) + logLines },
                onTrustedDevicesChange = { trustedDevices = it },
                onIsSyncingChange = { isSyncing = it },
                onIsStartingHostChange = { isStartingHost = it },
                onIsStoppingHostChange = { isStoppingHost = it },
            )
        }

        // ================ SUBSTANCE LIBRARY ================
        item {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp).then(if (isDesktopPlatform()) Modifier.animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 2000f)) else Modifier)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { libraryExpanded = !libraryExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Substance library", style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(if (libraryExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (libraryExpanded) {
                        Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                        Text("Reloads the default PsychonautWiki substance database from the bundled seed resource.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton(onClick = {
                                scope.launch {
                                    isFetching = true; fetchStatus = "Reloading seed data..."
                                    try {
                                        app.journal.data.DataInitializer.reloadDefaultSubstances(repo)
                                        fetchStatus = "Reloaded ${repo.substances.value.size} substances from seed"
                                    } catch (e: Exception) { fetchStatus = "Reload failed: ${e.message}" }
                                    isFetching = false
                                }
                            }, enabled = !isFetching, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp)); Text("Reset to defaults")
                            }
                        }
                        fetchStatus?.let {
                            Spacer(Modifier.height(4.dp)); Text(it, style = MaterialTheme.typography.labelSmall,
                                color = if (it.startsWith("Loaded")) MaterialTheme.colorScheme.primary
                                else if (it.startsWith("Fetch failed")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ================ ABOUT ================
        item { AboutCardContent(aboutExpanded = aboutExpanded, onToggle = { aboutExpanded = !aboutExpanded }) }

        // ================ LEGAL ================
        item { LegalCardContent(legalExpanded = legalExpanded, onToggle = { legalExpanded = !legalExpanded }) }

        // ================ PRIVACY ================
        item { PrivacyCardContent(privacyExpanded = privacyExpanded, onToggle = { privacyExpanded = !privacyExpanded }) }

        // ================ DEVELOPER ================
        item {
            DeveloperCardContent(
                repo = repo, crashLogStatus = crashLogStatus, scope = scope,
                onCrashLogStatusChange = { crashLogStatus = it },
                onDataStatusChange = { dataStatus = it }
            )
        }

        // ================ FOOTER ================
        item { Text("No ads. No subscriptions. No tracking. Your data is yours.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
