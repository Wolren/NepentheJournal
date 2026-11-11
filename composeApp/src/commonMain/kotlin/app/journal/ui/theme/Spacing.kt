package app.journal.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout gaps for one density step: screen edge, card interior, gap between
 * cards, and gap between rows inside a card. Read through [LocalSpacing].
 */
@Immutable
data class SpacingTokens(
    val screenPadding: Dp,
    val cardPadding: Dp,
    val densePadding: Dp,
    val cardGap: Dp,
    val innerGap: Dp,
)

/** Compact packs 25 percent tighter than comfortable; spacious opens 35 percent wider. */
val Density.spacing: SpacingTokens
    get() = when (this) {
        Density.COMPACT -> SpacingTokens(
            screenPadding = 12.dp,
            cardPadding = 12.dp,
            densePadding = 10.dp,
            cardGap = 8.dp,
            innerGap = 6.dp,
        )
        Density.COMFORTABLE -> SpacingTokens(
            screenPadding = 16.dp,
            cardPadding = 16.dp,
            densePadding = 12.dp,
            cardGap = 12.dp,
            innerGap = 8.dp,
        )
        Density.SPACIOUS -> SpacingTokens(
            screenPadding = 24.dp,
            cardPadding = 22.dp,
            densePadding = 16.dp,
            cardGap = 18.dp,
            innerGap = 12.dp,
        )
    }

/** Comfortable is the default so a screen composed outside [App] keeps today's gaps. */
val LocalSpacing = staticCompositionLocalOf { Density.COMFORTABLE.spacing }
