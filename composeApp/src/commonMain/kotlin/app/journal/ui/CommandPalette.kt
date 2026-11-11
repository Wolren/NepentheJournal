package app.journal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.ui.components.AppCard
import app.journal.ui.components.chipShape
import app.journal.util.PlatformFile
import kotlinx.coroutines.delay

/** One row of the command palette: a stable id, a label, an optional second line and its group. */
data class CommandItem(
    val id: String,
    val title: String,
    val detail: String?,
    val group: String,
    val isAction: Boolean,
    val action: () -> Unit,
)

private const val PaletteMaxResults = 80
private const val RecentLimit = 8

/** The last commands run from the palette, newest first, so the empty state opens with them. */
object PaletteRecents {
    private fun path(): String = "${PlatformFile.dataDir()}/palette-recents.txt"

    fun load(): List<String> = try {
        if (PlatformFile.size(path()) < 0) emptyList()
        else PlatformFile.readText(path()).lineSequence().filter { it.isNotBlank() }.take(RecentLimit).toList()
    } catch (_: Exception) {
        emptyList()
    }

    fun record(id: String) {
        try {
            val next = (listOf(id) + load().filter { it != id }).take(RecentLimit)
            PlatformFile.writeText(path(), next.joinToString("\n"))
        } catch (_: Exception) {
            // Best effort: a palette that cannot remember must still work.
        }
    }
}

/**
 * Scores a fuzzy match: every query character must appear in order. Lower is
 * better, and a gap or a late position costs more than a contiguous hit.
 */
internal fun fuzzyScore(text: String, query: String): Int? {
    if (query.isEmpty()) return 0
    val haystack = text.lowercase()
    val needle = query.lowercase()
    var index = 0
    var score = 0
    for (char in needle) {
        val found = haystack.indexOf(char, index)
        if (found < 0) return null
        score += found + if (found == index) 0 else 2
        index = found + 1
    }
    if (haystack.startsWith(needle)) score -= 3
    return score
}

/**
 * Ctrl+K overlay: fuzzy filtered rows, recent commands first, and a ">" prefix
 * that limits the list to actions. Arrow keys move, Enter runs, Escape closes.
 */
@Composable
fun CommandPalette(commands: List<CommandItem>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val recentIds = remember { PaletteRecents.load() }
    val trimmed = query.trim()
    val actionsOnly = trimmed.startsWith(">")
    val needle = if (actionsOnly) trimmed.removePrefix(">").trim() else trimmed

    val results = remember(needle, actionsOnly, commands, recentIds) {
        val base = if (actionsOnly) commands.filter { it.isAction } else commands
        if (needle.isEmpty()) {
            val recent = recentIds.mapNotNull { id -> base.firstOrNull { it.id == id } }
            val rest = base.filterNot { item -> recentIds.contains(item.id) }
            (recent + rest).take(PaletteMaxResults)
        } else {
            base.mapNotNull { item ->
                val score = fuzzyScore(item.title, needle)
                    ?: item.detail?.let { fuzzyScore(it, needle) }
                score?.let { it to item }
            }.sortedBy { it.first }.map { it.second }.take(PaletteMaxResults)
        }
    }
    LaunchedEffect(results.size) { if (selected >= results.size) selected = 0 }

    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        // The field must be attached and laid out before it can take focus.
        delay(50)
        runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(selected, results.size) {
        if (results.isNotEmpty()) listState.animateScrollToItem(selected.coerceIn(0, results.lastIndex))
    }

    fun run(item: CommandItem?) {
        if (item == null) return
        PaletteRecents.record(item.id)
        item.action()
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
    ) {
        AppCard(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp, start = 24.dp, end = 24.dp)
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            contentPadding = PaddingValues(12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    selected = 0
                },
                placeholder = { Text("Command, substance or session. Type > for actions only") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> {
                                if (results.isNotEmpty()) selected = (selected + 1).coerceAtMost(results.lastIndex)
                                true
                            }
                            Key.DirectionUp -> {
                                selected = (selected - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter -> {
                                run(results.getOrNull(selected))
                                true
                            }
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    }
            )
            Spacer(Modifier.height(8.dp))
            if (results.isEmpty()) {
                Text(
                    "No match",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    itemsIndexed(results) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(chipShape())
                                .background(
                                    if (index == selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable { run(item) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (index == selected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface
                                )
                                if (!item.detail.isNullOrBlank()) {
                                    Text(
                                        item.detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                item.group,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}
