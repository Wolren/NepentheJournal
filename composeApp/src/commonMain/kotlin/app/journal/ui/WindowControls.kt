package app.journal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Window commands the desktop host installs. Android leaves them null, where
 * the system chrome owns minimize, maximize and close.
 */
object WindowControls {
    var minimize: (() -> Unit)? = null
    var toggleMaximize: (() -> Unit)? = null
    var close: (() -> Unit)? = null
    var maximized by mutableStateOf(false)

    val available: Boolean get() = close != null
}

/** Borderless windows carry their own controls: the rail stacks them, the narrow bar lays them in a row. */
@Composable
fun WindowControlButtons(horizontal: Boolean) {
    val buttons: @Composable () -> Unit = {
        IconButton(onClick = { WindowControls.minimize?.invoke() }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Remove, contentDescription = "Minimize", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { WindowControls.toggleMaximize?.invoke() }, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (WindowControls.maximized) Icons.Default.FilterNone else Icons.Default.CropSquare,
                contentDescription = if (WindowControls.maximized) "Restore" else "Maximize",
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(onClick = { WindowControls.close?.invoke() }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
        }
    }
    if (horizontal) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            buttons()
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            buttons()
        }
    }
}
