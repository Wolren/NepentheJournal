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
import app.journal.data.JournalRepository
import app.journal.model.Session
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
fun SessionListScreen(repo: JournalRepository = JournalRepository.instance,
    viewModel: SessionListViewModel,
    onNewSession: () -> Unit = {},
    onEditSession: (String) -> Unit = {},
    onSessionClick: (String) -> Unit = {},
    onLiveSession: () -> Unit = {},
    timeDisplayMode: TimeDisplayMode = TimeDisplayMode.RELATIVE,
    onCycleTimeDisplay: () -> Unit = {}
) {
    val sessions by viewModel.filteredSessions.collectAsState(initial = emptyList())
    val allSessions by viewModel.sessions.collectAsState(initial = emptyList())
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showLiveDialog by remember { mutableStateOf(false) }
    var liveSessionTitle by remember { mutableStateOf("") }
    var substanceDropdownExpanded by remember { mutableStateOf(false) }

    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterSubstanceIds by viewModel.filterSubstanceIds.collectAsState()
    val consumerFilter by viewModel.consumerFilter.collectAsState()
    val showFavs by viewModel.showFavoritesOnly.collectAsState()
    val showArch by viewModel.showArchived.collectAsState()
    val allSessionSubstances by viewModel.allSessionSubstances.collectAsState(initial = emptyList())
    val allConsumers by viewModel.allConsumers.collectAsState(initial = emptyList())
    val scrollState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Search bar with substance filter as trailing icon
            Box {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.searchQuery.value = it },
                    placeholder = { Text("Search sessions...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                            IconButton(onClick = { substanceDropdownExpanded = true }) {
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
                    expanded = substanceDropdownExpanded,
                    onDismissRequest = { substanceDropdownExpanded = false },
                    offset = DpOffset(0.dp, 0.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("Clear filters", fontWeight = if (filterSubstanceIds.isEmpty()) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { viewModel.filterSubstanceIds.value = emptySet(); substanceDropdownExpanded = false },
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
                            onClick = { substanceDropdownExpanded = false },
                            enabled = false
                        )
                    } else {
                        allSessionSubstances.forEach { item ->
                            val isSelected = item.id in filterSubstanceIds
                            DropdownMenuItem(
                                text = { Text(item.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                onClick = {
                                    // Keep the menu open so multiple substances can be toggled in one pass
                                    viewModel.toggleSubstance(item.id)
                                },
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
                            onClick = { substanceDropdownExpanded = false },
                            leadingIcon = { Box(Modifier.size(18.dp)) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Count row
            Text(
                text = "${sessions.size} session${if (sessions.size != 1) "s" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))

            // Session list
            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
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
            } else {
                LazyColumn(
                    state = scrollState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    itemsIndexed(sessions, key = { _, s -> s.id }) { _, session ->
                        AnimatedListItem {
                            SessionCard(
                                session = session,
                                timeDisplayMode = timeDisplayMode,
                                onClick = { onSessionClick(session.id) },
                                onDelete = { showDeleteConfirm = session.id },
                                onEdit = { onEditSession(session.id) }
                            )
                        }
                    }
                }
            }
        }

        // FABs
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

    // Live session confirmation dialog
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
                    // Create and start the live session with the chosen title
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

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete session?") },
            text = { Text("This will also remove all doses, notes, and timeline events for this session.") },
            confirmButton = {
                AppTextButton(onClick = {
                    showDeleteConfirm?.let { viewModel.repo.deleteSession(it) }
                    showDeleteConfirm = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                AppTextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
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
        showArchived: Boolean,
        onToggleFavorites: () -> Unit,
        onToggleArchived: () -> Unit,
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
            val tipState3 = rememberTooltipState()
            TooltipBox(state = tipState3, positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text("Archived") } }) {
                IconButton(onClick = onToggleArchived, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = "Archived",
                        modifier = Modifier.size(20.dp),
                        tint = if (showArchived) MaterialTheme.colorScheme.primary
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
