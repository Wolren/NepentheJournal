package app.journal.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.journal.data.IJournalRepository
import app.journal.model.Session
import app.journal.ui.LocalJournalRepository
import app.journal.ui.components.DesktopScrollbar
import app.journal.ui.components.*
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager
import app.journal.util.TimeDisplayMode
import app.journal.util.currentTimeMillis
import app.journal.util.formatRelativeTime
import app.journal.util.isDesktopPlatform
import app.journal.ui.session.SessionCard
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun SessionListScreen(repo: IJournalRepository = LocalJournalRepository.current,
    viewModel: SessionListViewModel,
    onNewSession: () -> Unit = {},
    onEditSession: (String) -> Unit = {},
    onSessionClick: (String) -> Unit = {},
    onLiveSession: () -> Unit = {},
    timeDisplayMode: TimeDisplayMode = TimeDisplayMode.RELATIVE,
    onCycleTimeDisplay: () -> Unit = {}
) {
    val sessions by viewModel.filteredSessions.collectAsState(initial = emptyList())
    val allDoses by repo.doses.collectAsState()
    val allSubstances by repo.substances.collectAsState()
    val dosesBySession = remember(allDoses) { allDoses.groupBy { it.sessionId } }
    val substanceNameMap = remember(allSubstances) { allSubstances.associate { it.id to it.name } }
    var showLiveDialog by remember { mutableStateOf(false) }
    var liveSessionTitle by remember { mutableStateOf("") }

    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterSubstanceIds by viewModel.filterSubstanceIds.collectAsState()
    val consumerFilter by viewModel.consumerFilter.collectAsState()
    val showFavs by viewModel.showFavoritesOnly.collectAsState()
    val allSessionSubstances by viewModel.allSessionSubstances.collectAsState(initial = emptyList())
    val allConsumers by viewModel.allConsumers.collectAsState(initial = emptyList())
    val scrollState = rememberLazyListState()

    CenteredContent(maxWidth = 1100.dp) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val showEmptyState = rememberEmptyStateVisible(isEmpty = sessions.isEmpty())
            val countText = "${sessions.size} session${if (sessions.size != 1) "s" else ""}"
            val showCount = sessions.isNotEmpty() || showEmptyState
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 720.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (showCount) {
                            Text(countText, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                        } else Spacer(Modifier.weight(1f))
                        SessionSearchBox(
                            searchQuery = searchQuery,
                            onSearchChange = { viewModel.searchQuery.value = it },
                            allSessionSubstances = allSessionSubstances,
                            filterSubstanceIds = filterSubstanceIds,
                            onToggleSubstance = { viewModel.toggleSubstance(it) },
                            onClearFilters = { viewModel.filterSubstanceIds.value = emptySet() },
                            modifier = Modifier.width(380.dp),
                        )
                    }
                } else {
                    Column {
                        SessionSearchBox(
                            searchQuery = searchQuery,
                            onSearchChange = { viewModel.searchQuery.value = it },
                            allSessionSubstances = allSessionSubstances,
                            filterSubstanceIds = filterSubstanceIds,
                            onToggleSubstance = { viewModel.toggleSubstance(it) },
                            onClearFilters = { viewModel.filterSubstanceIds.value = emptySet() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (showCount) {
                            Spacer(Modifier.height(6.dp))
                            Text(countText, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (showEmptyState) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.MenuBook, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(48.dp))
                            Text(
                                text = when {
                                    searchQuery.isNotBlank() -> "No sessions match \"$searchQuery\""
                                    filterSubstanceIds.isNotEmpty() -> "No sessions with selected substances"
                                    showFavs -> "No favorite sessions"
                                    else -> "No sessions yet"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (searchQuery.isBlank() && filterSubstanceIds.isEmpty() && !showFavs) {
                                Text(
                                    text = "Tap + to create your first session",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            } else {
                SelectableContent {
                LazyColumn(
                    state = scrollState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    itemsIndexed(sessions, key = { _, s -> s.id }) { _, session ->
                        AnimatedListItem {
                            SessionCard(
                                session = session,
                                doses = dosesBySession[session.id] ?: emptyList(),
                                substanceNameMap = substanceNameMap,
                                timeDisplayMode = timeDisplayMode,
                                onClick = { onSessionClick(session.id) },
                                onEdit = { onEditSession(session.id) },
                                onToggleFavorite = { repo.toggleFavorite(session.id) }
                            )
                        }
                    }
                }
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            FloatingActionButton(
                onClick = { 
                    liveSessionTitle = ""
                    showLiveDialog = true
                },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Live Session")
            }
            FloatingActionButton(
                onClick = onNewSession
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Session")
            }
        }

        DesktopScrollbar(scrollState)
    }
    }

    if (showLiveDialog) {
        AlertDialog(
            onDismissRequest = { showLiveDialog = false },
            title = { Text("Start Live Session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A live session is a calm, focused workspace for tracking an ongoing experience in real-time.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = liveSessionTitle,
                        onValueChange = { liveSessionTitle = it },
                        label = { Text("Session title (optional)") },
                        placeholder = { Text("Evening exploration") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                AppButton(onClick = {
                    showLiveDialog = false
                    val now = currentTimeMillis()
                    val session = Session(
                        id = "session:live:${now}",
                        title = liveSessionTitle.ifBlank { "Live Session" },
                        startTime = now,
                        createdAt = now, updatedAt = now,
                        deviceOrigin = "desktop"
                    )
                    viewModel.repo.upsertSession(session)
                    onSessionClick(session.id)
                }) { Text("Start") }
            },
            dismissButton = {
                AppTextButton(onClick = { showLiveDialog = false }) { Text("Cancel") }
            }
        )
    }

}

/**
 * Action icons rendered into the shared TopAppBar (right side) when the
 * Sessions tab is active. Title stays left-aligned in the bar.
 */
object SessionListScreen {
    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    fun TopActions(
        showFavoritesOnly: Boolean,
        onToggleFavorites: () -> Unit,
        onCalendarClick: () -> Unit,
        timeDisplayMode: TimeDisplayMode,
        onCycleTimeDisplay: () -> Unit
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val tipState1 = rememberTooltipState()
            TooltipBox(state = tipState1, positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text("Calendar") } }) {
                IconButton(onClick = onCalendarClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = "Calendar",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            val tipState2 = rememberTooltipState()
            TooltipBox(state = tipState2, positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text("Favorites") } }) {
                IconButton(onClick = onToggleFavorites, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (showFavoritesOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorites",
                        modifier = Modifier.size(20.dp),
                        tint = if (showFavoritesOnly) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val tipState4 = rememberTooltipState()
            val (timeIcon, timeLabel) = when (timeDisplayMode) {
                TimeDisplayMode.RELATIVE -> Icons.Default.Timer to "Relative time"
                TimeDisplayMode.CLOCK -> Icons.Default.Schedule to "Clock time"
                TimeDisplayMode.ELAPSED -> Icons.Default.Timeline to "Elapsed"
                TimeDisplayMode.DURATION -> Icons.Default.HourglassEmpty to "Duration"
            }
            TooltipBox(state = tipState4, positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(timeLabel) } }) {
                IconButton(onClick = onCycleTimeDisplay, modifier = Modifier.size(36.dp)) {
                    Icon(
                        timeIcon,
                        contentDescription = timeLabel,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionSearchBox(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    allSessionSubstances: List<SubstanceFilterItem>,
    filterSubstanceIds: Set<String>,
    onToggleSubstance: (String) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search sessions...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            Icons.Default.Label,
                            contentDescription = "Filter by substance",
                            tint = if (filterSubstanceIds.isNotEmpty()) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 0.dp)
        ) {
            DropdownMenuItem(
                text = { Text("Clear filters", fontWeight = if (filterSubstanceIds.isEmpty()) FontWeight.Bold else FontWeight.Normal) },
                onClick = { onClearFilters(); expanded = false },
                leadingIcon = {
                    if (filterSubstanceIds.isEmpty()) {
                        Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    } else Box(Modifier.size(18.dp))
                }
            )
            if (allSessionSubstances.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No substances used yet", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { expanded = false },
                    enabled = false
                )
            } else {
                allSessionSubstances.forEach { item ->
                    val isSelected = item.id in filterSubstanceIds
                    DropdownMenuItem(
                        text = { Text(item.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { onToggleSubstance(item.id) },
                        leadingIcon = {
                            if (isSelected) {
                                Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))) {
                                    Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            } else Box(Modifier.size(18.dp))
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Done", fontWeight = FontWeight.Bold) },
                    onClick = { expanded = false },
                    leadingIcon = { Box(Modifier.size(18.dp)) }
                )
            }
        }
    }
}
