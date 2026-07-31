package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.model.ShulginRating

@Composable
fun SessionRatingSection(
    useShulgin: Boolean,
    shulginRating: String,
    onShulginRatingChange: (String) -> Unit,
    rating: String,
    onRatingChange: (String) -> Unit
) {
    Text("Rating", style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    if (useShulgin) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ShulginRating.entries.forEach { s ->
                FilterChip(
                    selected = shulginRating == s.name,
                    onClick = {
                        onShulginRatingChange(if (shulginRating == s.name) "" else s.name)
                    },
                    label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(32.dp)
                )
            }
        }
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            (1..10).forEach { n ->
                FilterChip(
                    selected = rating == n.toString(),
                    onClick = {
                        onRatingChange(if (rating == n.toString()) "" else n.toString())
                    },
                    label = { Text(n.toString(), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(32.dp)
                )
            }
        }
    }
}
