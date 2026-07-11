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
import app.journal.ui.theme.ColorUtils

/**
 * A compact color field: a swatch that opens an HSV picker dialog on tap.
 * The picker is dependency-free (Canvas + sliders) and works on every
 * Compose Multiplatform target. [value] is an ARGB [Long] (0xAARRGGBB).
 *
 * Color math (HSV/hex) lives in [ColorUtils].
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
    var hue by remember { mutableStateOf(ColorUtils.toHsvHue(color)) }
    var sat by remember { mutableStateOf(ColorUtils.toHsvSat(color)) }
    var valueBri by remember { mutableStateOf(ColorUtils.toHsvVal(color)) }
    var alpha by remember { mutableStateOf((initial ushr 24).toInt().coerceIn(0, 255)) }

    // Keep ARGB in sync with HSV/alpha
    fun sync() { argb = ColorUtils.hsvToArgb(hue, sat, valueBri, alpha) }

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
                            colors = listOf(Color.White, ColorUtils.hueColor(hue))))
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
                        thumbColor = ColorUtils.hueColor(hue),
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
                var hexInput by remember(argb) { mutableStateOf(ColorUtils.toHexColor(argb)) }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        hexInput = input.uppercase().take(9)
                        ColorUtils.parseArgb(input)?.let { parsed ->
                            argb = parsed
                            val c = Color(parsed)
                            hue = ColorUtils.toHsvHue(c); sat = ColorUtils.toHsvSat(c); valueBri = ColorUtils.toHsvVal(c)
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

// All color math (HSV/hex) moved to ColorUtils.kt
