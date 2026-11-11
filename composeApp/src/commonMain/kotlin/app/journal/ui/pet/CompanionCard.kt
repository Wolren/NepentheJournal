package app.journal.ui.pet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.ui.components.AppCard

private fun PetCorner.label(): String = when (this) {
    PetCorner.TOP_START -> "Top left"
    PetCorner.TOP_END -> "Top right"
    PetCorner.BOTTOM_START -> "Bottom left"
    PetCorner.BOTTOM_END -> "Bottom right"
}

/** Settings card for the petdex companion: pick a set, size it and pin it to a corner. */
@Composable
fun CompanionCard() {
    val settings by PetManager.instance.settings.collectAsState()
    val pets = remember { availablePets() }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Pets, null, tint = MaterialTheme.colorScheme.primary)
                Text("Companion", style = MaterialTheme.typography.titleMedium)
            }
            Switch(
                checked = settings.enabled,
                enabled = pets.isNotEmpty(),
                onCheckedChange = { enabled ->
                    val slug = settings.slug.ifBlank { pets.firstOrNull()?.slug.orEmpty() }
                    PetManager.instance.update(settings.copy(enabled = enabled, slug = slug))
                }
            )
        }
        if (pets.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No petdex sets found. Install one with npx petdex install <slug>.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@AppCard
        }
        if (!settings.enabled) {
            Spacer(Modifier.height(8.dp))
            Text(
                "The companion sits in a window corner and runs while a live session is open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@AppCard
        }

        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pets.forEach { pet ->
                FilterChip(
                    selected = settings.slug == pet.slug,
                    onClick = { PetManager.instance.update(settings.copy(slug = pet.slug)) },
                    label = { Text(pet.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Slider(
                value = settings.scale,
                onValueChange = { PetManager.instance.update(settings.copy(scale = it)) },
                valueRange = 0.3f..1.2f,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(settings.scale * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(40.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Column {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PetCorner.entries.forEach { corner ->
                    FilterChip(
                        selected = settings.corner == corner,
                        onClick = { PetManager.instance.update(settings.copy(corner = corner)) },
                        label = { Text(corner.label(), style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}
