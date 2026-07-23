/*
 * Nepenthe Journal — GPLv3
 * Copyright (C) 2026 Wolren
 *
 * Derived from PsychonautWiki Journal (GPL-3.0-or-later)
 * Copyright (C) 2022 Isaak Hanimann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.journal.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
import app.journal.data.JournalStore
import app.journal.model.Session
import app.journal.sync.SyncEngine
import app.journal.sync.createSyncEngine
import app.journal.util.currentTimeMillis
import app.journal.util.isSoftwareRender
import app.journal.util.platformDeviceOrigin
import app.journal.ui.dashboard.DashboardScreen
import app.journal.ui.safer.SaferScreen
import app.journal.ui.search.SearchOverlay
import app.journal.ui.session.CalendarScreen
import app.journal.ui.session.SessionEditorScreen
import app.journal.ui.session.SessionListScreen
import app.journal.ui.session.SessionListViewModel
import app.journal.ui.session.SessionTimelineScreen
import app.journal.ui.session.LiveSessionScreen
import app.journal.ui.settings.SettingsScreen
import app.journal.ui.substances.SubstanceDetailScreen
import app.journal.ui.substances.SubstanceEditorScreen
import app.journal.ui.substances.SubstanceCompanionScreen
import app.journal.ui.substances.SubstanceScreen
import app.journal.ui.theme.BackgroundImage
import app.journal.ui.theme.LocalThemeConfig
import app.journal.ui.theme.NepentheTypography
import app.journal.ui.theme.ThemeManager
import app.journal.ui.SystemBackHandler

enum class Screen(
    val label: String,
    val filledIcon: ImageVector,
    val outlinedIcon: ImageVector
) {
    DASHBOARD("Board", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    SESSIONS("Sessions", Icons.AutoMirrored.Filled.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
    SUBSTANCES("Drugs", Icons.Filled.Science, Icons.Outlined.Science),
    SAFER("Safe", Icons.Default.Warning, Icons.Default.Warning),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(repo: IJournalRepository = JournalRepository.instance) {
    val themeManager = remember { ThemeManager.instance }
    val themeConfig by themeManager.config.collectAsState()

    // ── Data integrity: startup recovery dialog ──
    val journalStore = remember { JournalStore(repo as JournalRepository) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var recoveryMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // Check after the first render cycle to let the UI settle
        kotlinx.coroutines.delay(100)
        if (journalStore.lastLoadHadIssues) {
            recoveryMessage = journalStore.lastLoadIssueSummary
            showRecoveryDialog = true
        }
    }

    // ── Data integrity: auto-backup on close ──
    DisposableEffect(Unit) {
        onDispose {
            journalStore.triggerAutoBackup()
        }
    }

    // ── Recovery dialog ──
    if (showRecoveryDialog) {
        AlertDialog(
            onDismissRequest = { showRecoveryDialog = false },
            title = { Text("Data Recovery Notice") },
            text = {
                Text("Your journal data had issues when loading:\n\n$recoveryMessage\n\nDo you want to continue with the partially recovered data or restore from the previous backup (.bak)?")
            },
            confirmButton = {
                TextButton(onClick = { showRecoveryDialog = false }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val restored = journalStore.restoreFromBackup()
                    if (restored) {
                        recoveryMessage = "Restored from backup successfully."
                    } else {
                        recoveryMessage = "No backup available to restore from."
                    }
                    showRecoveryDialog = false
                }) {
                    Text("Restore from backup")
                }
            }
        )
    }

    var selectedScreen by remember { mutableStateOf(Screen.DASHBOARD) }
    var editingSessionId by remember { mutableStateOf<String?>(null) }
    var selectedTimelineSessionId by remember { mutableStateOf<String?>(null) }
    var showCalendar by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var selectedSubstanceId by remember { mutableStateOf<String?>(null) }
    var editingSubstanceId by remember { mutableStateOf<String?>(null) }
    var useRelativeTime by remember { mutableStateOf(true) }
    val sessionListViewModel = remember { SessionListViewModel.create(repo) }
    val syncEngine = remember { createSyncEngine(JournalRepository.instance) }
    val showFavs by sessionListViewModel.showFavoritesOnly.collectAsState()
    val showArch by sessionListViewModel.showArchived.collectAsState()
    var liveSessionId by remember { mutableStateOf<String?>(null) }
    var companionSubstanceId by remember { mutableStateOf<String?>(null) }

    CompositionLocalProvider(LocalThemeConfig provides themeConfig) {
        val isDark = themeManager.isDarkTheme()
        val colorScheme = themeManager.colorScheme(isDark)

        MaterialTheme(
            colorScheme = colorScheme,
            typography = NepentheTypography,
            shapes = themeConfig.shapes
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colorScheme.background
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Background image layer (renders behind content)
                    BackgroundImage(
                        imagePath = themeConfig.backgroundImagePath,
                        opacity = themeConfig.backgroundOpacity
                    )

                    // Foreground content
                    val editingSession = editingSessionId?.let { id ->
                        if (id == "__new__") null
                        else repo.getSession(id)
                    }
                    val editingSubstance = editingSubstanceId?.let { id ->
                        if (id == "__new__") null
                        else repo.getSubstance(id)
                    }

                    val stableTimelineId = selectedTimelineSessionId
                    val stableSubstanceId = selectedSubstanceId
                    val stableLiveId = liveSessionId

                    SystemBackHandler {
                        when {
                            liveSessionId != null -> liveSessionId = null
                            showSearch -> showSearch = false
                            companionSubstanceId != null -> companionSubstanceId = null
                            editingSessionId != null -> editingSessionId = null
                            selectedTimelineSessionId != null -> selectedTimelineSessionId = null
                            editingSubstanceId != null -> editingSubstanceId = null
                            selectedSubstanceId != null -> selectedSubstanceId = null
                            showCalendar -> showCalendar = false
                        }
                    }

                    val isSoftwareRender = remember { isSoftwareRender() }
                    val slideSpec: androidx.compose.animation.core.FiniteAnimationSpec<IntOffset> = remember {
                        if (isSoftwareRender) spring(dampingRatio = 1f, stiffness = 6000f)
                        else spring(dampingRatio = 1f, stiffness = 4000f)
                    }
                    val fadeSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> = remember {
                        if (isSoftwareRender) spring(dampingRatio = 1f, stiffness = 6000f)
                        else spring(dampingRatio = 1f, stiffness = 4000f)
                    }

                    AnimatedContent(
                        targetState = when {
                            showSearch -> "search"
                            companionSubstanceId != null -> "companion_substance"
                            liveSessionId != null -> "live_session"
                            editingSessionId != null -> "editor_session"
                            selectedTimelineSessionId != null -> "timeline"
                            editingSubstanceId != null -> "editor_substance"
                            selectedSubstanceId != null -> "detail_substance"
                            showCalendar -> "calendar"
                            else -> "main"
                        },
                        transitionSpec = {
                            if (targetState == "main") {
                                slideInVertically(animationSpec = slideSpec) { it / 8 } togetherWith
                                slideOutVertically(animationSpec = slideSpec) { it / 8 }
                            } else {
                                slideInVertically(animationSpec = slideSpec) { it / 8 } togetherWith
                                fadeOut(animationSpec = fadeSpec)
                            }
                        },
                        label = "navOverlay"
                    ) { state ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            when (state) {
                            "search" -> {
                                SearchOverlay(
                                    onBack = { showSearch = false },
                                    onSessionClick = { sessionId ->
                                        selectedTimelineSessionId = sessionId
                                        showSearch = false
                                    },
                                    onSubstanceClick = { subId ->
                                        selectedSubstanceId = subId
                                        showSearch = false
                                    }
                                )
                            }
                            "live_session" -> {
                                val session = stableLiveId?.let { id ->
                                    if (id == "__new__") null
                                    else JournalRepository.instance.getSession(id)
                                }
                                if (session != null) {
                                    LiveSessionScreen(
                                        session = session,
                                        onBack = { liveSessionId = null }
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Starting session...")
                                    }
                                    LaunchedEffect(Unit) {
                                        val now = currentTimeMillis()
                                        val newSession = Session(
                                            id = "session:live:${now}",
                                            title = "Live Session",
                                            startTime = now,
                                            createdAt = now, updatedAt = now,
                                            deviceOrigin = platformDeviceOrigin()
                                        )
                                        JournalRepository.instance.upsertSession(newSession)
                                        liveSessionId = newSession.id
                                    }
                                }
                            }
                            "editor_session" -> {
                                SessionEditorScreen(
                                    sessionToEdit = editingSession,
                                    onBack = { editingSessionId = null }
                                )
                            }
                            "timeline" -> {
                                val id = stableTimelineId
                                if (id != null) {
                                    SessionTimelineScreen(
                                        sessionId = id,
                                        onBack = { selectedTimelineSessionId = null }
                                    )
                                }
                            }
                            "editor_substance" -> {
                                SubstanceEditorScreen(
                                    substanceToEdit = editingSubstance,
                                    onBack = { editingSubstanceId = null }
                                )
                            }
                            "detail_substance" -> {
                                val id = stableSubstanceId
                                if (id != null) {
                                    SubstanceDetailScreen(
                                        substanceId = id,
                                        onBack = { selectedSubstanceId = null },
                                        onEdit = { editingId -> editingSubstanceId = editingId; selectedSubstanceId = null },
                                        onCompanion = { companionSubstanceId = id }
                                    )
                                }
                            }
                            "companion_substance" -> {
                                val id = companionSubstanceId
                                if (id != null) {
                                    SubstanceCompanionScreen(
                                        substanceId = id,
                                        onBack = { companionSubstanceId = null },
                                        onSessionClick = { sessionId: String ->
                                            selectedTimelineSessionId = sessionId
                                            companionSubstanceId = null
                                        }
                                    )
                                }
                            }
                            "calendar" -> {
                                CalendarScreen(
                                    onBack = { showCalendar = false },
                                    onSessionTap = { id -> selectedTimelineSessionId = id; showCalendar = false }
                                )
                            }
                            "main" -> {
                                Scaffold(
                                    modifier = Modifier.fillMaxSize(),
                                    topBar = {
                                        if (selectedScreen == Screen.SESSIONS) {
                                            TopAppBar(
                                                title = {
                                                    Text(
                                                        selectedScreen.label,
                                                        style = MaterialTheme.typography.titleLarge
                                                    )
                                                },
                                                actions = {
                                                    when (selectedScreen) {
                                                        Screen.SESSIONS -> SessionListScreen.TopActions(
                                                            showFavoritesOnly = showFavs,
                                                            showArchived = showArch,
                                                            onToggleFavorites = { sessionListViewModel.showFavoritesOnly.value = !showFavs },
                                                            onToggleArchived = { sessionListViewModel.showArchived.value = !showArch },
                                                            onCalendarClick = { showCalendar = true },
                                                            useRelativeTime = useRelativeTime,
                                                            onToggleTimeFormat = { useRelativeTime = !useRelativeTime }
                                                        )
                                                        else -> Unit
                                                    }
                                                },
                                                colors = TopAppBarDefaults.topAppBarColors(
                                                    containerColor = Color.Transparent,
                                                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                                                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                                                )
                                            )
                                        }
                                    },
                                    bottomBar = {
                                        NavigationBar(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            contentColor = MaterialTheme.colorScheme.onSurface
                                        ) {
                                            Screen.entries.forEach { screen ->
                                                NavigationBarItem(
                                                    selected = selectedScreen == screen,
                                                    onClick = { selectedScreen = screen },
                                                    icon = {
                                                        Icon(
                                                            imageVector = if (selectedScreen == screen) screen.filledIcon else screen.outlinedIcon,
                                                            contentDescription = screen.label
                                                        )
                                                    },
                                                    label = { Text(screen.label) }
                                                )
                                            }
                                        }
                                    }
                                ) { innerPadding ->
                                    Box(Modifier.padding(innerPadding).fillMaxSize()) {
                                        when (selectedScreen) {
                                            Screen.DASHBOARD -> DashboardScreen(
                                                onSearchClick = { showSearch = true }
                                            )
                                            Screen.SESSIONS -> SessionListScreen(
                                                viewModel = sessionListViewModel,
                                                onNewSession = { editingSessionId = "__new__" },
                                                onEditSession = { id -> editingSessionId = id },
                                                onSessionClick = { id -> selectedTimelineSessionId = id },
                                                onLiveSession = { liveSessionId = "__new__" },
                                                useRelativeTime = useRelativeTime,
                                                onToggleTimeFormat = { useRelativeTime = !useRelativeTime }
                                            )
                                            Screen.SUBSTANCES -> SubstanceScreen(
                                                onSubstanceClick = { id -> selectedSubstanceId = id },
                                                onNewSubstance = { editingSubstanceId = "__new__" }
                                            )
                                            Screen.SAFER -> SaferScreen()
                                            Screen.SETTINGS -> SettingsScreen(syncEngine = syncEngine)
                                        }  // closes when(selectedScreen)
                                    }  // closes inner Box(padding)
                                }  // closes Scaffold innerPadding
                            }  // closes "main" block
                            }  // closes when(state)
                        }  // closes Box wrapper (background flash fix)
                    }  // closes AnimatedContent transitionSpec
                }  // closes outer Box(fillMaxSize)
            }  // closes Surface
        }  // closes MaterialTheme
    }  // closes CompositionLocalProvider
}  // closes App