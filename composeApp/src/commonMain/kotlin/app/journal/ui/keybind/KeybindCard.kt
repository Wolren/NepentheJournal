package app.journal.ui.keybind

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.journal.ui.components.AppCard
import app.journal.ui.components.AppTonalButton
import kotlinx.coroutines.delay

/** Settings card: click a shortcut, press the new keys, Escape cancels. */
@Composable
fun KeybindCard() {
    val binds by KeybindManager.instance.settings.collectAsState()
    var capturing by remember { mutableStateOf<KeybindAction?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(capturing) {
        if (capturing != null) {
            delay(60)
            runCatching { focusRequester.requestFocus() }
        }
    }

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val action = capturing ?: return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.Escape) {
                    capturing = null
                    return@onPreviewKeyEvent true
                }
                val bindable = BindableKey.entries.firstOrNull { it.key == event.key }
                if (bindable != null) {
                    val next = action.of(binds).copy(
                        key = bindable,
                        ctrl = event.isCtrlPressed,
                        shift = event.isShiftPressed
                    )
                    KeybindManager.instance.update(action.with(binds, next))
                    capturing = null
                }
                true
            }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Keyboard, null, tint = MaterialTheme.colorScheme.primary)
            Text("Shortcuts", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Click a shortcut, then press the new keys. Escape cancels.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        KeybindAction.entries.forEach { action ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(action.label, style = MaterialTheme.typography.bodyMedium)
                AppTonalButton(onClick = { capturing = if (capturing == action) null else action }) {
                    Text(if (capturing == action) "Press keys..." else action.of(binds).label())
                }
            }
        }
    }
}
