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
import androidx.compose.animation.core.tween
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
import app.journal.data.JournalRepository
import app.journal.model.Session
import app.journal.util.currentTimeMillis
import app.journal.util.platformDeviceOrigin
import app.journal.ui.dashboard.DashboardScreen
import app.journal.ui.safer.SaferScreen
import app.journal.ui.session.CalendarScreen
import app.journal.ui.session.SessionEditorScreen
import app.journal.ui.session.SessionListScreen
import app.journal.ui.session.SessionTimelineScreen
import app.journal.ui.session.LiveSessionScreen
import app.journal.ui.settings.SettingsScreen
import app.journal.ui.substances.SubstanceEditorScreen
import app.journal.ui.substances.SubstanceDetailScreen
import app.journal.ui.substances.SubstanceScreen
import app.journal.ui.theme.LocalThemeConfig
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
fun App() {
    val themeManager = remember { ThemeManager.instance }
    val themeConfig by themeManager.config.collectAsState()

    var selectedScreen by remember { mutableStateOf(Screen.DASHBOARD) }
    var editingSessionId by remember { mutableStateOf<String?>(null) }
    var selectedTimelineSessionId by remember { mutableStateOf<String?>(null) }
    var showCalendar by remember { mutableStateOf(false) }
    var selectedSubstanceId by remember { mutableStateOf<String?>(null) }
    var editingSubstanceId by remember { mutableStateOf<String?>(null) }
    var showFavoritesOnly by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var useRelativeTime by remember { mutableStateOf(true) }
    var liveSessionId by remember { mutableStateOf<String?>(null) }

    CompositionLocalProvider(LocalThemeConfig provides themeConfig) {
        val isDark = themeManager.isDarkTheme()
        val colorScheme = themeManager.colorScheme(isDark)

        MaterialTheme(
            colorScheme = colorScheme,
            shapes = themeConfig.shapes
        ) {
            // Root Surface ensures the entire window is always filled with
            // the theme background color — no white flash during transitions.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colorScheme.background
            ) {
                val editingSession = editingSessionId?.let { id ->
                    if (id == "__new__") null
                    else remember { JournalRepository.instance }.getSession(id)
                }
                val editingSubstance = editingSubstanceId?.let { id ->
                    if (id == "__new__") null
                    else remember { JournalRepository.instance }.getSubstance(id)
                }

                // Capture overlay IDs before AnimatedContent so they remain
                // valid during exit animations even after the state is cleared.
                val stableTimelineId = selectedTimelineSessionId
                val stableSubstanceId = selectedSubstanceId
                val stableLiveId = liveSessionId

            // Intercept system back button (Android) / map to overlay back
            SystemBackHandler {
                when {
                    liveSessionId != null -> liveSessionId = null
                    editingSessionId != null -> editingSessionId = null
                    selectedTimelineSessionId != null -> selectedTimelineSessionId = null
                    editingSubstanceId != null -> editingSubstanceId = null
                    selectedSubstanceId != null -> selectedSubstanceId = null
                    showCalendar -> showCalendar = false
                }
            }

            // Overlays with crossfade + slide animations (4.2)
            // Detect software rendering — skip animations to avoid tearing/flashing
            val isSoftwareRender = remember {
                System.getProperty("skiko.renderApi", "").uppercase() == "SOFTWARE" ||
                System.getProperty("skiko.renderApi", "").uppercase() == "SOFTWARE_FAST"
            }
            val animDuration = if (isSoftwareRender) 0 else 200
            AnimatedContent(
                targetState = when {
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
                        // Slide only — no fadeIn to avoid white flash from
                        // transparent background showing through while overlay slides out.
                        slideInVertically(animationSpec = tween(animDuration)) { it / 4 } togetherWith
                        slideOutVertically(animationSpec = tween(animDuration)) { it / 4 }
                    } else {
                        slideInVertically(animationSpec = tween(animDuration)) { it / 4 } togetherWith
                        fadeOut(animationSpec = tween(animDuration))
                    }
                },
                label = "navOverlay"
            ) { state ->
            when (state) {
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
                        // Create a new session then navigate to live session
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Starting session...")
                        }
                        LaunchedEffect(Unit) {
                            val now = currentTimeMillis()
                            val newSession = Session(
                                id = "session:live:${now}",
                                title = "Live Session",
                                startTime = now,
                                tags = emptyList(),
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
                                onEdit = { editingId -> editingSubstanceId = editingId; selectedSubstanceId = null }
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
                                            showFavoritesOnly = showFavoritesOnly,
                                            showArchived = showArchived,
                                            onToggleFavorites = { showFavoritesOnly = !showFavoritesOnly },
                                            onToggleArchived = { showArchived = !showArchived },
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
                                Screen.DASHBOARD   -> DashboardScreen()
                                Screen.SESSIONS    -> SessionListScreen(
                                    onNewSession = { editingSessionId = "__new__" },
                                    onEditSession = { id -> editingSessionId = id },
                                    onSessionClick = { id -> selectedTimelineSessionId = id },
                                    onLiveSession = { liveSessionId = "__new__" },
                                    showFavoritesOnly = showFavoritesOnly,
                                    showArchived = showArchived,
                                    useRelativeTime = useRelativeTime,
                                    onToggleTimeFormat = { useRelativeTime = !useRelativeTime }
                                )
                                Screen.SUBSTANCES  -> SubstanceScreen(
                                    onSubstanceClick = { id -> selectedSubstanceId = id },
                                    onNewSubstance = { editingSubstanceId = "__new__" }
                                )
                                Screen.SAFER        -> SaferScreen()
                                Screen.SETTINGS    -> SettingsScreen()
                            }
                        }
                    }
                    }
                }
            }
            }
        }
    }
}
