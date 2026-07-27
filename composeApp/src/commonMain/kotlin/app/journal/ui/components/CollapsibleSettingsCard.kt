package app.journal.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.journal.util.isDesktopPlatform

/**
 * Reusable collapsible settings card with icon, title, and expandable content.
 * Replaces the repeated Card+Row+Spacer+Divider+Spacer+content pattern
 * used by every settings section.
 *
 * @param expanded whether the card content is visible
 * @param onToggle called when the header row is clicked
 * @param icon leading icon
 * @param title card title
 * @param modifier passed to the outer Card
 * @param content rendered inside the collapsed area when [expanded] is true
 */
@Composable
fun CollapsibleSettingsCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .then(
                    if (isDesktopPlatform()) Modifier.animateContentSize(
                        animationSpec = spring(dampingRatio = 1f, stiffness = 2000f)
                    ) else Modifier
                )
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}
