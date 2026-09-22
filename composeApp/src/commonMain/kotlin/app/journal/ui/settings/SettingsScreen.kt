package app.journal.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.ui.components.DesktopScrollbar
import androidx.compose.foundation.lazy.rememberLazyListState
import app.journal.data.IJournalRepository
import app.journal.ui.LocalJournalRepository
import app.journal.model.RatingScaleMode
import app.journal.sync.*
import app.journal.ui.theme.*
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis
import app.journal.util.formatRelativeTime
import app.journal.util.isDesktopPlatform
import app.journal.util.PlatformFile
import app.journal.ui.settings.detail.SyncSettingsCallbacks
import app.journal.ui.settings.detail.SyncSettingsUiState
import app.journal.ui.settings.detail.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: IJournalRepository = LocalJournalRepository.current,
    syncEngine: SyncEngine,
) {
    val sessionCount by repo.totalSessionCount.collectAsState(initial = 0)
    val substanceCount by repo.totalSubstanceCount.collectAsState(initial = 0)

    val themeManager = remember { ThemeManager.instance }
    val themeConfig by themeManager.config.collectAsState()
    // Captured at composition: ThemeManager.isDarkTheme is @Composable and
    // cannot be called from click lambdas.
    val isDarkNow = themeManager.isDarkTheme()
    var themeExpanded by remember { mutableStateOf(false) }
    var editBaseTheme by remember(themeConfig) { mutableStateOf(themeConfig.baseTheme) }
    var editPrimary by remember(themeConfig) { mutableStateOf(themeConfig.primaryColor) }
    var editSecondary by remember(themeConfig) { mutableStateOf(themeConfig.secondaryColor) }
    var editTertiary by remember(themeConfig) { mutableStateOf(themeConfig.tertiaryColor) }
    var editBgColor by remember(themeConfig) { mutableStateOf<Long>(themeConfig.backgroundColor ?: 0xFF0E1511L) }
    var editSurfaceColor by remember(themeConfig) { mutableStateOf(themeConfig.surfaceColor ?: 0xFF16211AL) }
    var editBgImage by remember(themeConfig) { mutableStateOf(themeConfig.backgroundImagePath) }
    var editBgOpacity by remember(themeConfig) { mutableStateOf(themeConfig.backgroundOpacity) }
    var editCardStyle by remember(themeConfig) { mutableStateOf(themeConfig.cardStyle) }
    var editCornerRadius by remember(themeConfig) { mutableStateOf(themeConfig.cornerRadius) }
    var editFontScale by remember(themeConfig) { mutableStateOf(themeConfig.fontScale) }
    var editAnimationScale by remember(themeConfig) { mutableStateOf(themeConfig.animationScale) }
    var editGithubGreen by remember(themeConfig) { mutableStateOf(themeConfig.githubGreenActivity) }

    fun applyTheme() {
        themeManager.update(ThemeConfig(
            baseTheme = editBaseTheme, primaryColor = editPrimary, secondaryColor = editSecondary,
            tertiaryColor = editTertiary, backgroundColor = editBgColor, surfaceColor = editSurfaceColor,
            backgroundImagePath = editBgImage?.takeIf { it.isNotBlank() }, backgroundOpacity = editBgOpacity,
            cardStyle = editCardStyle, cornerRadius = editCornerRadius, fontScale = editFontScale,
            animationScale = editAnimationScale, githubGreenActivity = editGithubGreen
        ))
    }

    val status by syncEngine.observeStatus().collectAsState(initial = SyncStatusSnapshot(
        isHosting = false, hostAddress = null, activeConnections = emptyList(), lastSyncAt = null,
        pendingConflicts = 0, lastError = null
    ))
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var syncState by remember { mutableStateOf(SyncSettingsUiState()) }
    var dataExpanded by remember { mutableStateOf(false) }
    var aboutExpanded by remember { mutableStateOf(false) }
    var legalExpanded by remember { mutableStateOf(false) }
    var privacyExpanded by remember { mutableStateOf(false) }
    var libraryExpanded by remember { mutableStateOf(false) }
    var logLines by remember {
        mutableStateOf(listOf<SyncLogEntry>(SyncLogEntry.Info("Sync engine ready")))
    }
    var trustedDevices by remember { mutableStateOf(syncEngine.trustedDevices()) }

    // Seed reload, test data and every export/import/backup operation live in
    // the view model; this screen only reads status and calls its methods.
    val dataViewModel = remember(repo, scope) { DataSettingsViewModel.create(repo, scope) }
    val fetchStatus by dataViewModel.fetchStatus.collectAsState()
    val isFetching by dataViewModel.isFetching.collectAsState()

    LaunchedEffect(status.pairedDeviceCount, status.isHosting) {
        trustedDevices = syncEngine.trustedDevices()
    }

    val scrollState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            state = scrollState
        ) {
        // ================ THEME ================
        item { ThemeContent(
            themeConfig = themeConfig, themeManager = themeManager,
            editBaseTheme = editBaseTheme, editPrimary = editPrimary, editSecondary = editSecondary,
            editTertiary = editTertiary, editBgColor = editBgColor, editSurfaceColor = editSurfaceColor,
            editBgImage = editBgImage, editBgOpacity = editBgOpacity, editCardStyle = editCardStyle,
            editCornerRadius = editCornerRadius, editFontScale = editFontScale, editAnimationScale = editAnimationScale,
            editGithubGreen = editGithubGreen,
            onBaseThemeChange = { editBaseTheme = it },
            onPrimaryChange = { editPrimary = it }, onSecondaryChange = { editSecondary = it },
            onTertiaryChange = { editTertiary = it }, onBgColorChange = { editBgColor = it },
            onSurfaceColorChange = { editSurfaceColor = it }, onBgImageChange = { editBgImage = it },
            onBgOpacityChange = { editBgOpacity = it }, onCardStyleChange = { editCardStyle = it },
            onCornerRadiusChange = { editCornerRadius = it }, onFontScaleChange = { editFontScale = it },
            onAnimationScaleChange = { editAnimationScale = it },
            onGithubGreenChange = { editGithubGreen = it },
            onPresetSelect = { preset ->
                // Apply the preset variant matching the current dark/light mode,
                // keeping the user's base theme choice (Dark/Light/System).
                val v = preset.variant(isDarkNow)
                editPrimary = v.primaryColor
                editSecondary = v.secondaryColor
                editTertiary = v.tertiaryColor
                editBgColor = v.backgroundColor ?: editBgColor
                editSurfaceColor = v.surfaceColor ?: editSurfaceColor
                preset.cardStyle?.let { editCardStyle = it }
                preset.cornerRadius?.let { editCornerRadius = it }
                applyTheme()
            },
            applyTheme = { applyTheme() }
        ) }

        // ================ PREFERENCES ================
        item {
            val prefExpanded = remember { mutableStateOf(false) }
            CollapsibleSettingsCard(
                expanded = prefExpanded.value,
                onToggle = { prefExpanded.value = !prefExpanded.value },
                icon = Icons.Default.Edit, title = "Preferences"
            ) {
                val ratingMode by repo.ratingScaleMode.collectAsState()
                Column(Modifier.fillMaxWidth()) {
                    Text("Rating scale", style = MaterialTheme.typography.bodyMedium)
                    RatingScaleMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = ratingMode == mode,
                                    onClick = { repo.setRatingScaleMode(mode) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = ratingMode == mode,
                                onClick = { repo.setRatingScaleMode(mode) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                                Text(mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
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

        // ================ DATA ================
        item {
            DataSettingsContent(
                vm = dataViewModel, sessionCount = sessionCount, substanceCount = substanceCount,
                dataExpanded = dataExpanded,
                onDataExpanded = { dataExpanded = !dataExpanded }
            )
        }

        // ================ PEOPLE ================
        item { PeopleSettingsContent(repo = repo) }

        // ================ OBSIDIAN VAULT ================
        item { ObsidianSettingsCard() }

        // ================ DEVICE SYNC ================
        item {
            SyncSettingsContent(
                syncEngine = syncEngine, status = status, state = syncState,
                callbacks = SyncSettingsCallbacks(
                    onManualHostChange = { syncState = syncState.copy(manualHost = it) },
                    onManualPortChange = { syncState = syncState.copy(manualPort = it) },
                    onManualTokenChange = { syncState = syncState.copy(manualToken = it) },
                    onContinuousSyncChange = { syncState = syncState.copy(continuousSync = it) },
                    onSyncExpanded = { syncState = syncState.copy(syncExpanded = !syncState.syncExpanded) },
                    onLogLine = { logLines = listOf(it) + logLines },
                    onTrustedDevicesChange = { trustedDevices = it },
                    onIsSyncingChange = { syncState = syncState.copy(isSyncing = it) },
                    onIsStartingHostChange = { syncState = syncState.copy(isStartingHost = it) },
                    onIsStoppingHostChange = { syncState = syncState.copy(isStoppingHost = it) },
                ),
                scope = scope, clipboard = clipboard, formatTimestamp = ::formatRelativeTime,
            )
        }

        // ================ SUBSTANCE LIBRARY ================
        item {
            CollapsibleSettingsCard(
                expanded = libraryExpanded,
                onToggle = { libraryExpanded = !libraryExpanded },
                icon = Icons.Default.MenuBook, title = "Substance library"
            ) {
                Text("Reloads the default PsychonautWiki substance database from the bundled seed resource.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(onClick = { dataViewModel.reloadDefaultSubstances() },
                        enabled = !isFetching, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp)); Text("Reset to defaults")
                    }
                }
                fetchStatus?.let {
                    Spacer(Modifier.height(4.dp)); Text(it, style = MaterialTheme.typography.labelSmall,
                        color = if (it.contains("failed")) MaterialTheme.colorScheme.error
                        else if (it.startsWith("Reloaded")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
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
            DeveloperCardContent(vm = dataViewModel)
        }

        // ================ FOOTER ================
        item {
            Text("No ads. No subscriptions. No tracking. Your data is yours.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        item { Spacer(Modifier.height(16.dp)) }
        }
        DesktopScrollbar(scrollState)
    }
}
