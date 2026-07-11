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
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun SessionListScreen(
    onNewSession: () -> Unit = {},
    onEditSession: (String) -> Unit = {},
    onSessionClick: (String) -> Unit = {},
    onLiveSession: () -> Unit = {},
    showFavoritesOnly: Boolean = false,
    showArchived: Boolean = false,
    useRelativeTime: Boolean = true,
    onToggleTimeFormat: () -> Unit = {}
) {
    val repo = remember { JournalRepository.instance }
    val sessions by repo.sessions.collectAsState(initial = emptyList())
    var filterTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showLiveDialog by remember { mutableStateOf(false) }
    var liveSessionTitle by remember { mutableStateOf("") }
    var consumerFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var tagDropdownExpanded by remember { mutableStateOf(false) }

    val allTags = remember(sessions) {
        sessions.flatMap { it.tags }.distinct().sorted()
    }

    val allConsumers = remember(sessions) {
        sessions.mapNotNull { it.consumerName }.distinct().sorted()
    }

    val filteredSessions = remember(sessions, filterTags, showFavoritesOnly, showArchived, consumerFilter, searchQuery) {
        var result = sessions
        if (filterTags.isNotEmpty()) result = result.filter { s -> s.tags.any { it in filterTags } }
        if (showFavoritesOnly) result = result.filter { it.isFavorite }
        if (!showArchived) result = result.filter { !it.isArchived }
        if (consumerFilter != null) result = result.filter { it.consumerName == consumerFilter }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            result = result.filter { s ->
                s.title.lowercase().contains(q) ||
                s.tags.any { it.lowercase().contains(q) } ||
                s.intention?.lowercase()?.contains(q) == true
            }
        }
        result.sortedByDescending { it.startTime }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Search bar with tag filter as trailing icon
            Box {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search sessions...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                            IconButton(onClick = { tagDropdownExpanded = true }) {
                                Icon(
                                    Icons.Default.Label,
                                    contentDescription = "Filter by tag",
                                    tint = if (filterTags.isNotEmpty()) MaterialTheme.colorScheme.primary
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
                    expanded = tagDropdownExpanded,
                    onDismissRequest = { tagDropdownExpanded = false },
                    offset = DpOffset(0.dp, 0.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("Clear all filters", fontWeight = if (filterTags.isEmpty()) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { filterTags = emptySet(); tagDropdownExpanded = false },
                        leadingIcon = {
                            if (filterTags.isEmpty()) {
                                Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            } else Box(Modifier.size(18.dp))
                        }
                    )
                    if (allTags.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No tags yet", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = { tagDropdownExpanded = false },
                            enabled = false
                        )
                    } else {
                        allTags.forEach { tag ->
                            val isSelected = tag in filterTags
                            DropdownMenuItem(
                                text = { Text(tag, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                onClick = {
                                    filterTags = if (isSelected) filterTags - tag else filterTags + tag
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
                text = "${filteredSessions.size} session${if (filteredSessions.size != 1) "s" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))

            // Session list
            if (filteredSessions.isEmpty()) {
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
                                filterTags.isNotEmpty() -> "No sessions with selected tags"
                                showFavoritesOnly -> "No favorite sessions"
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
                    itemsIndexed(filteredSessions, key = { _, s -> s.id }) { _, session ->
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
                        tags = emptyList(),
                        createdAt = now, updatedAt = now,
                        deviceOrigin = "desktop"
                    )
                    repo.upsertSession(session)
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
                    showDeleteConfirm?.let { repo.deleteSession(it) }
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

    HoverCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
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
                            if (session.isFavorite) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = "Favorite",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(2.dp))

                        // Date/time
                        val tz = TimeZone.currentSystemDefault()
                        val instant = Instant.fromEpochMilliseconds(session.startTime)
                        val local = instant.toLocalDateTime(tz)
                        val dateText = if (useRelativeTime) relativeTime(session.startTime)
                        else "${local.year}-${(local.month.ordinal + 1).toString().padStart(2,'0')}-${local.day.toString().padStart(2,'0')} ${local.hour.toString().padStart(2,'0')}:${local.minute.toString().padStart(2,'0')}"
                        Text(
                            text = dateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        var menuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Menu",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp))
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                                offset = DpOffset(0.dp, 0.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    onClick = { menuExpanded = false; onEdit() },
                                    leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menuExpanded = false; onDelete() },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                                )
                            }
                        }
                    }
                }

                // Substances (neutral tags, no substance-specific colors)
                if (doses.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        doses.forEach { dose ->
                            val substance = repo.getSubstance(dose.substanceId)
                            if (substance != null) {
                                val doseColor = app.journal.ui.theme.AdaptiveColors.colorFor(substance.name)
                                val doseAccent = doseColor.getComposeColor(isDark)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = doseAccent.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "${substance.name} ${dose.amount} ${dose.unit}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = doseAccent,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Tags
                if (session.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        session.tags.take(4).forEach { tag ->
                            TagChip(tag = tag)
                        }
                        if (session.tags.size > 4) {
                            Text("+${session.tags.size - 4}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically))
                        }
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
