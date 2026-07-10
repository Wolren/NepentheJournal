package app.journal.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.journal.ui.components.*

/**
 * A compact color field: a swatch that opens an HSV picker dialog on tap.
 * The picker is dependency-free (Canvas + sliders) and works on every
 * Compose Multiplatform target. [value] is an ARGB [Long] (0xAARRGGBB).
 */
@Composable
fun ColorPickerField(
    label: String,
    value: Long,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(value),
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { showDialog = true }
        ) {
            Box(Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)))
        }
    }

    if (showDialog) {
        ColorPickerDialog(
            initial = value,
            onDismiss = { showDialog = false },
            onConfirm = { onPick(it); showDialog = false }
        )
    }
}

@Composable
private fun ColorPickerDialog(
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var argb by remember { mutableStateOf(initial) }
    val color = Color(argb)
    // HSV state
    var hue by remember { mutableStateOf(color.toHsvHue()) }
    var sat by remember { mutableStateOf(color.toHsvSat()) }
    var valueBri by remember { mutableStateOf(color.toHsvVal()) }
    var alpha by remember { mutableStateOf((initial ushr 24).toInt().coerceIn(0, 255)) }

    // Keep ARGB in sync with HSV/alpha
    fun sync() { argb = hsvToArgb(hue, sat, valueBri, alpha) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            AppTextButton(onClick = { onConfirm(argb) }) { Text("Done") }
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Pick color") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Saturation/Value square
                val squareSize = 220.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(squareSize)
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    val x = (change.position.x / size.width).coerceIn(0f, 1f)
                                    val y = (change.position.y / size.height).coerceIn(0f, 1f)
                                    sat = x
                                    valueBri = 1f - y
                                    sync()
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val x = (offset.x / size.width).coerceIn(0f, 1f)
                                    val y = (offset.y / size.height).coerceIn(0f, 1f)
                                    sat = x
                                    valueBri = 1f - y
                                    sync()
                                }
                            }
                    ) {
                        val w = size.width
                        val h = size.height
                        // white -> hue color across X
                        drawRect(brush = Brush.horizontalGradient(
                            colors = listOf(Color.White, hueColor(hue))))
                        // black from bottom (Y inverted)
                        drawRect(brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startY = 0f, endY = h))
                    }
                    // selector dot
                    val dotX = (sat * squareSize.value).dp
                    val dotY = ((1f - valueBri) * squareSize.value).dp
                    Box(
                        modifier = Modifier
                            .offset(dotX - 8.dp, dotY - 8.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(2.dp, Color.White, CircleShape)
                    )
                }

                // Hue slider
                Slider(
                    value = hue,
                    onValueChange = { hue = it; sync() },
                    valueRange = 0f..360f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = hueColor(hue),
                        activeTrackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                // Hue track background
                HueTrack(Modifier.fillMaxWidth().height(6.dp))

                // Alpha slider
                Slider(
                    value = alpha.toFloat(),
                    onValueChange = { alpha = it.toInt().coerceIn(0, 255); sync() },
                    valueRange = 0f..255f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(argb),
                        activeTrackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alpha", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(44.dp))
                    Text("${alpha * 100 / 255}%", style = MaterialTheme.typography.labelSmall)
                }

                // Hex field (live)
                var hexInput by remember(argb) { mutableStateOf(argb.toHex()) }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        hexInput = input.uppercase().take(9)
                        parseArgb(input)?.let { parsed ->
                            argb = parsed
                            val c = Color(parsed)
                            hue = c.toHsvHue(); sat = c.toHsvSat(); valueBri = c.toHsvVal()
                            alpha = (parsed ushr 24).toInt().coerceIn(0, 255)
                        }
                    },
                    placeholder = { Text("#FF000000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done)
                )

                // Preview swatch
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(argb),
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                ) {}
            }
        }
    )
}

/** Rainbow track behind the hue slider. */
@Composable
private fun HueTrack(modifier: Modifier) {
    Canvas(modifier = modifier) {
        drawRect(brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                Color.Blue, Color.Magenta, Color.Red
            )))
    }
}

// ---------- HSV <-> ARGB helpers ----------

private fun hueColor(h: Float): Color {
    val c = hsvToArgb(h, 1f, 1f, 255)
    return Color(c)
}

private fun hsvToArgb(h: Float, s: Float, v: Float, a: Int): Long {
    val hh = (h % 360f).coerceAtLeast(0f)
    val c = v * s
    val x = c * (1f - kotlin.math.abs((hh / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        hh < 60f -> Triple(c, x, 0f)
        hh < 120f -> Triple(x, c, 0f)
        hh < 180f -> Triple(0f, c, x)
        hh < 240f -> Triple(0f, x, c)
        hh < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val ri = ((r + m) * 255).toInt().coerceIn(0, 255)
    val gi = ((g + m) * 255).toInt().coerceIn(0, 255)
    val bi = ((b + m) * 255).toInt().coerceIn(0, 255)
    val ai = a.coerceIn(0, 255)
    return (ai.toLong() shl 24) or (ri.toLong() shl 16) or (gi.toLong() shl 8) or bi.toLong()
}

private fun Color.toHsvHue(): Float {
    val r = red; val g = green; val b = blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val d = max - min
    if (d == 0f) return 0f
    val h = when (max) {
        r -> ((g - b) / d) % 6f
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return ((h * 60f) + 360f) % 360f
}

private fun Color.toHsvSat(): Float {
    val max = maxOf(red, green, blue)
    if (max == 0f) return 0f
    return (max - minOf(red, green, blue)) / max
}

private fun Color.toHsvVal(): Float = maxOf(red, green, blue)

private fun Long.toHex(): String {
    val c = Color(this)
    val a = (this ushr 24).toInt() and 0xFF
    fun two(v: Float) = ((v * 255).toInt().coerceIn(0, 255)).toString(16).padStart(2, '0')
    return "#${two(c.alpha)}${two(c.red)}${two(c.green)}${two(c.blue)}".uppercase()
}

private fun parseArgb(hex: String): Long? {
    val clean = hex.removePrefix("#")
    val (a, rg, gg, bg) = when (clean.length) {
        8 -> listOf(clean.substring(0, 2), clean.substring(2, 4), clean.substring(4, 6), clean.substring(6, 8))
        6 -> listOf("FF", clean.substring(0, 2), clean.substring(2, 4), clean.substring(4, 6))
        else -> return null
    }
    return try {
        val aI = a.toInt(16); val rI = rg.toInt(16); val gI = gg.toInt(16); val bI = bg.toInt(16)
        (aI.toLong() shl 24) or (rI.toLong() shl 16) or (gI.toLong() shl 8) or bI.toLong()
    } catch (e: Exception) { null }
}
