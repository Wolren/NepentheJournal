package app.journal.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.data.IJournalRepository
import app.journal.ui.LocalJournalRepository
import app.journal.data.SearchResult
import app.journal.model.Note
import app.journal.ui.components.DesktopScrollbar
import app.journal.ui.components.InteractionColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchOverlay(
    repo: IJournalRepository = LocalJournalRepository.current,
    onBack: () -> Unit,
    onSessionClick: (String) -> Unit,
    onSubstanceClick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }
    val notes by repo.notes.collectAsState()
    val scrollState = rememberLazyListState()

    // Debounce: repo.search scores under the repository lock, so a full search
    // must not run on every keystroke. Restarting the effect per query change
    // also cancels the search that has not fired yet.
    LaunchedEffect(query) {
        if (query.length >= 2) {
            delay(200)
            results = repo.search(query)
            hasSearched = true
        } else {
            results = emptyList()
            hasSearched = false
        }
    }

    // Grouped once per result set instead of on every recomposition.
    val groupedResults = remember(results) { results.groupBy { it.entityType } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            state = scrollState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search sessions, substances, notes...") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear",
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (query.length < 2 && hasSearched) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Type at least 2 characters to search",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (hasSearched && results.isEmpty() && query.length >= 2) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.SearchOff, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No results found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (results.isNotEmpty()) {
                for ((type, typeResults) in groupedResults) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                iconForType(type), null,
                                tint = colorForType(type),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                labelForType(type),
                                style = MaterialTheme.typography.titleSmall,
                                color = colorForType(type),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "(${typeResults.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(typeResults, key = { "${it.entityType}:${it.entityId}" }) { result ->
                        SearchResultCard(
                            result = result,
                            note = if (result.entityType == "note")
                                notes.find { it.id == result.entityId } else null,
                            onSessionClick = onSessionClick,
                            onSubstanceClick = onSubstanceClick
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
        DesktopScrollbar(scrollState)
        }
    }
}

@Composable
private fun SearchResultCard(
    result: SearchResult,
    note: Note? = null,
    onSessionClick: (String) -> Unit,
    onSubstanceClick: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                when (result.entityType) {
                    "session" -> onSessionClick(result.entityId)
                    "substance" -> onSubstanceClick(result.entityId)
                    "dose" -> onSessionClick(result.entityId)
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                iconForType(result.entityType), null,
                tint = colorForType(result.entityType).copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp).padding(top = 2.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (result.snippet.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        result.snippet,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                NoteConflictBanner(note = note)
            }
            Text(
                result.entityType,
                style = MaterialTheme.typography.labelSmall,
                color = colorForType(result.entityType).copy(alpha = 0.6f)
            )
        }
    }
}


/**
 * Contract banner (HARDENING-CONTRACTS-2026-09, section c item 7): a note that
 * lost a sync conflict keeps the losing body as a sibling, and the UI has to
 * say so. Shows the sibling count; expanding reveals the sibling bodies so the
 * other version can actually be read.
 */
@Composable
private fun NoteConflictBanner(note: Note?) {
    if (note == null || note.conflictSiblings.isEmpty()) return
    var expanded by remember(note.id, note.updatedAt) { mutableStateOf(false) }
    val siblingCount = note.conflictSiblings.size
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Default.Warning, null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp)
            )
            Text(
                if (siblingCount == 1) "1 conflicting version kept for this note"
                else "$siblingCount conflicting versions kept for this note",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    if (expanded) "Hide" else "Review",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        if (expanded) {
            note.conflictSiblings.forEach { sibling ->
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 6.dp)) {
                    Text(
                        "Version from " + sibling.deviceOrigin.ifBlank { "another device" },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sibling.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun iconForType(type: String): ImageVector = when (type) {
    "session" -> Icons.Default.DateRange
    "substance" -> Icons.Default.Science
    "note" -> Icons.Default.Note
    "dose" -> Icons.Default.Medication
    "event" -> Icons.Default.Timeline
    "effect" -> Icons.Default.Psychology
    else -> Icons.Default.Article
}

private fun colorForType(type: String): Color = when (type) {
    "session" -> Color(0xFF42A5F5)
    "substance" -> Color(0xFFAB47BC)
    "note" -> Color(0xFF66BB6A)
    "dose" -> Color(0xFFFFA726)
    "event" -> InteractionColors.severe
    "effect" -> Color(0xFF7E57C2)
    else -> Color(0xFF888888)
}

private fun labelForType(type: String): String = when (type) {
    "session" -> "Sessions"
    "substance" -> "Substances"
    "note" -> "Notes"
    "dose" -> "Doses"
    "event" -> "Timeline Events"
    "effect" -> "Effects"
    else -> type
}
