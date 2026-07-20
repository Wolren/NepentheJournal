package app.journal.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import app.journal.ui.components.*
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager
import app.journal.util.currentTimeMillis
import app.journal.util.isDesktopPlatform
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel = remember { SessionListViewModel.create() },
    onNewSession: () -> Unit = {},
    onEditSession: (String) -> Unit = {},
    onSessionClick: (String) -> Unit = {},
    onLiveSession: () -> Unit = {},
    useRelativeTime: Boolean = true,
    onToggleTimeFormat: () -> Unit = {}
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
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.onPrimary)
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
                                    viewModel.toggleSubstance(item.id); substanceDropdownExpanded = false
                                },
                                leadingIcon = {
                                    if (isSelected) {
                                        Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))) {
                                            Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.onPrimary)
                                        }
                                    } else Box(Modifier.size(18.dp))
                                }
                            )
                        }
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
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.MenuBook, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(40.dp))
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
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    itemsIndexed(sessions, key = { _, s -> s.id }) { _, session ->
                        AnimatedListItem {
                            SessionCard(
                                session = session,
                                useRelativeTime = useRelativeTime,
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

    // Delete confirmation
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
        useRelativeTime: Boolean,
        onToggleTimeFormat: () -> Unit
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
            TooltipBox(state = tipState4, positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text("Toggle time format") } }) {
                IconButton(onClick = onToggleTimeFormat, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (useRelativeTime) Icons.Default.Timer else Icons.Default.Schedule,
                        contentDescription = if (useRelativeTime) "Relative time" else "Absolute time",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: Session,
    useRelativeTime: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    val repo = remember { JournalRepository.instance }
    val themeManager = remember { ThemeManager.instance }
    val isDark = themeManager.isDarkTheme()
    val doses = remember(session.id) { repo.dosesForSession(session.id) }
    val subColor = remember(session.title) { AdaptiveColors.colorFor(session.title) }
    val accent = subColor.getComposeColor(isDark)

    // Pre-join substance names for O(1) lookup in the FlowRow below
    val substanceNameMap = remember(doses) {
        doses.associate { dose ->
            dose.substanceId to (repo.getSubstance(dose.substanceId)?.name ?: dose.substanceId)
        }
    }

    HoverCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        useAnimations = isDesktopPlatform(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)
        ) {
            // Accent bar colored by session title (substance identity)
            Surface(
                modifier = Modifier.fillMaxHeight().width(4.dp),
                color = accent
            ) {}
            Column(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }

                        Spacer(Modifier.height(2.dp))

                        // Date/time
                        val tz = TimeZone.currentSystemDefault()
                        val instant = Instant.fromEpochMilliseconds(session.startTime)
                        val local = instant.toLocalDateTime(tz)
                        val dateText = if (useRelativeTime) relativeTime(session.startTime)
                                                else "${local.day.toString().padStart(2,'0')} ${local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${local.year} ${local.hour.toString().padStart(2,'0')}:${local.minute.toString().padStart(2,'0')}"
                        Text(
                            text = dateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (session.rating != null || session.shulginRating != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    text = session.shulginRating ?: "${session.rating}/10",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        // Edit button
                        IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Substances
                if (doses.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        doses.forEach { dose ->
                            val subName = substanceNameMap[dose.substanceId]
                            if (subName != null) {
                                val doseColor = app.journal.ui.theme.AdaptiveColors.colorFor(subName)
                                val doseAccent = doseColor.getComposeColor(isDark)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = doseAccent
                                ) {
                                    Text(
                                        text = "$subName ${dose.amount} ${dose.unit}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Dose composition bar
                if (doses.isNotEmpty()) {
                    val totalAmount = doses.sumOf { it.amount }
                    if (totalAmount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            doses.forEachIndexed { i, dose ->
                                val subName = substanceNameMap[dose.substanceId]
                                val fraction = (dose.amount / totalAmount).toFloat()
                                if (fraction > 0.01f) {
                                    val color = if (subName != null)
                                        app.journal.ui.theme.AdaptiveColors.colorFor(subName).getComposeColor(isDark)
                                    else MaterialTheme.colorScheme.primary
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .weight(fraction.coerceAtLeast(0.02f)),
                                        color = color,
                                        shape = if (i == 0) RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp)
                                                else if (i == doses.lastIndex) RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)
                                                else RoundedCornerShape(0.dp)
                                    ) {}
                                }
                            }
                        }
                    }
                }

                // Favorite heart at bottom-right
                if (session.isFavorite) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = "Favorite",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun relativeTime(epochMs: Long): String {
    val now = currentTimeMillis()
    val diff = now - epochMs
    val mins = diff / 60000
    val hours = diff / 3600000
    val days = diff / 86400000
    return when {
        mins < 1 -> "Just now"
        mins < 60 -> "${mins}m ago"
        hours < 24 -> "${hours}h ago"
        days < 2 -> "Yesterday"
        days < 7 -> "${days}d ago"
        days < 30 -> "${(days / 7)}w ago"
        days < 365 -> "${(days / 30)}mo ago"
        else -> "${(days / 365)}y ago"
    }
}
