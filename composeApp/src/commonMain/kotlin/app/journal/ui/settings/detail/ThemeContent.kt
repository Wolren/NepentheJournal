package app.journal.ui.settings.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.journal.ui.theme.*
import app.journal.ui.components.*

@Composable
internal fun ThemeContent(
    themeConfig: ThemeConfig, themeManager: ThemeManager,
    editBaseTheme: BaseTheme, editPrimary: Long, editSecondary: Long, editTertiary: Long,
    editBgColor: Long, editSurfaceColor: Long, editBgImage: String?,
    editBgOpacity: Float, editCardStyle: CardStyle, editCornerRadius: CornerRadius,
    editDensity: Density,
    editFontScale: Float, editAnimationScale: Float, editGithubGreen: Boolean,
    editFontFamily: AppFontFamily, editTextColor: Long?,
    editSurfaceTint: Float?, editOutlineStrength: Float?,
    editSubstanceAccentMode: SubstanceAccentMode,
    onBaseThemeChange: (BaseTheme) -> Unit, onPrimaryChange: (Long) -> Unit,
    onSecondaryChange: (Long) -> Unit, onTertiaryChange: (Long) -> Unit,
    onBgColorChange: (Long) -> Unit, onSurfaceColorChange: (Long) -> Unit,
    onBgImageChange: (String?) -> Unit, onBgOpacityChange: (Float) -> Unit,
    onCardStyleChange: (CardStyle) -> Unit, onCornerRadiusChange: (CornerRadius) -> Unit,
    onDensityChange: (Density) -> Unit,
    onFontScaleChange: (Float) -> Unit, onAnimationScaleChange: (Float) -> Unit,
    onFontFamilyChange: (AppFontFamily) -> Unit, onTextColorChange: (Long?) -> Unit,
    onSurfaceTintChange: (Float?) -> Unit, onOutlineStrengthChange: (Float?) -> Unit,
    onSubstanceAccentModeChange: (SubstanceAccentMode) -> Unit,
    onGithubGreenChange: (Boolean) -> Unit,
    onPresetSelect: (ThemePreset) -> Unit,
    onConfigImport: (ThemeConfig) -> Unit,
    applyTheme: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var userPresets by remember { mutableStateOf(ThemeStore.loadPresets()) }
    var presetName by remember { mutableStateOf("") }
    var importFailed by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 2000f))) {
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
                        onClick = { onBaseThemeChange(BaseTheme.DARK); onPrimaryChange(ThemeDefaults.Dark.primaryColor); onSecondaryChange(ThemeDefaults.Dark.secondaryColor); onTertiaryChange(ThemeDefaults.Dark.tertiaryColor); onBgColorChange(ThemeDefaults.Dark.backgroundColor ?: 0xFF0A2621L); onSurfaceColorChange(ThemeDefaults.Dark.surfaceColor ?: 0xFF10332BL); onTextColorChange(ThemeDefaults.Dark.textColor); onSurfaceTintChange(null); onOutlineStrengthChange(null); applyTheme() },
                        label = { Text("Dark", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }, modifier = Modifier.weight(1f))
                    FilterChip(selected = editBaseTheme == BaseTheme.LIGHT,
                        onClick = { onBaseThemeChange(BaseTheme.LIGHT); onPrimaryChange(ThemeDefaults.Light.primaryColor); onSecondaryChange(ThemeDefaults.Light.secondaryColor); onTertiaryChange(ThemeDefaults.Light.tertiaryColor); onBgColorChange(ThemeDefaults.Light.backgroundColor ?: 0xFFF4EFE3L); onSurfaceColorChange(ThemeDefaults.Light.surfaceColor ?: 0xFFFFFFFFL); onTextColorChange(ThemeDefaults.Light.textColor); onSurfaceTintChange(null); onOutlineStrengthChange(null); applyTheme() },
                        label = { Text("Light", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }, modifier = Modifier.weight(1f))
                    FilterChip(selected = editBaseTheme == BaseTheme.SYSTEM,
                        onClick = { onBaseThemeChange(BaseTheme.SYSTEM); applyTheme() },
                        label = { Text("System", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }, modifier = Modifier.weight(1f))
                    FilterChip(selected = editBaseTheme == BaseTheme.CUSTOM,
                        onClick = { onBaseThemeChange(BaseTheme.CUSTOM) },
                        label = { Text("Custom", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }, modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Presets", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                val isDark = themeManager.isDarkTheme()
                ThemePresets.all.chunked(2).forEach { rowPresets ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowPresets.forEach { preset ->
                            PresetCard(
                                preset = preset,
                                selected = preset.matches(
                                    editPrimary, editSecondary, editTertiary,
                                    editBgColor, editSurfaceColor, isDark
                                ),
                                onClick = { onPresetSelect(preset) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowPresets.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(8.dp))
                if (userPresets.isNotEmpty()) {
                    HorizontalDivider(); Spacer(Modifier.height(8.dp))
                    Text("Your presets", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    userPresets.chunked(2).forEach { rowPresets ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowPresets.forEach { preset ->
                                PresetCard(
                                    preset = preset,
                                    selected = preset.matches(
                                        editPrimary, editSecondary, editTertiary,
                                        editBgColor, editSurfaceColor, isDark
                                    ),
                                    onClick = { onPresetSelect(preset) },
                                    onDelete = {
                                        userPresets = userPresets.filterNot { it.name == preset.name }
                                        ThemeStore.savePresets(userPresets)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (rowPresets.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        placeholder = { Text("Preset name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    AppButton(onClick = {
                        val name = presetName.trim()
                        if (name.isNotEmpty()) {
                            val preset = ThemePreset(
                                name = name,
                                dark = themeConfig.copy(baseTheme = BaseTheme.DARK),
                                light = themeConfig.copy(baseTheme = BaseTheme.LIGHT),
                                cardStyle = themeConfig.cardStyle,
                                cornerRadius = themeConfig.cornerRadius,
                                fontFamily = themeConfig.fontFamily,
                                fontScale = themeConfig.fontScale,
                                animationScale = themeConfig.animationScale,
                                surfaceTint = themeConfig.surfaceTint,
                                outlineStrength = themeConfig.outlineStrength
                            )
                            userPresets = userPresets.filterNot { it.name == name } + preset
                            ThemeStore.savePresets(userPresets)
                            presetName = ""
                        }
                    }, enabled = presetName.isNotBlank()) { Text("Save") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTonalButton(onClick = {
                        clipboard.setText(AnnotatedString(ThemeStore.encode(themeConfig)))
                    }, modifier = Modifier.weight(1f)) { Text("Copy theme") }
                    AppTonalButton(onClick = {
                        val parsed = clipboard.getText()?.text?.let { ThemeStore.decode(it) }
                        importFailed = parsed == null
                        parsed?.let { onConfigImport(it) }
                    }, modifier = Modifier.weight(1f)) { Text("Paste theme") }
                }
                if (importFailed) {
                    Text(
                        "Clipboard does not contain a theme",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("GitHub green activity", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = editGithubGreen,
                        onCheckedChange = { onGithubGreenChange(it); applyTheme() })
                }
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
                    Spacer(Modifier.height(8.dp))
                    val effectiveText = editTextColor ?: (MaterialTheme.colorScheme.onSurface.toArgb().toLong() and 0xFFFFFFFFL)
                    ColorPickerField("Text", effectiveText, onPick = { onTextColorChange(it); applyTheme() })
                    if (editTextColor != null) {
                        AppTextButton(onClick = { onTextColorChange(null); applyTheme() }) { Text("Use default text") }
                    }
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
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Density: ", style = MaterialTheme.typography.bodySmall)
                        Density.entries.forEach { density -> FilterChip(selected = editDensity == density, onClick = { onDensityChange(density); applyTheme() }, label = { Text(density.name.lowercase(), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.padding(end = 4.dp)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    val tintValue = editSurfaceTint ?: (if (isDark) 0.12f else 0.06f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Surface tint", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp))
                        Slider(value = tintValue, onValueChange = { onSurfaceTintChange(it); applyTheme() },
                            modifier = Modifier.weight(1f), valueRange = 0f..0.3f)
                        Text("${(tintValue * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    }
                    val outlineValue = editOutlineStrength ?: (if (isDark) 0.28f else 0.18f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Outline", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp))
                        Slider(value = outlineValue, onValueChange = { onOutlineStrengthChange(it); applyTheme() },
                            modifier = Modifier.weight(1f), valueRange = 0f..0.5f)
                        Text("${(outlineValue * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Typography", style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Font: ", style = MaterialTheme.typography.bodySmall)
                        AppFontFamily.entries.forEach { family ->
                            FilterChip(selected = editFontFamily == family,
                                onClick = { onFontFamilyChange(family); applyTheme() },
                                label = { Text(family.name.lowercase(), style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Font scale", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp))
                        Slider(value = editFontScale, onValueChange = { onFontScaleChange(it); applyTheme() },
                            modifier = Modifier.weight(1f).height(16.dp), valueRange = 0.8f..1.3f, steps = 9)
                        Text("${(editFontScale * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Substance accents", style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SubstanceAccentMode.entries.forEach { mode ->
                            FilterChip(
                                selected = editSubstanceAccentMode == mode,
                                onClick = { onSubstanceAccentModeChange(mode); applyTheme() },
                                label = { Text(mode.name.lowercase(), style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
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
                    Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(onClick = { applyTheme() }, modifier = Modifier.weight(1f)) { Text("Apply") }
                        AppTonalButton(onClick = { onBaseThemeChange(ThemeDefaults.Dark.baseTheme); onPrimaryChange(ThemeDefaults.Dark.primaryColor); onSecondaryChange(ThemeDefaults.Dark.secondaryColor); onTertiaryChange(ThemeDefaults.Dark.tertiaryColor); onBgColorChange(ThemeDefaults.Dark.backgroundColor ?: 0xFF0A2621L); onSurfaceColorChange(ThemeDefaults.Dark.surfaceColor ?: 0xFF10332BL); onTextColorChange(ThemeDefaults.Dark.textColor); onSurfaceTintChange(null); onOutlineStrengthChange(null); onFontFamilyChange(ThemeDefaults.Dark.fontFamily); onCardStyleChange(ThemeDefaults.Dark.cardStyle); onCornerRadiusChange(ThemeDefaults.Dark.cornerRadius); onDensityChange(ThemeDefaults.Dark.density); onBgImageChange(null); onBgOpacityChange(0.3f); onFontScaleChange(1.0f); onAnimationScaleChange(1.0f); onSubstanceAccentModeChange(SubstanceAccentMode.ADAPTIVE); applyTheme() },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.weight(1f)) { Text("Reset") }
                    }
            }
        }
    }
}

/**
 * A preset picker card: name + a swatch strip of the palette (primary,
 * secondary, tertiary, background, surface). Active presets get a primary
 * border. Clicking applies the variant matching the current dark/light mode.
 */
@Composable
private fun PresetCard(
    preset: ThemePreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null
) {
    val v = preset.variant(ThemeManager.instance.isDarkTheme())
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(v.primary, v.secondary, v.tertiary, v.background ?: Color.Transparent, v.surface ?: Color.Transparent)
                    .forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(c)
                                .border(1.dp, Color.Black.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        )
                    }
                Spacer(Modifier.weight(1f))
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Delete preset",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    }
                }
                if (selected) Icon(Icons.Default.Check, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Aa",
                    fontFamily = when (preset.fontFamily ?: AppFontFamily.SANS) {
                        AppFontFamily.SANS -> FontFamily.SansSerif
                        AppFontFamily.SERIF -> FontFamily.Serif
                        AppFontFamily.MONO -> FontFamily.Monospace
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary else v.primary
                )
                Text(preset.name, style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
