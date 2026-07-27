package app.journal.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import app.journal.data.JournalRepository
import app.journal.data.SearchResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchOverlay(
    repo: JournalRepository = JournalRepository.instance,
    onBack: () -> Unit,
    onSessionClick: (String) -> Unit,
    onSubstanceClick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }

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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { q ->
                        query = q
                        if (q.length >= 2) {
                            results = repo.search(q)
                            hasSearched = true
                        } else {
                            results = emptyList()
                            hasSearched = false
                        }
                    },
                    placeholder = { Text("Search sessions, substances, notes...") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; results = emptyList(); hasSearched = false }) {
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

            if (results.isEmpty() && query.length >= 2) {
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
                val grouped = results.groupBy { it.entityType }
                for ((type, typeResults) in grouped) {
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
                            onSessionClick = onSessionClick,
                            onSubstanceClick = onSubstanceClick
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: SearchResult,
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
            }
            Text(
                result.entityType,
                style = MaterialTheme.typography.labelSmall,
                color = colorForType(result.entityType).copy(alpha = 0.6f)
            )
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
    "event" -> Color(0xFFEF5350)
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
