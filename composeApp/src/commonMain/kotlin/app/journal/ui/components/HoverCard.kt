package app.journal.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Card with smooth elevation transition on hover and press.
 *
 * - Hover raises the card (subtle lift on desktop)
 * - Press lowers it (push-in feedback)
 * - Springs are tuned for fluid motion
 *
 * Drop-in replacement for [Card] with onClick. On mobile the hover
 * state is inactive and elevation stays at [restingElevation].
 */
@Composable
fun HoverCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    restingElevation: Dp = 1.dp,
    hoverElevation: Dp = 4.dp,
    pressedElevation: Dp = 0.dp,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val targetElevation = when {
        isPressed -> pressedElevation
        isHovered -> hoverElevation
        else -> restingElevation
    }

    val elevation by animateDpAsState(
        targetValue = targetElevation,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 400f
        ),
        label = "cardElevation"
    )

    Card(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = elevation
        )
    ) {
        content()
    }
}
