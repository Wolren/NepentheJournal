package app.journal.ui.substances

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
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
import app.journal.model.Substance
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager
import app.journal.util.currentTimeMillis
import app.journal.util.isDesktopPlatform
import app.journal.ui.components.*

@Composable
fun SubstanceScreen(
    viewModel: SubstanceScreenViewModel = remember { SubstanceScreenViewModel.create() },
    onSubstanceClick: (String) -> Unit = {},
    onNewSubstance: () -> Unit = {}
) {
    val substances by viewModel.realSubstances.collectAsState(initial = emptyList())
    val results by viewModel.results.collectAsState(initial = emptyList())
    val allCategories by viewModel.allCategories.collectAsState(initial = emptyList())
    val themeManager = remember { ThemeManager.instance }
    val isDark = themeManager.isDarkTheme()
    val substanceDoseStats = viewModel.substanceDoseStats

    val query by viewModel.query.collectAsState()
    val activeCategories by viewModel.activeCategories.collectAsState()
    var categoryDropdownExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Search bar with category filter as trailing icon
            Box {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.query.value = it },
                    placeholder = { Text("Search substances...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.query.value = "" }) {
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
                        onClick = { viewModel.activeCategories.value = emptySet(); categoryDropdownExpanded = false },
                        leadingIcon = {
                            if (activeCategories.isEmpty()) {
                                Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))) {
                                    Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.onPrimary)
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
                                    viewModel.activeCategories.value = if (isSelected) activeCategories - category
                                        else activeCategories + category
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
                            onClick = { categoryDropdownExpanded = false },
                            leadingIcon = { Box(Modifier.size(18.dp)) }
                        )
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
                        Icon(Icons.Default.Science, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp))
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
                        Icon(Icons.Default.Science, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp))
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
                LazyColumn(
                    state = scrollState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(results, key = { _, s -> s.id }) { _, substance ->
                        AnimatedListItem {
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
                    }
                    item { Spacer(Modifier.height(72.dp)) }
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

        DesktopScrollbar(scrollState)
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

    HoverCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        useAnimations = isDesktopPlatform(),
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
                SelectableText(
                    text = substance.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
                if (substance.aliases.isNotEmpty()) {
                    SelectableText(
                        text = substance.aliases.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1
                    )
                }
                // Usage stats
                if (sessionCount > 0 || lastUsedDaysAgo != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (sessionCount > 0) {
                            SelectableText(
                                text = "$sessionCount session${if (sessionCount != 1) "s" else ""}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                        if (lastUsedDaysAgo != null) {
                            SelectableText(
                                text = when {
                                    lastUsedDaysAgo == 0 -> "Used today"
                                    lastUsedDaysAgo == 1 -> "Used yesterday"
                                    lastUsedDaysAgo < 7 -> "${lastUsedDaysAgo}d ago"
                                    lastUsedDaysAgo < 30 -> "${lastUsedDaysAgo / 7}w ago"
                                    else -> "${lastUsedDaysAgo / 30}mo ago"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
