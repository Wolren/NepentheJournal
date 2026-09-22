package app.journal.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared inner padding for content cards.
 *
 * 16 dp is the value almost every card already used (SectionCard,
 * CollapsibleSettingsCard, the settings and sync cards, the duration and
 * session sections); ChartCard was the lone 14 dp outlier, so the token
 * settles on 16 dp. Charts gain 2 dp of breathing room, which is the only
 * visual shift this consolidation introduces.
 */
val CardContentPadding: Dp = 16.dp
