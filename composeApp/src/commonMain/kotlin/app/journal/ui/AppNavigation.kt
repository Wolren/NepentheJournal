/*
 * Nepenthe Journal - GPLv3
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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import app.journal.data.DataInitializer
import app.journal.data.IJournalRepository
import app.journal.data.JournalStore
import app.journal.data.QuickLog
import app.journal.data.QuickLogOutcome
import app.journal.model.Session
import app.journal.sync.createSyncEngine
import app.journal.util.PendingQuickLog
import app.journal.util.currentTimeMillis
import app.journal.util.isDesktopPlatform
import app.journal.util.isSoftwareRender
import app.journal.util.platformDeviceOrigin
import app.journal.ui.pet.PetCompanion
import app.journal.ui.pet.PetCorner
import app.journal.ui.pet.PetManager
import app.journal.ui.pet.PetState
import app.journal.util.TimeDisplayMode
import app.journal.ui.components.SubstanceLibraryGate
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
import app.journal.ui.theme.ThemeConfig
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

enum class Screen(
    val label: String,
    val filledIcon: ImageVector,
    val outlinedIcon: ImageVector
) {
    DASHBOARD("Dashboard", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    SESSIONS("Sessions", Icons.AutoMirrored.Filled.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
    SUBSTANCES("Substances", Icons.Filled.Science, Icons.Outlined.Science),
    SAFER("Safety", Icons.Filled.Warning, Icons.Outlined.Warning),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppNavigation(
    repo: IJournalRepository,
    journalStore: JournalStore,
    themeConfig: ThemeConfig,
    colorScheme: ColorScheme,
) {
    var selectedScreen by remember { mutableStateOf(Screen.DASHBOARD) }
    var quickLogStatus by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var editingSessionId by remember { mutableStateOf<String?>(null) }
    var selectedTimelineSessionId by remember { mutableStateOf<String?>(null) }
    var showCalendar by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }
    var selectedSubstanceId by remember { mutableStateOf<String?>(null) }
    var editingSubstanceId by remember { mutableStateOf<String?>(null) }
    // A nepenthe://quicklog link logs its dose on arrival, then opens that session.
    LaunchedEffect(Unit) {
        PendingQuickLog.uri.filterNotNull().collect { raw ->
            val request = QuickLog.parse(raw) ?: return@collect
            PendingQuickLog.consume()
            // Wait for the startup snapshot apply: it replaces the repository and would drop these writes.
            app.journal.data.DataInitializer.initializedFlow.first { it }
            // Substances arrive with the bundled library; a link that lands first would find none.
            app.journal.data.DataInitializer.libraryReadyFlow.first { it }
            when (val outcome = QuickLog.apply(repo, request)) {
                is QuickLogOutcome.Logged -> {
                    quickLogStatus = "Logged ${outcome.summary}"
                    selectedTimelineSessionId = outcome.sessionId
                }
                is QuickLogOutcome.Failed -> quickLogStatus = outcome.reason
            }
        }
    }
    LaunchedEffect(quickLogStatus) {
        quickLogStatus?.let {
            snackbarHostState.showSnackbar(it)
            quickLogStatus = null
        }
    }
    var timeDisplayMode by remember { mutableStateOf(TimeDisplayMode.RELATIVE) }
    var sessionPaneFraction by remember { mutableStateOf(0.42f) }
    val sessionListViewModel = remember { SessionListViewModel.create(repo) }
    val syncEngine = remember(repo) {
        // Single persistence owner: the engine persists through this store instead of constructing its own (audit C5).
        createSyncEngine(repo, persist = { journalStore.save(fullBackup = false) })
    }
    val showFavs by sessionListViewModel.showFavoritesOnly.collectAsState()
    var liveSessionId by remember { mutableStateOf<String?>(null) }
    var companionSubstanceId by remember { mutableStateOf<String?>(null) }
    // Substance ids and interaction warnings come from the bundled library; every dosing surface waits for it.
    val libraryReady by DataInitializer.libraryReadyFlow.collectAsState()
    val paletteSubstances by repo.substances.collectAsState()
    val paletteSessions by repo.sessions.collectAsState()
    val petSettings by PetManager.instance.settings.collectAsState()
    val paletteCommands = remember(paletteSubstances, paletteSessions) {
        buildList {
            Screen.entries.forEach { screen ->
                add(CommandItem(screen.label, "Open screen", "Screen") { selectedScreen = screen })
            }
            add(CommandItem("New session", "Open the session editor", "Action") { editingSessionId = "__new__" })
            add(CommandItem("Start live session", "Start a timer now", "Action") { liveSessionId = "__new__" })
            add(CommandItem("Calendar", "Sessions by day", "Action") { showCalendar = true })
            add(CommandItem("Search", "Full text search", "Action") { showSearch = true })
            paletteSubstances.sortedBy { it.name.lowercase() }.forEach { substance ->
                add(CommandItem(substance.name, substance.substanceClass.joinToString(", "), "Substance") {
                    selectedSubstanceId = substance.id
                })
            }
            paletteSessions.sortedByDescending { it.startTime }.forEach { session ->
                add(CommandItem(session.title, "Session", "Session") { selectedTimelineSessionId = session.id })
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colorScheme.background
    ) {
        val desktopHandler = if (isDesktopPlatform()) {
            Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.isCtrlPressed) {
                    when (event.key) {
                        Key.F -> { showSearch = true; true }
                        Key.N -> { liveSessionId = "__new__"; true }
                        Key.K -> { showPalette = true; true }
                        else -> false
                    }
                } else false
            }
        } else Modifier
        Box(Modifier.fillMaxSize().then(desktopHandler)) {
            BackgroundImage(
                imagePath = themeConfig.backgroundImagePath,
                opacity = themeConfig.backgroundOpacity
            )

            val editingSession = remember(editingSessionId) {
                editingSessionId?.let { id ->
                    if (id == "__new__") null
                    else repo.getSession(id)
                }
            }
            val editingSubstance = remember(editingSubstanceId) {
                editingSubstanceId?.let { id ->
                    if (id == "__new__") null
                    else repo.getSubstance(id)
                }
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
            val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
            val wideShell = windowWidth >= 840.dp
            val animationScale = themeConfig.animationScale.coerceIn(0f, 1f)
            val animationSpeed = 0.4f + 0.6f * animationScale
            val slideSpec: androidx.compose.animation.core.FiniteAnimationSpec<IntOffset> = remember(isSoftwareRender, animationSpeed) {
                spring(dampingRatio = 1f, stiffness = (if (isSoftwareRender) 6000f else 4000f) * animationSpeed)
            }
            val fadeSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> = remember(isSoftwareRender, animationSpeed) {
                spring(dampingRatio = 1f, stiffness = (if (isSoftwareRender) 6000f else 4000f) * animationSpeed)
            }

            AnimatedContent(
                targetState = when {
                    showSearch -> NavigationState.Search
                    companionSubstanceId != null -> NavigationState.CompanionSubstance
                    liveSessionId != null -> NavigationState.LiveSession
                    editingSessionId != null -> NavigationState.EditorSession
                    !wideShell && selectedTimelineSessionId != null -> NavigationState.Timeline
                    editingSubstanceId != null -> NavigationState.EditorSubstance
                    selectedSubstanceId != null -> NavigationState.DetailSubstance
                    showCalendar -> NavigationState.Calendar
                    else -> NavigationState.Main
                },
                transitionSpec = {
                    if (animationScale <= 0.01f) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else if (targetState is NavigationState.Main) {
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
                    NavigationState.Search -> {
                        SearchOverlay(
                            repo = repo,
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
                    NavigationState.LiveSession -> {
                        val session = remember(stableLiveId) {
                            stableLiveId?.let { id ->
                                if (id == "__new__") null
                                else repo.getSession(id)
                            }
                        }
                        if (!libraryReady) {
                            SubstanceLibraryGate(onBack = { liveSessionId = null })
                        } else if (session != null) {
                            LiveSessionScreen(
                                repo = repo,
                                session = session,
                                onBack = { liveSessionId = null }
                            )
                        } else {
                            var liveCreateError by remember { mutableStateOf<String?>(null) }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Starting session...")
                                    liveCreateError?.let { err ->
                                        Spacer(Modifier.height(8.dp))
                                        Text(err, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            val liveCreateKey = stableLiveId
                            LaunchedEffect(liveCreateKey) {
                                if (liveCreateKey != "__new__") return@LaunchedEffect
                                val openLive = repo.sessions.value.firstOrNull {
                                    it.id.startsWith("session:live:") && it.endTime == null
                                }
                                if (openLive != null) {
                                    liveSessionId = openLive.id
                                    return@LaunchedEffect
                                }
                                try {
                                    val now = currentTimeMillis()
                                val newSession = Session(
                                    id = "session:live:${now}",
                                    title = "Live Session",
                                    startTime = now,
                                    createdAt = now, updatedAt = now,
                                    deviceOrigin = platformDeviceOrigin()
                                )
                                repo.upsertSession(newSession)
                                liveSessionId = newSession.id
                                } catch (e: Exception) {
                                    liveCreateError = "Could not start session: ${e.message ?: "unknown error"}"
                                }
                            }
                        }
                    }
                    NavigationState.EditorSession -> {
                        if (!libraryReady) {
                            SubstanceLibraryGate(onBack = { editingSessionId = null })
                        } else {
                            SessionEditorScreen(
                                repo = repo,
                                sessionToEdit = editingSession,
                                onBack = { editingSessionId = null }
                            )
                        }
                    }
                    NavigationState.Timeline -> {
                        val id = stableTimelineId
                        if (id != null) {
                            SessionTimelineScreen(
                                repo = repo,
                                sessionId = id,
                                onBack = { selectedTimelineSessionId = null }
                            )
                        }
                    }
                    NavigationState.EditorSubstance -> {
                        SubstanceEditorScreen(
                            repo = repo,
                            substanceToEdit = editingSubstance,
                            onBack = { editingSubstanceId = null }
                        )
                    }
                    NavigationState.DetailSubstance -> {
                        val id = stableSubstanceId
                        if (id != null) {
                            SubstanceDetailScreen(
                                repo = repo,
                                substanceId = id,
                                onBack = { selectedSubstanceId = null },
                                onEdit = { editingId -> editingSubstanceId = editingId; selectedSubstanceId = null },
                                onCompanion = { companionSubstanceId = id },
                                onOpenSubstance = { selectedSubstanceId = it },
                            )
                        }
                    }
                    NavigationState.CompanionSubstance -> {
                        val id = companionSubstanceId
                        if (id != null) {
                            SubstanceCompanionScreen(
                                repo = repo,
                                substanceId = id,
                                onBack = { companionSubstanceId = null },
                                onSessionClick = { sessionId: String ->
                                    selectedTimelineSessionId = sessionId
                                    companionSubstanceId = null
                                }
                            )
                        }
                    }
                    NavigationState.Calendar -> {
                        CalendarScreen(
                            repo = repo,
                            onBack = { showCalendar = false },
                            onSessionTap = { id -> selectedTimelineSessionId = id; showCalendar = false }
                        )
                    }
                    NavigationState.Main -> {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wide = maxWidth >= 840.dp
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            topBar = {
                                if (!wide && selectedScreen == Screen.SESSIONS) {
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
                                                        onToggleFavorites = { sessionListViewModel.showFavoritesOnly.value = !showFavs },
                                                        onCalendarClick = { showCalendar = true },
                                                        timeDisplayMode = timeDisplayMode,
                                                        onCycleTimeDisplay = {
                                                            timeDisplayMode = TimeDisplayMode.entries[
                                                                (timeDisplayMode.ordinal + 1) % TimeDisplayMode.entries.size
                                                            ]
                                                        }
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
                                if (!wide) {
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
                            }
                        ) { innerPadding ->
                            Row(Modifier.padding(innerPadding).fillMaxSize()) {
                                if (wide) {
                                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                                        Screen.entries.forEach { screen ->
                                            NavigationRailItem(
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
                                Box(Modifier.weight(1f).fillMaxSize()) {
                                when (selectedScreen) {
                                    Screen.DASHBOARD -> DashboardScreen(
                                        repo = repo,
                                        onSearchClick = { showSearch = true }
                                    )
                                    Screen.SESSIONS -> {
                                        val listContent: @Composable () -> Unit = {
                                            SessionListScreen(
                                                viewModel = sessionListViewModel,
                                                showInlineActions = false,
                                                compactActions = wide,
                                                selectedSessionId = selectedTimelineSessionId,
                                                onCalendarClick = { showCalendar = true },
                                                onNewSession = { editingSessionId = "__new__" },
                                                onEditSession = { id -> editingSessionId = id },
                                                onSessionClick = { id ->
                                                    if (repo.getSession(id)?.endTime == null) liveSessionId = id
                                                    else selectedTimelineSessionId = id
                                                },
                                                onLiveSession = { liveSessionId = "__new__" },
                                                timeDisplayMode = timeDisplayMode,
                                                onCycleTimeDisplay = {
                                                    timeDisplayMode = TimeDisplayMode.entries[
                                                        (timeDisplayMode.ordinal + 1) % TimeDisplayMode.entries.size
                                                    ]
                                                }
                                            )
                                        }
                                        if (wide) {
                                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                                val totalPx = with(LocalDensity.current) { maxWidth.toPx() }
                                                val listFraction = sessionPaneFraction.coerceIn(0.25f, 0.65f)
                                                Row(Modifier.fillMaxSize()) {
                                                    Box(Modifier.weight(listFraction).fillMaxHeight()) { listContent() }
                                                    Box(
                                                        modifier = Modifier
                                                            .width(8.dp)
                                                            .fillMaxHeight()
                                                            .pointerInput(totalPx) {
                                                                detectHorizontalDragGestures { change, dragAmount ->
                                                                    change.consume()
                                                                    if (totalPx > 0f) {
                                                                        sessionPaneFraction = (sessionPaneFraction + dragAmount / totalPx)
                                                                            .coerceIn(0.25f, 0.65f)
                                                                    }
                                                                }
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Box(
                                                            Modifier
                                                                .width(1.dp)
                                                                .fillMaxHeight()
                                                                .background(MaterialTheme.colorScheme.outlineVariant)
                                                        )
                                                    }
                                                    Box(Modifier.weight(1f - listFraction).fillMaxHeight()) {
                                                        val paneSessionId = selectedTimelineSessionId
                                                        if (paneSessionId != null) {
                                                            SessionTimelineScreen(
                                                                repo = repo,
                                                                sessionId = paneSessionId,
                                                                onBack = { selectedTimelineSessionId = null }
                                                            )
                                                        } else {
                                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                                Text(
                                                                    "Select a session",
                                                                    style = MaterialTheme.typography.bodyLarge,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            listContent()
                                        }
                                    }
                                    Screen.SUBSTANCES -> if (!libraryReady) {
                                        SubstanceLibraryGate()
                                    } else {
                                        SubstanceScreen(
                                            onSubstanceClick = { id -> selectedSubstanceId = id },
                                            onNewSubstance = { editingSubstanceId = "__new__" }
                                        )
                                    }
                                    Screen.SAFER -> SaferScreen()
                                    Screen.SETTINGS -> SettingsScreen(repo = repo, syncEngine = syncEngine)
                                }  // closes when(selectedScreen)
                                }  // closes inner Box(weight)
                            }  // closes Row
                        }  // closes Scaffold innerPadding
                        }  // closes BoxWithConstraints
                    }  // closes "main" block
                    }  // closes when(state)
                }  // closes Box wrapper (background flash fix)
            }  // closes AnimatedContent transitionSpec

            if (petSettings.enabled) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    contentAlignment = when (petSettings.corner) {
                        PetCorner.TOP_START -> Alignment.TopStart
                        PetCorner.TOP_END -> Alignment.TopEnd
                        PetCorner.BOTTOM_START -> Alignment.BottomStart
                        PetCorner.BOTTOM_END -> Alignment.BottomEnd
                    }
                ) {
                    PetCompanion(state = if (liveSessionId != null) PetState.RUNNING else PetState.IDLE)
                }
            }
            if (showPalette) {
                CommandPalette(commands = paletteCommands, onDismiss = { showPalette = false })
            }
        }  // closes outer Box(fillMaxSize)
    }  // closes Surface
}
