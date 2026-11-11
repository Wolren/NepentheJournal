package app.journal.ui.pet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Row index per petdex state, matching the 8 by 9 sheet every set ships. */
private const val RowIdle = 0
private const val RowWaving = 3
private const val RowRunning = 7

private const val FrameWidth = 192
private const val FrameHeight = 208

private const val GreetingMs = 2200L

/** Rest between animation cycles: idle rests longest, a running session keeps a short beat. */
private fun restMs(row: Int): Long = when (row) {
    RowRunning -> 1400L
    RowWaving -> 2000L
    else -> 3400L
}

/**
 * Draws the selected pet in the corner of the window. It waves once after
 * launch, runs while a live session is open, and idles otherwise.
 */
@Composable
fun PetCompanion(state: PetState, modifier: Modifier = Modifier) {
    val settings by PetManager.instance.settings.collectAsState()
    if (!settings.enabled) return
    val sheet = remember(settings.slug) { loadPetSheet(settings.slug) } ?: return

    var greeting by remember(settings.slug) { mutableStateOf(true) }
    LaunchedEffect(settings.slug) {
        delay(GreetingMs)
        greeting = false
    }
    val row = when {
        greeting -> RowWaving
        state == PetState.RUNNING -> RowRunning
        else -> RowIdle
    }
    val frames = sheet.rowFrames.getOrElse(row) { 1 }
    var frame by remember(row, frames) { mutableStateOf(0) }
    LaunchedEffect(row, frames) {
        while (true) {
            for (step in 0 until frames) {
                frame = step
                delay(if (row == RowRunning) 90L else 150L)
            }
            // Hold the last frame so a cycle ends with a rest instead of a permanent loop.
            frame = frames - 1
            delay(restMs(row) + Random.nextLong(0L, 900L))
        }
    }

    Canvas(
        modifier = modifier
            .size((FrameWidth * settings.scale).dp, (FrameHeight * settings.scale).dp)
            .semantics { contentDescription = "Companion" }
    ) {
        drawImage(
            image = sheet.image,
            srcOffset = IntOffset((frame % frames) * sheet.frameWidth, row * sheet.frameHeight),
            srcSize = IntSize(sheet.frameWidth, sheet.frameHeight),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt())
        )
    }
}
