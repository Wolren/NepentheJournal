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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.model.Substance
import app.journal.model.TimelineEvent
import app.journal.ui.components.DoseDotMeter
import app.journal.ui.components.HoverCard
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.isDarkTheme
import app.journal.util.TimeDisplayMode
import app.journal.util.currentTimeMillis
import app.journal.util.formatClockTime
import app.journal.util.formatDuration
import app.journal.util.formatDoseAmount
import app.journal.util.formatElapsedSinceStart
import app.journal.util.formatRelativeTime
import app.journal.util.isDesktopPlatform
import app.journal.util.referenceDoseAmount
import kotlinx.coroutines.delay

/**
 * Compact card for one session: identity and controls on top, then the
 * psychonaut-journal stack - effect timeline, one row per dose with a
 * dot-meter of its size, and the session's cumulative doses per substance.
 *
 * Every lookup (substance names, dosage bands) arrives through the maps the
 * list screen passes in; the card never touches a repository.
 */
@Composable
fun SessionCard(
    session: Session,
    doses: List<Dose>,
    events: List<TimelineEvent>,
    substancesById: Map<String, Substance>,
    timeDisplayMode: TimeDisplayMode,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val isDark = isDarkTheme()
    val sortedDoses = remember(doses) { doses.sortedBy { it.timestamp } }
    // Card accent follows the session's actual substances, falling back to the
    // title only for doseless sessions, so every card carries its own color.
    val accentSource = remember(substancesById, doses, session.title) {
        doses.firstOrNull()?.let { substancesById[it.substanceId]?.name } ?: session.title
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
        Column(modifier = Modifier.fillMaxWidth()) {
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
                }
            }

            // Body sits under the header text: 4dp spine + 14dp header padding.
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 18.dp, end = 14.dp, bottom = 14.dp)
            ) {
                SessionEffectTimeline(
                    session = session,
                    doses = sortedDoses,
                    events = events,
                    substancesById = substancesById,
                    accent = accent,
                )

                if (sortedDoses.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SectionDivider()
                    Spacer(Modifier.height(12.dp))
                    sortedDoses.forEachIndexed { index, dose ->
                        if (index > 0) {
                            Spacer(Modifier.height(12.dp))
                            SectionDivider()
                            Spacer(Modifier.height(12.dp))
                        }
                        DoseRow(
                            dose = dose,
                            substancesById = substancesById,
                            isDark = isDark,
                        )
                    }
                    CumulativeDoses(
                        doses = sortedDoses,
                        substancesById = substancesById,
                        isDark = isDark,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.16f),
    )
}

/**
 * One dose as a PsychonautWiki-style row: accent bar, time, substance, amount
 * with a dimmed route, and the dot matrix that shows the size of the dose
 * against the substance's typical dose.
 */
@Composable
private fun DoseRow(
    dose: Dose,
    substancesById: Map<String, Substance>,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val substance = substancesById[dose.substanceId]
    val name = substance?.name ?: dose.substanceId
    val color = AdaptiveColors.colorFor(name).getComposeColor(isDark)
    val reference = remember(substance, dose.unit) {
        substance?.let { referenceDoseAmount(it.dosageBands, dose.unit) }
    }

    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.align(Alignment.Top)
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(color.copy(alpha = 0.9f))
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatClockTime(dose.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = amountWithRoute(dose),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
        if (reference != null) {
            Spacer(Modifier.width(10.dp))
            DoseDotMeter(
                amount = dose.amount,
                reference = reference,
                color = color,
                modifier = Modifier.align(Alignment.Bottom),
            )
        }
    }
}

/** "2400 mg oral · Full meal" - the number leads, everything else dims out. */
@Composable
private fun amountWithRoute(dose: Dose): androidx.compose.ui.text.AnnotatedString {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    return buildAnnotatedString {
        val prefix = if (dose.isDoseEstimate) "~" else ""
        append("$prefix${formatDoseAmount(dose.amount)} ${dose.unit}")
        val route = dose.routeOfAdministration
        if (route.isNotBlank()) {
            append(" ")
            withStyle(SpanStyle(color = secondary, fontWeight = FontWeight.Normal)) {
                append(route)
            }
        }
        dose.stomachFullness?.let {
            append(" · ")
            withStyle(SpanStyle(color = secondary, fontWeight = FontWeight.Normal)) {
                append(it.label)
            }
        }
    }
}

/**
 * Session totals per substance: the whole reason the dot matrix exists - it is
 * the one place the card answers "how much did this session actually add up to".
 */
@Composable
private fun CumulativeDoses(
    doses: List<Dose>,
    substancesById: Map<String, Substance>,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val groups = remember(doses, substancesById) {
        doses.groupBy { it.substanceId to it.unit }
            .map { (key, list) ->
                val substance = substancesById[key.first]
                CumulativeGroup(
                    name = substance?.name ?: key.first,
                    unit = key.second,
                    amount = list.sumOf { it.amount },
                    route = list.map { it.routeOfAdministration }
                        .distinct()
                        .singleOrNull()
                        ?.takeIf { it.isNotBlank() },
                    reference = substance?.let { referenceDoseAmount(it.dosageBands, key.second) },
                )
            }
            .filter { it.amount > 0.0 }
            .sortedBy { it.name.lowercase() }
    }
    if (groups.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        SectionDivider()
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Your cumulative doses",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        groups.forEachIndexed { index, group ->
            Spacer(Modifier.height(if (index == 0) 10.dp else 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append("${formatDoseAmount(group.amount)} ${group.unit}")
                            group.route?.let { append(" $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (group.reference != null) {
                    Spacer(Modifier.width(12.dp))
                    val color = AdaptiveColors.colorFor(group.name).getComposeColor(isDark)
                    DoseDotMeter(
                        amount = group.amount,
                        reference = group.reference,
                        color = color,
                        dotSize = 9.dp,
                        gap = 4.dp,
                    )
                }
            }
        }
    }
}

private data class CumulativeGroup(
    val name: String,
    val unit: String,
    val amount: Double,
    val route: String?,
    val reference: Double?,
)

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
