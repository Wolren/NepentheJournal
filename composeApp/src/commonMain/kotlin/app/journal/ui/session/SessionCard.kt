package app.journal.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.ui.components.HoverCard
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.foregroundFor
import app.journal.ui.theme.isDarkTheme
import app.journal.util.TimeDisplayMode
import app.journal.util.currentTimeMillis
import app.journal.util.formatClockTime
import app.journal.util.formatDuration
import app.journal.util.formatElapsedSinceStart
import app.journal.util.formatRelativeTime
import app.journal.util.isDesktopPlatform
import kotlinx.coroutines.delay

@Composable
fun SessionCard(
    session: Session,
    doses: List<Dose>,
    substanceNameMap: Map<String, String>,
    timeDisplayMode: TimeDisplayMode,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val isDark = isDarkTheme()
    // Card accent follows the session's actual substances, falling back to the
    // title only for doseless sessions, so every card carries its own color.
    val accentSource = remember(substanceNameMap, doses, session.title) {
        doses.firstOrNull()?.let { substanceNameMap[it.substanceId] } ?: session.title
    }
    val subColor = remember(accentSource) { AdaptiveColors.colorFor(accentSource) }
    val accent = subColor.getComposeColor(isDark)

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
            // Quiet 4dp accent bar - solid, not gradient soup
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
                        if (session.endTime == null) {
                            Spacer(Modifier.height(4.dp))
                            LiveElapsedLabel(session)
                        }
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
                        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(28.dp)) {
                            Icon(
                                if (session.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Toggle favorite",
                                tint = if (session.isFavorite) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
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

            }
        }
    }
}

/** Ticking LIVE pill for open sessions: red dot plus elapsed time net of pauses. */
@Composable
private fun LiveElapsedLabel(session: Session) {
    var tick by remember(session.id) { mutableStateOf(currentTimeMillis()) }
    LaunchedEffect(session.id, session.endTime, session.pausedAt) {
        while (session.endTime == null) {
            delay(1000)
            tick = currentTimeMillis()
        }
    }
    val pausedExtra = if (session.pausedAt != null) tick - session.pausedAt else 0L
    val elapsed = (tick - session.startTime - session.pausedMs - pausedExtra).coerceAtLeast(0L)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape)
        )
        Text(
            text = "LIVE · ${formatDuration(0L, elapsed)}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
    }
}
