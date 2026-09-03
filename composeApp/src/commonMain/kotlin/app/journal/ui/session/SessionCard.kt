package app.journal.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.ui.components.HoverCard
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager
import app.journal.ui.theme.foregroundFor
import app.journal.util.TimeDisplayMode
import app.journal.util.formatClockTime
import app.journal.util.formatDuration
import app.journal.util.formatElapsedSinceStart
import app.journal.util.formatRelativeTime
import app.journal.util.isDesktopPlatform

@Composable
fun SessionCard(
    session: Session,
    timeDisplayMode: TimeDisplayMode,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    val repo = remember { JournalRepository.instance }
    val themeManager = remember { ThemeManager.instance }
    val isDark = themeManager.isDarkTheme()
    val doses = remember(session.id) { repo.dosesForSession(session.id) }
    val subColor = remember(session.title) { AdaptiveColors.colorFor(session.title) }
    val accent = subColor.getComposeColor(isDark)

    val substanceNameMap = remember(doses) {
        doses.associate { dose ->
            dose.substanceId to (repo.getSubstance(dose.substanceId)?.name ?: dose.substanceId)
        }
    }

    HoverCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        useAnimations = isDesktopPlatform(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        ) {
            // Quiet 4dp accent bar — solid, not gradient soup
            Box(
                Modifier.fillMaxHeight().width(4.dp)
                    .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .background(accent.copy(alpha = 0.85f))
            )
            Column(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }

                        if (!session.consumerName.isNullOrBlank()) {
                            Text(
                                text = session.consumerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                        }

                        Spacer(Modifier.height(2.dp))

                        val dateText = when (timeDisplayMode) {
                            TimeDisplayMode.RELATIVE -> formatRelativeTime(session.startTime)
                            TimeDisplayMode.CLOCK -> formatClockTime(session.startTime)
                            TimeDisplayMode.ELAPSED -> formatElapsedSinceStart(session.startTime)
                            TimeDisplayMode.DURATION -> formatDuration(session.startTime)
                        }
                        Text(
                            text = dateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (session.rating != null || session.shulginRating != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                border = BorderStroke(
                                    1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                ),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(
                                        text = session.shulginRating ?: "${session.rating}/10",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }

                if (doses.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        doses.forEach { dose ->
                            val subName = substanceNameMap[dose.substanceId]
                            if (subName != null) {
                                val doseColor = AdaptiveColors.colorFor(subName)
                                val doseAccent = doseColor.getComposeColor(isDark)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = doseAccent.copy(alpha = 0.88f)
                                ) {
                                    val prefix = if (dose.isDoseEstimate) "~" else ""
                                    Text(
                                        text = buildString {
                                            append("$subName $prefix${dose.amount} ${dose.unit}")
                                            if (dose.stomachFullness != null) append(" · ${dose.stomachFullness.label.take(3)}")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = foregroundFor(doseAccent),
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (doses.isNotEmpty()) {
                    val totalAmount = doses.sumOf { it.amount }
                    if (totalAmount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            doses.forEachIndexed { i, dose ->
                                val subName = substanceNameMap[dose.substanceId]
                                val fraction = (dose.amount / totalAmount).toFloat()
                                if (fraction > 0.01f) {
                                    val color = if (subName != null)
                                        AdaptiveColors.colorFor(subName).getComposeColor(isDark)
                                    else MaterialTheme.colorScheme.primary
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .weight(fraction.coerceAtLeast(0.02f)),
                                        color = color.copy(alpha = 0.85f),
                                        shape = if (i == 0) RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp)
                                                else if (i == doses.lastIndex) RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)
                                                else RoundedCornerShape(3.dp)
                                    ) {}
                                }
                            }
                        }
                    }
                }

                if (session.isFavorite) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = "Favorite",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
