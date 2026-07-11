package app.journal.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Press-animation modifier. Wraps a composable in a scale animation that
 * shrinks slightly on press and springs back on release.
 *
 * @param scaleWhenPressed animation target scale (e.g. 0.97f for buttons, 0.90f for icon buttons)
 */
@Composable
private fun Modifier.pressScale(
    scaleWhenPressed: Float = 0.97f,
    interactionSource: MutableInteractionSource
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) scaleWhenPressed else 1f,
        label = "press_scale"
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Internal helper: renders a leading icon with spacing if [icon] is provided.
 */
@Composable
private fun RowScope.IconSlot(icon: ImageVector?, contentDescription: String?) {
    if (icon != null) {
        Icon(icon, contentDescription, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
    }
}

/**
 * Filled button with press animation and theme shape.
 * Replaces bare [Button].
 */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    shape: androidx.compose.ui.graphics.Shape? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.pressScale(interactionSource = interactionSource),
        enabled = enabled,
        colors = colors ?: ButtonDefaults.buttonColors(),
        interactionSource = interactionSource,
        shape = shape ?: MaterialTheme.shapes.medium,
        contentPadding = ButtonDefaults.ContentPadding
    ) {
        IconSlot(icon, contentDescription)
        content()
    }
}

/**
 * Outlined button with press animation and theme shape.
 * Replaces bare [OutlinedButton].
 */
@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    shape: androidx.compose.ui.graphics.Shape? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.pressScale(interactionSource = interactionSource),
        enabled = enabled,
        colors = colors ?: ButtonDefaults.outlinedButtonColors(),
        interactionSource = interactionSource,
        shape = shape ?: MaterialTheme.shapes.medium,
        contentPadding = ButtonDefaults.ContentPadding
    ) {
        IconSlot(icon, contentDescription)
        content()
    }
}

/**
 * Tonal button with press animation and theme shape.
 * Replaces bare [FilledTonalButton].
 */
@Composable
fun AppTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    shape: androidx.compose.ui.graphics.Shape? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.pressScale(interactionSource = interactionSource),
        enabled = enabled,
        colors = colors ?: ButtonDefaults.filledTonalButtonColors(),
        interactionSource = interactionSource,
        shape = shape ?: MaterialTheme.shapes.medium,
        contentPadding = ButtonDefaults.ContentPadding
    ) {
        IconSlot(icon, contentDescription)
        content()
    }
}

/**
 * Text button with press animation.
 * Replaces bare [TextButton]. Use in dialogs and secondary actions.
 */
@Composable
fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier.pressScale(interactionSource = interactionSource),
        enabled = enabled,
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.small,
        contentPadding = ButtonDefaults.TextButtonContentPadding
    ) {
        IconSlot(icon, contentDescription)
        content()
    }
}

/**
 * Icon button with press animation (tighter scale for compact feel).
 * Replaces bare [IconButton].
 */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector,
    contentDescription: String?,
    tint: androidx.compose.ui.graphics.Color = LocalContentColor.current
) {
    val interactionSource = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier.pressScale(scaleWhenPressed = 0.90f, interactionSource = interactionSource),
        enabled = enabled,
        interactionSource = interactionSource
    ) {
        Icon(icon, contentDescription, tint = tint)
    }
}
