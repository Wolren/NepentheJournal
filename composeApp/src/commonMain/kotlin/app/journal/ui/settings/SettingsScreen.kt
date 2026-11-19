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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.journal.model.AutosaveDelay
import app.journal.model.RatingScaleMode
import app.journal.model.StartTab
import app.journal.util.TimeDisplayMode
import app.journal.sync.*
import app.journal.ui.theme.*
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis
import app.journal.util.formatRelativeTime
import app.journal.ui.pet.availablePets
import app.journal.util.isDesktopPlatform
import app.journal.util.PlatformFile
import app.journal.ui.settings.detail.SyncSettingsCallbacks
import app.journal.ui.settings.detail.SyncSettingsUiState
import app.journal.ui.settings.detail.*
import app.journal.ui.pet.CompanionCard
import app.journal.ui.keybind.KeybindCard

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
    // Captured at composition: ThemeManager.isDarkTheme is @Composable and can't be called from click lambdas.
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
    var editBackdropGradient by remember(themeConfig) { mutableStateOf(themeConfig.backdropGradient) }
    var editBackdropScrim by remember(themeConfig) { mutableStateOf(themeConfig.backdropScrim) }
    var editCardStyle by remember(themeConfig) { mutableStateOf(themeConfig.cardStyle) }
    var editCornerRadius by remember(themeConfig) { mutableStateOf(themeConfig.cornerRadius) }
    var editDensity by remember(themeConfig) { mutableStateOf(themeConfig.density) }
    var editWindowOpacity by remember(themeConfig) { mutableStateOf(themeConfig.windowOpacity) }
    var editCardOpacity by remember(themeConfig) { mutableStateOf(themeConfig.cardOpacity) }
    var editFontScale by remember(themeConfig) { mutableStateOf(themeConfig.fontScale) }
    var editFontFamily by remember(themeConfig) { mutableStateOf(themeConfig.fontFamily) }
    var editTextColor by remember(themeConfig) { mutableStateOf(themeConfig.textColor) }
    var editAnimationScale by remember(themeConfig) { mutableStateOf(themeConfig.animationScale) }
    var editSurfaceTint by remember(themeConfig) { mutableStateOf(themeConfig.surfaceTint) }
    var editOutlineStrength by remember(themeConfig) { mutableStateOf(themeConfig.outlineStrength) }
    var editSubstanceAccentMode by remember(themeConfig) { mutableStateOf(themeConfig.substanceAccentMode) }
    var editGithubGreen by remember(themeConfig) { mutableStateOf(themeConfig.githubGreenActivity) }
    var editPharmacologyChart by remember(themeConfig) { mutableStateOf(themeConfig.pharmacologyChart) }
    var editSourcePriority by remember(themeConfig) { mutableStateOf(themeConfig.pharmacologySourcePriority) }
    // Decoded once per screen: the companion card only exists where a pet can actually load.
    val hasPets = remember { availablePets().isNotEmpty() }

    fun currentEditConfig(): ThemeConfig = ThemeConfig(
        baseTheme = editBaseTheme, primaryColor = editPrimary, secondaryColor = editSecondary,
        tertiaryColor = editTertiary, backgroundColor = editBgColor, surfaceColor = editSurfaceColor,
        backgroundImagePath = editBgImage?.takeIf { it.isNotBlank() }, backgroundOpacity = editBgOpacity,
        backdropGradient = editBackdropGradient, backdropScrim = editBackdropScrim,
        cardStyle = editCardStyle, cornerRadius = editCornerRadius, density = editDensity, fontScale = editFontScale,
        fontFamily = editFontFamily, textColor = editTextColor,
        animationScale = editAnimationScale, surfaceTint = editSurfaceTint,
        outlineStrength = editOutlineStrength, substanceAccentMode = editSubstanceAccentMode,
        githubGreenActivity = editGithubGreen, pharmacologyChart = editPharmacologyChart,
        pharmacologySourcePriority = editSourcePriority,
        windowOpacity = editWindowOpacity, cardOpacity = editCardOpacity
    )

    fun setEditConfig(config: ThemeConfig) {
        editBaseTheme = config.baseTheme
        editPrimary = config.primaryColor
        editSecondary = config.secondaryColor
        editTertiary = config.tertiaryColor
        editBgColor = config.backgroundColor ?: editBgColor
        editSurfaceColor = config.surfaceColor ?: editSurfaceColor
        editBgImage = config.backgroundImagePath
        editBgOpacity = config.backgroundOpacity
        editBackdropGradient = config.backdropGradient
        editBackdropScrim = config.backdropScrim
        editCardStyle = config.cardStyle
        editCornerRadius = config.cornerRadius
        editDensity = config.density
        editWindowOpacity = config.windowOpacity
        editCardOpacity = config.cardOpacity
        editFontScale = config.fontScale
        editFontFamily = config.fontFamily
        editTextColor = config.textColor
        editAnimationScale = config.animationScale
        editSurfaceTint = config.surfaceTint
        editOutlineStrength = config.outlineStrength
        editSubstanceAccentMode = config.substanceAccentMode
        editGithubGreen = config.githubGreenActivity
        editPharmacologyChart = config.pharmacologyChart
        editSourcePriority = config.pharmacologySourcePriority
    }

    fun applyTheme() {
        themeManager.update(currentEditConfig())
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

    val dataViewModel = remember(repo, scope) { DataSettingsViewModel.create(repo, scope) }
    val fetchStatus by dataViewModel.fetchStatus.collectAsState()
    val isFetching by dataViewModel.isFetching.collectAsState()

    LaunchedEffect(status.pairedDeviceCount, status.isHosting) {
        trustedDevices = syncEngine.trustedDevices()
    }

    val scrollState = rememberLazyListState()
    // Categories drive the two-pane split; below the rail width the list shows every category in order.
    var selectedCategoryIndex by rememberSaveable { mutableStateOf(0) }
    val selectedCategory = SettingsCategory.entries[selectedCategoryIndex]

    CenteredContent {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val spacing = LocalSpacing.current
        val twoPane = maxWidth >= TwoPaneSettingsWidth
        val visible: (SettingsCategory) -> Boolean = { category -> !twoPane || category == selectedCategory }
        LaunchedEffect(selectedCategory) { if (twoPane) scrollState.scrollToItem(0) }
        Row(
            modifier = Modifier.fillMaxSize().padding(spacing.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(spacing.cardGap)
        ) {
            if (twoPane) {
                SettingsCategoryNav(
                    selected = selectedCategory,
                    onSelect = { selectedCategoryIndex = it.ordinal },
                    modifier = Modifier.width(SettingsNavWidth)
                )
                VerticalDivider()
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("settings-list"),
            verticalArrangement = Arrangement.spacedBy(spacing.cardGap),
            state = scrollState
        ) {
        if (visible(SettingsCategory.APPEARANCE)) item { ThemeContent(
            themeConfig = themeConfig, themeManager = themeManager,
            editBaseTheme = editBaseTheme, editPrimary = editPrimary, editSecondary = editSecondary,
            editTertiary = editTertiary, editBgColor = editBgColor, editSurfaceColor = editSurfaceColor,
            editBgImage = editBgImage, editBgOpacity = editBgOpacity,
            editBackdropGradient = editBackdropGradient, editBackdropScrim = editBackdropScrim, editCardStyle = editCardStyle,
            editCornerRadius = editCornerRadius, editDensity = editDensity,
            editWindowOpacity = editWindowOpacity, editCardOpacity = editCardOpacity,
            editFontScale = editFontScale, editAnimationScale = editAnimationScale,
            editFontFamily = editFontFamily, editTextColor = editTextColor,
            editSurfaceTint = editSurfaceTint, editOutlineStrength = editOutlineStrength,
            editSubstanceAccentMode = editSubstanceAccentMode,
            editGithubGreen = editGithubGreen, editPharmacologyChart = editPharmacologyChart,
        editSourcePriority = editSourcePriority,
            onBaseThemeChange = { editBaseTheme = it },
            onPrimaryChange = { editPrimary = it }, onSecondaryChange = { editSecondary = it },
            onTertiaryChange = { editTertiary = it }, onBgColorChange = { editBgColor = it },
            onSurfaceColorChange = { editSurfaceColor = it }, onBgImageChange = { editBgImage = it },
            onBgOpacityChange = { editBgOpacity = it },
            onBackdropGradientChange = { editBackdropGradient = it }, onBackdropScrimChange = { editBackdropScrim = it },
            onCardStyleChange = { editCardStyle = it },
            onCornerRadiusChange = { editCornerRadius = it }, onDensityChange = { editDensity = it },
            onWindowOpacityChange = { editWindowOpacity = it }, onCardOpacityChange = { editCardOpacity = it },
            onFontScaleChange = { editFontScale = it },
            onFontFamilyChange = { editFontFamily = it }, onTextColorChange = { editTextColor = it },
            onSurfaceTintChange = { editSurfaceTint = it }, onOutlineStrengthChange = { editOutlineStrength = it },
            onSubstanceAccentModeChange = { editSubstanceAccentMode = it },
            onAnimationScaleChange = { editAnimationScale = it },
            onGithubGreenChange = { editGithubGreen = it },
            onPharmacologyChartChange = { editPharmacologyChart = it },
        onSourcePriorityChange = { editSourcePriority = it },
            onPresetSelect = { preset ->
                setEditConfig(preset.applyTo(currentEditConfig(), isDarkNow))
                applyTheme()
            },
            onConfigImport = { config -> setEditConfig(config); applyTheme() },
            applyTheme = { applyTheme() }
        ) }

        // The companion appears wherever a pet loads; keybinds need a keyboard, so desktop only.
        if (visible(SettingsCategory.APPEARANCE) && hasPets) item { CompanionCard() }
        if (visible(SettingsCategory.PREFERENCES) && isDesktopPlatform()) item { KeybindCard() }

        if (visible(SettingsCategory.PREFERENCES)) item {
            val prefExpanded = remember { mutableStateOf(false) }
            CollapsibleSettingsCard(
                expanded = prefExpanded.value,
                onToggle = { prefExpanded.value = !prefExpanded.value },
                icon = Icons.Default.Tune, title = "Preferences"
            ) {
                val ratingMode by repo.ratingScaleMode.collectAsState()
                val timeMode by repo.timeDisplayMode.collectAsState()
                val startTab by repo.startTab.collectAsState()
                val autosave by repo.autosaveDelay.collectAsState()
                val showTrend by repo.showSessionsTrendChart.collectAsState()
                val showHeatmap by repo.dashboardHeatmap.collectAsState()
                val showTolerance by repo.dashboardTolerance.collectAsState()
                val showTop by repo.dashboardTopSubstances.collectAsState()
                val confirmDeletes by repo.confirmDeletes.collectAsState()
                val autoBackup by repo.autoBackupOnClose.collectAsState()

                PreferenceSectionTitle("Rating scale")
                RatingScaleMode.entries.forEach { mode ->
                    PreferenceRadioRow(
                        selected = ratingMode == mode,
                        title = mode.label,
                        subtitle = mode.description,
                        onSelect = { repo.setRatingScaleMode(mode) },
                    )
                }

                PreferenceDivider()
                PreferenceSectionTitle("Time display")
                PreferenceCaption("How session timestamps render in lists and timelines.")
                TimeDisplayMode.entries.forEach { mode ->
                    val (title, hint) = mode.settingText()
                    PreferenceRadioRow(
                        selected = timeMode == mode,
                        title = title,
                        subtitle = hint,
                        onSelect = { repo.setTimeDisplayMode(mode) },
                    )
                }

                PreferenceDivider()
                PreferenceSectionTitle("Open on")
                StartTab.entries.forEach { tab ->
                    PreferenceRadioRow(
                        selected = startTab == tab,
                        title = tab.label,
                        subtitle = tab.description,
                        onSelect = { repo.setStartTab(tab) },
                    )
                }

                PreferenceDivider()
                PreferenceSectionTitle("Free-form autosave")
                PreferenceCaption("How long the live session waits after the last keystroke before it writes the draft.")
                AutosaveDelay.entries.forEach { delay ->
                    PreferenceRadioRow(
                        selected = autosave == delay,
                        title = delay.label,
                        subtitle = delay.description,
                        onSelect = { repo.setAutosaveDelay(delay) },
                    )
                }

                PreferenceDivider()
                PreferenceSectionTitle("Dashboard cards")
                PreferenceSwitchRow(
                    title = "Activity heatmap",
                    subtitle = "Year of session activity, one square per day",
                    checked = showHeatmap,
                    onCheckedChange = { repo.setDashboardHeatmap(it) },
                )
                PreferenceSwitchRow(
                    title = "Tolerance overview",
                    subtitle = "Per-substance tolerance from recent doses",
                    checked = showTolerance,
                    onCheckedChange = { repo.setDashboardTolerance(it) },
                )
                PreferenceSwitchRow(
                    title = "Top substances",
                    subtitle = "Substances ranked by sessions",
                    checked = showTop,
                    onCheckedChange = { repo.setDashboardTopSubstances(it) },
                )
                PreferenceSwitchRow(
                    title = "Sessions per week chart",
                    subtitle = "Weekly trend line across the dashboard",
                    checked = showTrend,
                    onCheckedChange = { repo.setShowSessionsTrendChart(it) },
                )

                PreferenceDivider()
                PreferenceSectionTitle("Deleting")
                PreferenceSwitchRow(
                    title = "Ask before deleting",
                    subtitle = "Confirm dialogs for sessions and timeline events",
                    checked = confirmDeletes,
                    onCheckedChange = { repo.setConfirmDeletes(it) },
                )

                PreferenceDivider()
                PreferenceSectionTitle("Backups")
                PreferenceSwitchRow(
                    title = "Auto-backup on close",
                    subtitle = "Writes a timestamped backup when the app closes",
                    checked = autoBackup,
                    onCheckedChange = { repo.setAutoBackupOnClose(it) },
                )
            }
        }

        if (visible(SettingsCategory.DATA)) item {
            DataSettingsContent(
                vm = dataViewModel, sessionCount = sessionCount, substanceCount = substanceCount,
                dataExpanded = dataExpanded,
                onDataExpanded = { dataExpanded = !dataExpanded }
            )
        }

        if (visible(SettingsCategory.DATA)) item { ForeignImportSettingsCard(vm = dataViewModel) }

        if (visible(SettingsCategory.DATA)) item { CsvExportSettingsCard(vm = dataViewModel) }

        if (visible(SettingsCategory.JOURNAL)) item { PeopleSettingsContent(repo = repo) }

        if (visible(SettingsCategory.JOURNAL)) item { MedicationsSettingsContent(repo = repo) }

        // A vault is a real folder written through the file API, which scoped storage refuses on a phone.
        if (visible(SettingsCategory.JOURNAL) && isDesktopPlatform()) item { ObsidianSettingsCard() }

        if (visible(SettingsCategory.SYNC)) item {
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

        if (visible(SettingsCategory.DATA)) item {
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

        if (visible(SettingsCategory.ABOUT)) item { AboutCardContent(aboutExpanded = aboutExpanded, onToggle = { aboutExpanded = !aboutExpanded }) }

        if (visible(SettingsCategory.ABOUT)) item { LegalCardContent(legalExpanded = legalExpanded, onToggle = { legalExpanded = !legalExpanded }) }

        if (visible(SettingsCategory.ABOUT)) item { PrivacyCardContent(privacyExpanded = privacyExpanded, onToggle = { privacyExpanded = !privacyExpanded }) }

        if (visible(SettingsCategory.ABOUT)) item {
            DeveloperCardContent(vm = dataViewModel)
        }

        item { Spacer(Modifier.height(16.dp)) }
        }
        DesktopScrollbar(scrollState)
            }
        }
    }
    }
}

/** Category list for the two-pane settings layout; the single-column layout shows every category in order. */
private enum class SettingsCategory(val label: String, val icon: ImageVector) {
    APPEARANCE("Appearance", Icons.Default.Palette),
    PREFERENCES("Preferences", Icons.Default.Tune),
    DATA("Data", Icons.Default.Storage),
    JOURNAL("Journal", Icons.Default.People),
    SYNC("Sync", Icons.Default.Sync),
    ABOUT("About", Icons.Default.Info),
}

private val TwoPaneSettingsWidth = 840.dp
private val SettingsNavWidth = 200.dp

@Composable
private fun SettingsCategoryNav(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsCategory.entries.forEach { category ->
            val isSelected = category == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(chipShape())
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(category) })
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** Small heading above one settings group inside the preferences card. */
@Composable
private fun PreferenceSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** One line of explanation under a settings heading. */
@Composable
private fun PreferenceCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PreferenceDivider() {
    Spacer(Modifier.height(10.dp))
    HorizontalDivider()
    Spacer(Modifier.height(10.dp))
}

/** A radio row with a title and a one-line explanation. */
@Composable
private fun PreferenceRadioRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A switch row with a title and a one-line explanation. */
@Composable
private fun PreferenceSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Display form of a timestamp mode, shown in the Time display group. */
private fun TimeDisplayMode.settingText(): Pair<String, String> = when (this) {
    TimeDisplayMode.RELATIVE -> "Relative" to "2h ago, Yesterday, 3w ago"
    TimeDisplayMode.CLOCK -> "Clock time" to "Wall clock, 15:30"
    TimeDisplayMode.ELAPSED -> "Since session start" to "T+1:23 from the opening dose"
    TimeDisplayMode.DURATION -> "Elapsed duration" to "1h 23m of session time"
}
