package app.journal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * How long an empty state waits before it is allowed to appear.
 *
 * Tab screens collect derived view-model flows with an `initial = emptyList()`
 * value, so the first frame after entering a tab is always empty even when
 * there is data. Without a holdback, "No sessions yet" / "No substances in
 * database" flashes on every tab switch. Long enough to swallow that first
 * emission, short enough that a genuinely empty list still speaks up.
 */
internal const val EMPTY_STATE_DELAY_MS = 600L

/**
 * True once [isEmpty] has held continuously for [delayMs].
 *
 * Flip to false immediately when data arrives, so the empty state can never
 * be shown next to a populated list; flip to true only after the delay, so a
 * transient empty frame stays invisible.
 */
@Composable
internal fun rememberEmptyStateVisible(
    isEmpty: Boolean,
    delayMs: Long = EMPTY_STATE_DELAY_MS,
): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(isEmpty) {
        if (isEmpty) {
            visible = false
            delay(delayMs)
            visible = true
        } else {
            visible = false
        }
    }
    return visible
}
