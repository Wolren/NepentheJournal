package app.journal.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.journal.ui.theme.CardStyle
import app.journal.ui.theme.LocalSpacing
import app.journal.ui.theme.LocalThemeConfig
import app.journal.ui.theme.blend

/** Cards follow the theme's medium radius, so the corner setting applies everywhere. */
@Composable
fun cardShape(): Shape = MaterialTheme.shapes.medium

/** Chips and small pills follow the theme's small radius. */
@Composable
fun chipShape(): Shape = MaterialTheme.shapes.small

/** Outlined cards fill with the surface; filled and elevated cards use the accent-tinted variant. */
@Composable
fun appCardContainer(): Color = when (LocalThemeConfig.current.cardStyle) {
    CardStyle.OUTLINED -> MaterialTheme.colorScheme.surface
    else -> MaterialTheme.colorScheme.surfaceVariant
}

/** Card fill for the selected card style; [alpha] fades the fill for de-emphasised cards. */
@Composable
fun appCardColors(
    container: Color = appCardContainer(),
    alpha: Float = 1f,
): CardColors {
    // Card opacity comes from the theme, so translucent panels show the backdrop through every card.
    val painted = container.copy(alpha = (alpha * LocalThemeConfig.current.cardOpacity).coerceIn(0f, 1f))
    return CardDefaults.cardColors(containerColor = painted)
}

/**
 * Fill for a block nested inside a card: one tone step away from the card, so
 * tiles, rows and bands stay distinct in every theme.
 */
@Composable
fun appInnerContainer(): Color = blend(appCardContainer(), MaterialTheme.colorScheme.onSurface, 0.06f)

/** Inner fill that carries a hue: the hue mixes into the step instead of fading into the card. */
@Composable
fun appInnerContainer(tint: Color, alpha: Float = 0.12f): Color =
    blend(appInnerContainer(), tint, alpha)

/** Hairline for a nested block; a [tint] keeps coloured tiles tied to their accent. */
@Composable
fun appInnerBorder(tint: Color? = null): BorderStroke =
    BorderStroke(
        width = 1.dp,
        color = if (tint == null) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                else tint.copy(alpha = 0.45f)
    )

/** Elevated cards lift by 2 dp; filled and outlined cards stay flat. */
@Composable
fun appCardElevation(default: Dp = 0.dp): CardElevation =
    CardDefaults.cardElevation(
        defaultElevation = if (LocalThemeConfig.current.cardStyle == CardStyle.ELEVATED) maxOf(default, 2.dp) else 0.dp
    )

/** Outlined cards draw an outline-colour border; filled and elevated cards draw none. */
@Composable
fun appCardBorder(): BorderStroke? =
    if (LocalThemeConfig.current.cardStyle == CardStyle.OUTLINED) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    } else null

/**
 * Inner padding for a card: the regular step, or the tighter step used by
 * inline rows, event rows and search hits.
 */
@Composable
fun appCardPadding(dense: Boolean = false): PaddingValues =
    PaddingValues(if (dense) LocalSpacing.current.densePadding else LocalSpacing.current.cardPadding)

/**
 * Card shell shared by every content card: the theme picks fill, border,
 * elevation and corners, and the density step picks the inner padding.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = cardShape(),
    colors: CardColors = appCardColors(),
    elevation: CardElevation = appCardElevation(),
    border: BorderStroke? = appCardBorder(),
    contentPadding: PaddingValues = appCardPadding(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** Clickable card shell: same tokens as [AppCard], plus the ripple and semantics of a button. */
@Composable
fun AppCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = cardShape(),
    colors: CardColors = appCardColors(),
    elevation: CardElevation = appCardElevation(),
    border: BorderStroke? = appCardBorder(),
    contentPadding: PaddingValues = appCardPadding(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}
