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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val GreetingMs = 2200L
private const val ReactionHoldMs = 2000L

/** The petdex sheets are drawn at 208 px tall; the display size scales from that, not from the asset. */
private const val DisplayHeightDp = 208f

/**
 * Draws the selected pet in a window corner. It waves after launch, runs while a live session
 * is open, and idles otherwise; a check-in or an interaction warning plays a one-shot clip.
 */
@Composable
fun PetCompanion(state: PetState, modifier: Modifier = Modifier) {
    val settings by PetManager.instance.settings.collectAsState()
    if (!settings.enabled) return
    val pets = remember { availablePets() }
    val slug = remember(settings.slug, pets) {
        settings.slug.takeIf { chosen -> pets.any { it.slug == chosen } } ?: pets.firstOrNull()?.slug
    } ?: return
    val sheet = remember(slug) { loadPetSheet(slug) } ?: return

    var reaction by remember(slug) { mutableStateOf<PetReaction?>(null) }
    LaunchedEffect(slug) {
        PetManager.instance.reactions.collect { next ->
            reaction = next
            delay(ReactionHoldMs)
            if (reaction == next) reaction = null
        }
    }

    var greeting by remember(slug) { mutableStateOf(true) }
    LaunchedEffect(slug) {
        delay(GreetingMs)
        greeting = false
    }

    val baseName = when {
        greeting -> "wave"
        state == PetState.RUNNING -> "run"
        else -> "idle"
    }
    val clip = reaction?.let { sheet.clip(it.clipName) }
        ?: sheet.clip(baseName)
        ?: sheet.clips.firstOrNull()
        ?: return

    var frame by remember(clip) { mutableStateOf(0) }
    LaunchedEffect(clip) {
        val stepMs = (1000f / clip.fps.coerceAtLeast(1f)).toLong()
        while (true) {
            for (step in 0 until clip.frames) {
                frame = step
                delay(stepMs)
            }
            frame = clip.frames - 1
            if (!clip.loop) break
            delay(clip.restMs + Random.nextLong(0L, 900L))
        }
    }

    val height = DisplayHeightDp.dp * settings.scale
    val width = height * (sheet.frameWidth.toFloat() / sheet.frameHeight.toFloat())
    Canvas(
        modifier = modifier
            .size(width, height)
            .semantics { contentDescription = "Companion" }
    ) {
        // A soft lantern glow behind the sprite; the sheet itself stays flat so it compresses.
        val glowRadius = size.minDimension * 0.72f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x2EE6B05C), Color(0x00000000)),
                center = center,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = center,
        )
        val columns = (sheet.image.width / sheet.frameWidth).coerceAtLeast(1)
        val column = frame.coerceIn(0, clip.frames - 1).coerceIn(0, columns - 1)
        drawImage(
            image = sheet.image,
            srcOffset = IntOffset(column * sheet.frameWidth, clip.row * sheet.frameHeight),
            srcSize = IntSize(sheet.frameWidth, sheet.frameHeight),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}
