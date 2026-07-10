package app.journal.ui.substances

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.Substance
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager
import app.journal.util.currentTimeMillis
import app.journal.ui.components.*

@Composable
fun SubstanceScreen(
    onSubstanceClick: (String) -> Unit = {},
    onNewSubstance: () -> Unit = {}
) {
    val repo = remember { JournalRepository.instance }
    val substances by repo.substances.collectAsState(initial = emptyList())
    val allDoses by repo.doses.collectAsState()
    val themeManager = remember { ThemeManager.instance }
    val isDark = themeManager.isDarkTheme()
    var query by remember { mutableStateOf("") }
    var activeCategories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var categoryDropdownExpanded by remember { mutableStateOf(false) }

    // Extract all unique substance classes across substances with real data
    val realSubstances = remember(substances) {
        substances.filter { sub ->
            sub.routesOfAdministration.isNotEmpty() ||
            sub.effects.isNotEmpty() ||
            sub.dosageBands.isNotEmpty()
        }
    }
    val allCategories = remember(realSubstances) {
        realSubstances.flatMap { it.substanceClass }.distinct().sorted()
    }

    // Filtered results (excluding category entries with no consumption data)
    val results = remember(query, realSubstances, activeCategories) {
        var result = realSubstances

        // Category filter
        if (activeCategories.isNotEmpty()) {
            result = result.filter { sub ->
                sub.substanceClass.any { it in activeCategories }
            }
        }

        // Text search
        if (query.isNotBlank()) {
            val q = query.lowercase()
            result = result.filter { sub ->
                sub.name.lowercase().contains(q) ||
                sub.aliases.any { it.lowercase().contains(q) } ||
                sub.substanceClass.any { it.lowercase().contains(q) }
            }
        }

        result
    }

    // Precompute dose stats per substance once instead of filtering per card.
    // Map: substanceId -> (distinctSessionCount, lastUsedTimestamp)
    val substanceDoseStats = remember(allDoses) {
        val sessionIdsPerSub = mutableMapOf<String, MutableSet<String>>()
        val lastUsedPerSub = mutableMapOf<String, Long>()
        for (dose in allDoses) {
            sessionIdsPerSub.getOrPut(dose.substanceId) { mutableSetOf() }.add(dose.sessionId)
            val existing = lastUsedPerSub[dose.substanceId] ?: 0L
            if (dose.timestamp > existing) lastUsedPerSub[dose.substanceId] = dose.timestamp
        }
        sessionIdsPerSub.mapValues { (key, sessionIds) ->
            Pair(sessionIds.size, lastUsedPerSub[key])
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Search bar with category filter as trailing icon
            Box {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search substances...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                            IconButton(onClick = { categoryDropdownExpanded = true }) {
                                Icon(
                                    Icons.Default.Label,
                                    contentDescription = "Filter by category",
                                    tint = if (activeCategories.isNotEmpty()) MaterialTheme.colorScheme.primary
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
                    expanded = categoryDropdownExpanded,
                    onDismissRequest = { categoryDropdownExpanded = false },
                    offset = DpOffset(0.dp, 0.dp)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text("All categories",
                                fontWeight = if (activeCategories.isEmpty()) FontWeight.Bold else FontWeight.Normal)
                        },
                        onClick = { activeCategories = emptySet(); categoryDropdownExpanded = false },
                        leadingIcon = {
                            if (activeCategories.isEmpty()) {
                                Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            } else Box(Modifier.size(18.dp))
                        }
                    )
                    if (allCategories.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No categories", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = { categoryDropdownExpanded = false },
                            enabled = false
                        )
                    } else {
                        allCategories.forEach { category ->
                            val isSelected = category in activeCategories
                            DropdownMenuItem(
                                text = {
                                    Text(category,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                },
                                onClick = {
                                    activeCategories = if (isSelected) activeCategories - category
                                        else activeCategories + category
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

            Spacer(Modifier.height(4.dp))

            // Results list
            if (results.isEmpty() && query.isNotEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "No substances match \"$query\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppOutlinedButton(onClick = onNewSubstance) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add custom substance")
                        }
                    }
                }
            } else if (results.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No substances in database",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AppOutlinedButton(onClick = onNewSubstance) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add custom substance")
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results, key = { it.id }) { substance ->
                        val stats = substanceDoseStats[substance.id]
                        val sessionCount = stats?.first ?: 0
                        val lastUsed = stats?.second
                        val lastUsedDaysAgo = lastUsed?.let {
                            ((currentTimeMillis() - it) / 86400000L).toInt()
                        }
                        SubstanceCard(
                            substance = substance,
                            isDark = isDark,
                            onClick = { onSubstanceClick(substance.id) },
                            sessionCount = sessionCount,
                            lastUsedDaysAgo = lastUsedDaysAgo
                        )
                    }
                    // Add custom substance button at the bottom
                    item {
                        Spacer(Modifier.height(4.dp))
                        AppOutlinedButton(
                            onClick = onNewSubstance,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add custom substance")
                        }
                        Spacer(Modifier.height(72.dp))
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = onNewSubstance,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "New Substance")
        }
    }
}

@Composable
private fun SubstanceCard(
    substance: Substance,
    isDark: Boolean,
    onClick: () -> Unit,
    sessionCount: Int = 0,
    lastUsedDaysAgo: Int? = null
) {
    val color = app.journal.ui.theme.AdaptiveColors.colorFor(substance.name)

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically) {
            // Color indicator bar
            Surface(
                modifier = Modifier.width(4.dp).height(48.dp),
                shape = RoundedCornerShape(2.dp),
                color = color.getComposeColor(isDark)
            ) {}
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = substance.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (substance.aliases.isNotEmpty()) {
                    Text(
                        text = substance.aliases.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Usage stats
                if (sessionCount > 0 || lastUsedDaysAgo != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (sessionCount > 0) {
                            Text(
                                "$sessionCount session${if (sessionCount != 1) "s" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (lastUsedDaysAgo != null) {
                            Text(
                                when {
                                    lastUsedDaysAgo == 0 -> "Used today"
                                    lastUsedDaysAgo == 1 -> "Used yesterday"
                                    lastUsedDaysAgo < 7 -> "${lastUsedDaysAgo}d ago"
                                    lastUsedDaysAgo < 30 -> "${lastUsedDaysAgo / 7}w ago"
                                    else -> "${lastUsedDaysAgo / 30}mo ago"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
