package app.journal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.journal.model.InteractionRisk

/**
 * Display colors for interaction risk levels.
 * Centralized to avoid duplication across InteractionGroup and InlineInteractionWarning.
 */
object InteractionColors {

    @Composable
    fun color(risk: InteractionRisk): Color = when (risk) {
        InteractionRisk.DANGEROUS -> Color(0xFFD32F2F)
        InteractionRisk.UNSAFE -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    @Composable
    fun backgroundColor(risk: InteractionRisk): Color = when (risk) {
        InteractionRisk.DANGEROUS -> Color(0xFFD32F2F).copy(alpha = 0.1f)
        InteractionRisk.UNSAFE -> Color(0xFFFF9800).copy(alpha = 0.1f)
        else -> Color.Transparent
    }
}
