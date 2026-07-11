package app.journal.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Wraps a list item with a fast entrance animation.
 *
 * Unlike a delayed stagger approach, this animation is intentionally fast
 * (completes in ~100ms) so it works correctly during scrolling — items
 * that enter the viewport via scroll animate in quickly without queueing
 * visible lag.
 *
 * - No slide (looks janky during vertical scroll)
 * - No stagger delay (causes visible latency when scrolling fast)
 * - Subtle fade + scale-in using snappy spring physics
 *
 * For reordering animations (items shifting position in the list), use
 * [Modifier.animateItemPlacement] on the item directly instead.
 *
 * Use inside LazyColumn's [items] block:
 * ```kotlin
 * itemsIndexed(list, key = { _, item -> item.id }) { index, item ->
 *     AnimatedListItem { MyCard(item) }
 * }
 * ```
 */
@Composable
fun AnimatedListItem(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val visible = remember { mutableStateOf(false) }

    // No delay — appear immediately with a fast spring animation
    LaunchedEffect(Unit) {
        visible.value = true
    }

    AnimatedVisibility(
        visible = visible.value,
        enter = scaleIn(
            animationSpec = spring(
                dampingRatio = 0.7f,
                stiffness = 600f   // snappy — completes in ~100ms
            ),
            initialScale = 0.97f
        ) +
            fadeIn(
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = 800f  // very fast fade — no visible latency
                )
            ),
        modifier = modifier
    ) {
        content()
    }
}
