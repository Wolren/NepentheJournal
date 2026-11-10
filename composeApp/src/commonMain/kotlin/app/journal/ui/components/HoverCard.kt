package app.journal.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun HoverCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    useAnimations: Boolean = true,
    restingElevation: Dp = 0.dp,
    hoverElevation: Dp = 1.5.dp,
    pressedElevation: Dp = 0.dp,
    shape: Shape = MaterialTheme.shapes.medium,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    // Mobile card cost: Surface shadow layer and two interaction collectors, 2.5 ms per row measured.
    if (!useAnimations) {
        StaticCard(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            border = border,
            content = content
        )
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val targetElevation = when {
        isPressed -> pressedElevation
        isHovered -> hoverElevation
        else -> restingElevation
    }

    val elevation: Dp
    if (useAnimations) {
        val anim by animateDpAsState(
            targetValue = targetElevation,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
            label = "cardElevation"
        )
        elevation = anim
    } else {
        elevation = targetElevation
    }

    val hoverBoost by animateFloatAsState(
        targetValue = if (isHovered && !isPressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
        label = "hoverBoost"
    )
    val resolvedBorder = border?.let { b ->
        if (hoverBoost > 0.01f) BorderStroke(b.width, b.brush) else b
    }

    Card(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = shape,
        colors = appCardColors(),
        border = resolvedBorder ?: appCardBorder(),
        elevation = appCardElevation(elevation)
    ) {
        content()
    }
}

/** Clickable surface with the same look and ripple, minus shadow layer and interaction state. */
@Composable
private fun StaticCard(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape,
    border: BorderStroke?,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        propagateMinConstraints = true
    ) {
        content()
    }
}
