package app.journal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.journal.model.InteractionRisk

/**
 * Display colors for interaction risk levels.
 * Centralized to avoid duplication across InteractionGroup, InlineInteractionWarning
 * and the live warning banner/cards, which read the raw values below directly.
 */
object InteractionColors {

    /**
     * Non-composable palette for callers that only need a raw Color: warning
     * banners, card surfaces and icons outside a risk-level branch. The
     * composable entry points below delegate to these, so there is still one
     * value per risk level.
     */
    val dangerous: Color = Color(0xFFD32F2F)
    val unsafe: Color = Color(0xFFFF9800)

    @Composable
    fun color(risk: InteractionRisk): Color = when (risk) {
        InteractionRisk.DANGEROUS -> dangerous
        InteractionRisk.UNSAFE -> unsafe
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    @Composable
    fun backgroundColor(risk: InteractionRisk): Color = when (risk) {
        InteractionRisk.DANGEROUS -> dangerous.copy(alpha = 0.1f)
        InteractionRisk.UNSAFE -> unsafe.copy(alpha = 0.1f)
        else -> Color.Transparent
    }
}
