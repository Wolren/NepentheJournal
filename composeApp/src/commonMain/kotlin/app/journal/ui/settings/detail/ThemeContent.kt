package app.journal.ui.settings.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.ui.theme.*
import app.journal.ui.components.*

@Composable
internal fun ThemeContent(
    themeConfig: ThemeConfig, themeManager: ThemeManager,
    editBaseTheme: BaseTheme, editPrimary: Long, editSecondary: Long, editTertiary: Long,
    editBgColor: Long, editSurfaceColor: Long, editBgImage: String?,
    editBgOpacity: Float, editCardStyle: CardStyle, editCornerRadius: CornerRadius,
    editFontScale: Float, editAnimationScale: Float,
    onBaseThemeChange: (BaseTheme) -> Unit, onPrimaryChange: (Long) -> Unit,
    onSecondaryChange: (Long) -> Unit, onTertiaryChange: (Long) -> Unit,
    onBgColorChange: (Long) -> Unit, onSurfaceColorChange: (Long) -> Unit,
    onBgImageChange: (String?) -> Unit, onBgOpacityChange: (Float) -> Unit,
    onCardStyleChange: (CardStyle) -> Unit, onCornerRadiusChange: (CornerRadius) -> Unit,
    onFontScaleChange: (Float) -> Unit, onAnimationScaleChange: (Float) -> Unit,
    applyTheme: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp).animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 2000f))) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                }
                AppTonalButton(onClick = { expanded = !expanded }) { Text("Manage") }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Text("Base", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = editBaseTheme == BaseTheme.DARK,
                        onClick = { onBaseThemeChange(BaseTheme.DARK); onPrimaryChange(ThemeDefaults.Dark.primaryColor); onSecondaryChange(ThemeDefaults.Dark.secondaryColor); onTertiaryChange(ThemeDefaults.Dark.tertiaryColor); onBgColorChange(0xFF0E1511L); onSurfaceColorChange(0xFF16211AL); applyTheme() },
                        label = { Text("Dark") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = editBaseTheme == BaseTheme.LIGHT,
                        onClick = { onBaseThemeChange(BaseTheme.LIGHT); onPrimaryChange(ThemeDefaults.Light.primaryColor); onSecondaryChange(ThemeDefaults.Light.secondaryColor); onTertiaryChange(ThemeDefaults.Light.tertiaryColor); onBgColorChange(0xFFF3F8EFL); onSurfaceColorChange(0xFFFFFFFFL); applyTheme() },
                        label = { Text("Light") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = editBaseTheme == BaseTheme.SYSTEM,
                        onClick = { onBaseThemeChange(BaseTheme.SYSTEM); applyTheme() },
                        label = { Text("System") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = editBaseTheme == BaseTheme.CUSTOM,
                        onClick = { onBaseThemeChange(BaseTheme.CUSTOM) },
                        label = { Text("Custom") }, modifier = Modifier.weight(1f))
                }

                if (editBaseTheme == BaseTheme.CUSTOM) {
                    Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                    Text("Colors", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    ColorPickerField("Primary", editPrimary, onPick = { onPrimaryChange(it); applyTheme() })
                    SwatchesRow(current = editPrimary, onPick = { onPrimaryChange(it); applyTheme() })
                    ColorPickerField("Secondary", editSecondary, onPick = { onSecondaryChange(it); applyTheme() })
                    SwatchesRow(current = editSecondary, onPick = { onSecondaryChange(it); applyTheme() })
                    ColorPickerField("Tertiary", editTertiary, onPick = { onTertiaryChange(it); applyTheme() })
                    SwatchesRow(current = editTertiary, onPick = { onTertiaryChange(it); applyTheme() })
                    Spacer(Modifier.height(8.dp))
                    ColorPickerField("Background", editBgColor, onPick = { onBgColorChange(it); applyTheme() })
                    Spacer(Modifier.height(4.dp))
                    ColorPickerField("Surface", editSurfaceColor, onPick = { onSurfaceColorChange(it); applyTheme() })
                    Spacer(Modifier.height(12.dp))
                    Text("Background image", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = editBgImage ?: "", onValueChange = { onBgImageChange(it); applyTheme() },
                        placeholder = { Text("Image URL or file path") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = if (editBgImage != null) { { AppIconButton(onClick = { onBgImageChange(null); applyTheme() }, icon = Icons.Default.Close, contentDescription = "Clear") } } else null)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Opacity", style = MaterialTheme.typography.bodySmall)
                        Slider(value = editBgOpacity, onValueChange = { onBgOpacityChange(it); applyTheme() },
                            modifier = Modifier.weight(1f).height(16.dp), valueRange = 0f..1f)
                        Text("${(editBgOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Cards & shapes", style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Style: ", style = MaterialTheme.typography.bodySmall)
                        CardStyle.entries.forEach { style -> FilterChip(selected = editCardStyle == style, onClick = { onCardStyleChange(style); applyTheme() }, label = { Text(style.name.lowercase(), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.padding(end = 4.dp)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Corners: ", style = MaterialTheme.typography.bodySmall)
                        CornerRadius.entries.forEach { radius -> FilterChip(selected = editCornerRadius == radius, onClick = { onCornerRadiusChange(radius); applyTheme() }, label = { Text(radius.name.lowercase(), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.padding(end = 4.dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Typography", style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Font scale", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp))
                        Slider(value = editFontScale, onValueChange = { onFontScaleChange(it); applyTheme() },
                            modifier = Modifier.weight(1f).height(16.dp), valueRange = 0.8f..1.3f, steps = 9)
                        Text("${(editFontScale * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Animations", style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Speed", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp))
                        Slider(value = editAnimationScale, onValueChange = { onAnimationScaleChange(it); applyTheme() },
                            modifier = Modifier.weight(1f).height(16.dp), valueRange = 0f..1f, steps = 4)
                        val label = when { editAnimationScale == 0f -> "Off"; editAnimationScale <= 0.25f -> "0.25x"; editAnimationScale <= 0.5f -> "0.5x"; editAnimationScale <= 0.75f -> "0.75x"; else -> "1x" }
                        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    }
                }
                if (editBaseTheme == BaseTheme.CUSTOM) {
                    Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(onClick = { applyTheme() }, modifier = Modifier.weight(1f)) { Text("Apply") }
                        AppTonalButton(onClick = { onBaseThemeChange(ThemeDefaults.Dark.baseTheme); onPrimaryChange(ThemeDefaults.Dark.primaryColor); onSecondaryChange(ThemeDefaults.Dark.secondaryColor); onTertiaryChange(ThemeDefaults.Dark.tertiaryColor); onBgColorChange(0xFF0E1511L); onSurfaceColorChange(0xFF16211AL); onCardStyleChange(ThemeDefaults.Dark.cardStyle); onCornerRadiusChange(ThemeDefaults.Dark.cornerRadius); onBgImageChange(null); onBgOpacityChange(0.3f); onFontScaleChange(1.0f); onAnimationScaleChange(1.0f); applyTheme() },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.weight(1f)) { Text("Reset") }
                    }
                }
            }
        }
    }
}
