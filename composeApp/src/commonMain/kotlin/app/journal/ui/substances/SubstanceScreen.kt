package app.journal.ui.substances

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.journal.data.IJournalRepository
import app.journal.model.Substance
import app.journal.ui.LocalJournalRepository
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager
import app.journal.util.currentTimeMillis
import app.journal.util.isDesktopPlatform
import app.journal.data.EffectLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.journal.ui.components.*

/** The Substances tab holds the library, the effect library and the dose equivalence converter. */
internal enum class LibraryMode { SUBSTANCES, EFFECTS, EQUIVALENCE }

@Composable
fun SubstanceScreen(
    repo: IJournalRepository = LocalJournalRepository.current,
    viewModel: SubstanceScreenViewModel = remember(repo) { SubstanceScreenViewModel.create(repo) },
    onSubstanceClick: (String) -> Unit = {},
    onNewSubstance: () -> Unit = {},
    onEffectClick: (String) -> Unit = {},
) {
    val results by viewModel.results.collectAsState(initial = emptyList())
    val broadOptions by viewModel.broadOptions.collectAsState(initial = emptyList())
    val specificOptions by viewModel.specificOptions.collectAsState(initial = emptyList())
    val themeManager = remember { ThemeManager.instance }
    val isDark = themeManager.isDarkTheme()
    val substanceDoseStats = remember(results) { viewModel.substanceDoseStats }

    val query by viewModel.query.collectAsState()
    val activeBroad by viewModel.activeBroad.collectAsState()
    val activeSpecifics by viewModel.activeSpecifics.collectAsState()
    var categoryDropdownExpanded by remember { mutableStateOf(false) }
    var showSpecifics by remember { mutableStateOf(false) }
    LaunchedEffect(categoryDropdownExpanded) {
        if (!categoryDropdownExpanded) showSpecifics = false
    }
    val scrollState = rememberLazyListState()
    val effectsScrollState = rememberLazyListState()
    val equivalenceScrollState = rememberLazyListState()
    var mode by rememberSaveable { mutableStateOf(LibraryMode.SUBSTANCES) }
    var effectQuery by rememberSaveable { mutableStateOf("") }
    var effectFilterExpanded by rememberSaveable { mutableStateOf(false) }
    var effectBroad by rememberSaveable { mutableStateOf<String?>(null) }
    var effectSpecific by rememberSaveable { mutableStateOf<String?>(null) }
    val activeQuery = if (mode == LibraryMode.SUBSTANCES) query else effectQuery
    val effectArticles by EffectLibrary.articles.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { EffectLibrary.ensureLoaded() } }
    val effectOptions = remember(effectArticles) { effectFilterOptions(effectArticles) }
    LaunchedEffect(mode) { if (mode != LibraryMode.EFFECTS) effectFilterExpanded = false }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = mode == LibraryMode.SUBSTANCES,
                    onClick = { mode = LibraryMode.SUBSTANCES },
                    label = {
                        Text("Substances", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = mode == LibraryMode.EFFECTS,
                    onClick = { mode = LibraryMode.EFFECTS },
                    label = {
                        Text("Effects", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = mode == LibraryMode.EQUIVALENCE,
                    onClick = { mode = LibraryMode.EQUIVALENCE },
                    label = {
                        Text("Equivalence", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            if (mode != LibraryMode.EQUIVALENCE) {
                BoxWithConstraints {
                    val wideFilterMenu = maxWidth > 700.dp
                    OutlinedTextField(
                        value = activeQuery,
                        onValueChange = {
                            if (mode == LibraryMode.SUBSTANCES) viewModel.query.value = it else effectQuery = it
                        },
                        placeholder = {
                            Text(if (mode == LibraryMode.SUBSTANCES) "Search substances..." else "Search effects...")
                        },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (activeQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        viewModel.query.value = ""
                                        effectQuery = ""
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear")
                                    }
                                }
                                if (mode == LibraryMode.SUBSTANCES) {
                                    IconButton(onClick = { categoryDropdownExpanded = true }) {
                                        Icon(
                                            Icons.Default.Label,
                                            contentDescription = "Filter by category",
                                            tint = if (activeBroad != null) MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    IconButton(onClick = { effectFilterExpanded = true }) {
                                        Icon(
                                            Icons.Default.Label,
                                            contentDescription = "Filter by category",
                                            tint = if (effectBroad != null) MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    DropdownMenu(
                        expanded = effectFilterExpanded,
                        onDismissRequest = { effectFilterExpanded = false },
                        offset = DpOffset(0.dp, 0.dp),
                    ) {
                        Column(modifier = Modifier.width(300.dp)) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "All categories",
                                        fontWeight = if (effectBroad == null) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    effectBroad = null
                                    effectSpecific = null
                                    effectFilterExpanded = false
                                },
                                leadingIcon = { Checkbox(checked = effectBroad == null, onCheckedChange = null) },
                            )
                            effectOptions.filter { it.parent == null }.forEach { option ->
                                val selected = effectBroad == option.key && effectSpecific == null
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${option.label} (${option.count})",
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        effectBroad = option.key
                                        effectSpecific = null
                                        if (option.key != "sensory") effectFilterExpanded = false
                                    },
                                    leadingIcon = { Checkbox(checked = selected, onCheckedChange = null) },
                                )
                            }
                            val specifics = effectOptions.filter { it.parent == "sensory" }
                            if (specifics.isNotEmpty()) {
                                HorizontalDivider()
                                Text(
                                    text = "Sensory specifics",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                                )
                                specifics.forEach { option ->
                                    val selected = effectSpecific == option.key
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${option.label} (${option.count})",
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        },
                                        onClick = {
                                            effectBroad = "sensory"
                                            effectSpecific = option.key
                                            effectFilterExpanded = false
                                        },
                                        leadingIcon = { Checkbox(checked = selected, onCheckedChange = null) },
                                    )
                                }
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Done", fontWeight = FontWeight.Bold) },
                                onClick = { effectFilterExpanded = false },
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = categoryDropdownExpanded,
                        onDismissRequest = { categoryDropdownExpanded = false },
                        offset = DpOffset(0.dp, 0.dp),
                    ) {
                        val wideMenu = wideFilterMenu
                        @Composable
                        fun BroadPane() {
                            Column(modifier = Modifier.width(300.dp)) {
                                DropdownMenuItem(
                                    text = {
                                        Text("All categories",
                                            fontWeight = if (activeBroad == null) FontWeight.Bold else FontWeight.Normal)
                                    },
                                    onClick = { viewModel.selectBroad(null); categoryDropdownExpanded = false },
                                    leadingIcon = {
                                        Checkbox(
                                            checked = activeBroad == null,
                                            onCheckedChange = null,
                                        )
                                    }
                                )
                                if (broadOptions.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No categories", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                        onClick = { categoryDropdownExpanded = false },
                                        enabled = false
                                    )
                                } else {
                                    broadOptions.forEach { broad ->
                                        val isSelected = broad.id == activeBroad
                                        DropdownMenuItem(
                                            text = {
                                                Text("${broad.label} (${broad.count})",
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                            },
                                            onClick = {
                                                viewModel.selectBroad(if (isSelected) null else broad.id)
                                            },
                                            leadingIcon = {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = null,
                                                )
                                            },
                                            trailingIcon = {
                                                IconButton(
                                                    onClick = {
                                                        if (!isSelected) {
                                                            viewModel.selectBroad(broad.id)
                                                            showSpecifics = true
                                                        } else {
                                                            showSpecifics = !showSpecifics
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.KeyboardArrowRight,
                                                        contentDescription = "Show specifics",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Done", fontWeight = FontWeight.Bold) },
                                        onClick = { categoryDropdownExpanded = false },
                                    )
                                }
                            }
                        }
                        @Composable
                        fun SpecificPane() {
                            Column(modifier = Modifier.width(300.dp)) {
                                specificOptions.forEach { specific ->
                                    val specificSelected = specific.label in activeSpecifics
                                    DropdownMenuItem(
                                        text = {
                                            Text("${specific.label} (${specific.count})",
                                                fontWeight = if (specificSelected) FontWeight.Bold else FontWeight.Normal,
                                                style = MaterialTheme.typography.bodyMedium)
                                        },
                                        onClick = { viewModel.toggleSpecific(specific.label) },
                                        leadingIcon = {
                                            Checkbox(
                                                checked = specificSelected,
                                                onCheckedChange = null,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        if (wideMenu) {
                            Row {
                                BroadPane()
                                if (activeBroad != null && showSpecifics) {
                                    VerticalDivider()
                                    SpecificPane()
                                }
                            }
                        } else {
                            Column {
                                BroadPane()
                                if (activeBroad != null && showSpecifics) {
                                    HorizontalDivider()
                                    SpecificPane()
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            if (mode == LibraryMode.EFFECTS) {
                EffectsLibrary(
                    query = effectQuery,
                    listState = effectsScrollState,
                    broad = effectBroad,
                    specific = effectSpecific,
                    onEffectClick = onEffectClick,
                )
            } else if (mode == LibraryMode.EQUIVALENCE) {
                LazyColumn(
                    state = equivalenceScrollState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { DoseEquivalenceCard() }
                }
            } else if (results.isEmpty()) {
                val showEmptyState = rememberEmptyStateVisible(isEmpty = results.isEmpty())
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (showEmptyState) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Science, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(48.dp))
                            Text(
                                text = if (query.isNotEmpty()) {
                                    "No substances match \"$query\""
                                } else {
                                    "No substances in database"
                                },
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

        if (mode == LibraryMode.SUBSTANCES) {
            FloatingActionButton(
                onClick = onNewSubstance,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Substance")
            }
        }

        DesktopScrollbar(if (mode == LibraryMode.SUBSTANCES) scrollState else effectsScrollState)
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
    val nameStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    val aliasStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val aliasText = remember(substance.aliases) { substance.aliases.joinToString(", ") }
    val barColor = remember(color, isDark) { color.getComposeColor(isDark).copy(alpha = 0.85f) }

    HoverCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        useAnimations = isDesktopPlatform(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f)),
    ) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(barColor)
            )
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                SelectableText(
                    text = substance.name,
                    style = nameStyle
                )
                if (substance.aliases.isNotEmpty()) {
                    SelectableText(
                        text = aliasText,
                        style = aliasStyle,
                        maxLines = 1
                    )
                }
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
