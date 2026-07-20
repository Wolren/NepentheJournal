package app.journal.ui.settings.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.ui.theme.*
import app.journal.ui.components.*

@Composable
internal fun ThemeSettingsCard(
    themeConfig: ThemeConfig,
    themeManager: ThemeManager,
    editBaseTheme: BaseTheme,
    editPrimary: Long,
    editSecondary: Long,
    editTertiary: Long,
    editBgColor: Long,
    editSurfaceColor: Long,
    editBgImage: String?,
    editBgOpacity: Float,
    editCardStyle: CardStyle,
    editCornerRadius: CornerRadius,
    editFontScale: Float,
    editAnimationScale: Float,
    onBaseThemeChange: (BaseTheme) -> Unit,
    onPrimaryChange: (Long) -> Unit,
    onSecondaryChange: (Long) -> Unit,
    onTertiaryChange: (Long) -> Unit,
    onBgColorChange: (Long) -> Unit,
    onSurfaceColorChange: (Long) -> Unit,
    onBgImageChange: (String?) -> Unit,
    onBgOpacityChange: (Float) -> Unit,
    onCardStyleChange: (CardStyle) -> Unit,
    onCornerRadiusChange: (CornerRadius) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onAnimationScaleChange: (Float) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    fun applyTheme() {
        themeManager.update(ThemeConfig(
            baseTheme = editBaseTheme,
            primaryColor = editPrimary,
            secondaryColor = editSecondary,
            tertiaryColor = editTertiary,
            backgroundColor = editBgColor,
            surfaceColor = editSurfaceColor,
            backgroundImagePath = editBgImage?.takeIf { it.isNotBlank() },
            backgroundOpacity = editBgOpacity,
            cardStyle = editCardStyle,
            cornerRadius = editCornerRadius,
            fontScale = editFontScale,
            animationScale = editAnimationScale
        ))
    }

    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp).animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 2000f))) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onApply() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                }
                AppTonalButton(onClick = { onApply() }) { Text("Manage") }
            }
            if (true) { // expanded — always shown when this composable is called
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Text("Base", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = editBaseTheme == BaseTheme.DARK,
                        onClick = { onBaseThemeChange(BaseTheme.DARK) },
                        label = { Text("Dark") }, modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = editBaseTheme == BaseTheme.LIGHT,
                        onClick = { onBaseThemeChange(BaseTheme.LIGHT) },
                        label = { Text("Light") }, modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = editBaseTheme == BaseTheme.SYSTEM,
                        onClick = { onBaseThemeChange(BaseTheme.SYSTEM) },
                        label = { Text("System") }, modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = editBaseTheme == BaseTheme.CUSTOM,
                        onClick = { onBaseThemeChange(BaseTheme.CUSTOM) },
                        label = { Text("Custom") }, modifier = Modifier.weight(1f)
                    )
                }

                if (editBaseTheme == BaseTheme.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("Colors", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    ColorPickerField("Primary", editPrimary, onPick = { onPrimaryChange(it) })
                    SwatchesRow(current = editPrimary, onPick = { onPrimaryChange(it) })
                    ColorPickerField("Secondary", editSecondary, onPick = { onSecondaryChange(it) })
                    SwatchesRow(current = editSecondary, onPick = { onSecondaryChange(it) })
                    ColorPickerField("Tertiary", editTertiary, onPick = { onTertiaryChange(it) })
                    SwatchesRow(current = editTertiary, onPick = { onTertiaryChange(it) })
                    Spacer(Modifier.height(8.dp))
                    ColorPickerField("Background", editBgColor, onPick = { onBgColorChange(it) })
                    Spacer(Modifier.height(4.dp))
                    ColorPickerField("Surface", editSurfaceColor, onPick = { onSurfaceColorChange(it) })
                    Spacer(Modifier.height(12.dp))
                    Text("Background image", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = editBgImage ?: "",
                        onValueChange = { onBgImageChange(it) },
                        placeholder = { Text("Image URL or file path") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = if (editBgImage != null) {
                            { AppIconButton(onClick = { onBgImageChange(null) }, icon = Icons.Default.Close, contentDescription = "Clear") }
                        } else null
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Opacity", style = MaterialTheme.typography.bodySmall)
                        Slider(value = editBgOpacity, onValueChange = { onBgOpacityChange(it) },
                            modifier = Modifier.weight(1f).height(16.dp), valueRange = 0f..1f)
                        Text("%.0f%%".format(editBgOpacity * 100), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Cards & shapes", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Style: ", style = MaterialTheme.typography.bodySmall)
                        CardStyle.entries.forEach { style ->
                            FilterChip(selected = editCardStyle == style,
                                onClick = { onCardStyleChange(style) },
                                label = { Text(style.name.lowercase(), style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Corners: ", style = MaterialTheme.typography.bodySmall)
                        CornerRadius.entries.forEach { radius ->
                            FilterChip(selected = editCornerRadius == radius,
                                onClick = { onCornerRadiusChange(radius) },
                                label = { Text(radius.name.lowercase(), style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Typography", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Font scale", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        Slider(value = editFontScale, onValueChange = { onFontScaleChange(it) },
                            modifier = Modifier.weight(1f).height(16.dp), valueRange = 0.8f..1.3f, steps = 9)
                        Text("%.0f%%".format(editFontScale * 100), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Animations", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Speed", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        Slider(value = editAnimationScale, onValueChange = { onAnimationScaleChange(it) },
                            modifier = Modifier.weight(1f).height(16.dp), valueRange = 0f..1f, steps = 4)
                        val label = when {
                            editAnimationScale == 0f -> "Off"
                            editAnimationScale <= 0.25f -> "0.25x"
                            editAnimationScale <= 0.5f -> "0.5x"
                            editAnimationScale <= 0.75f -> "0.75x"
                            else -> "1x"
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    }
                }

                if (editBaseTheme == BaseTheme.CUSTOM) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(onClick = { applyTheme() }, modifier = Modifier.weight(1f)) { Text("Apply") }
                        AppTonalButton(
                            onClick = { onReset() },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ), modifier = Modifier.weight(1f)
                        ) { Text("Reset") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SwatchesRow(current: Long, onPick: (Long) -> Unit) {
    val swatches = listOf(
        0xFF4CAF50L, 0xFF2E7D32L, 0xFF81C784L, 0xFFAED581L, 0xFF1B5E20L,
        0xFF1565C0L, 0xFF0D47A1L, 0xFF7B1FA2L, 0xFF4A148CL, 0xFFFFCC80L
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        swatches.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { swatch ->
                    val isSelected = current == swatch
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(swatch))
                            .clickable { onPick(swatch) }
                            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                            .then(Modifier.border(1.dp, Color.Black.copy(alpha = 0.2f), CircleShape)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) Icon(Icons.Default.Check, null,
                            tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
