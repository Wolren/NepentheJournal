package app.journal.ui.substances.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    accentColor: Color? = null,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (danger) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                             else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = accentColor ?: MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.65f))
    }
}

@Composable
internal fun RouteChip(route: String) {
    val color = routeColor(route)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Surface(
            modifier = Modifier.size(14.dp),
            shape = RoundedCornerShape(7.dp),
            color = color
        ) {}
        Text(route, style = MaterialTheme.typography.labelSmall,
            color = color, fontWeight = FontWeight.Medium)
    }
}
