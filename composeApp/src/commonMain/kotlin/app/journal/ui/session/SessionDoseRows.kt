package app.journal.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.journal.model.Dose
import app.journal.model.Substance
import app.journal.ui.components.DoseDotMeter
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.isDarkTheme
import app.journal.util.formatClockTime
import app.journal.util.formatDoseAmount
import app.journal.util.referenceDoseAmount

/**
 * PsychonautWiki-style dose presentation for a session detail view: one row per
 * dose - accent bar, time, substance, amount with dimmed route, and a dot
 * matrix against the substance's reference dose.
 *
 * The screen passes the substance map in; these rows never touch a repository.
 */

@Composable
internal fun SessionDoseRows(
    doses: List<Dose>,
    substancesById: Map<String, Substance>,
    modifier: Modifier = Modifier,
) {
    if (doses.isEmpty()) return
    val isDark = isDarkTheme()
    Column(modifier = modifier.fillMaxWidth()) {
        doses.forEachIndexed { index, dose ->
            if (index > 0) {
                Spacer(Modifier.height(12.dp))
                DoseSectionDivider()
                Spacer(Modifier.height(12.dp))
            }
            SessionDoseRow(
                dose = dose,
                substancesById = substancesById,
                isDark = isDark,
            )
        }
    }
}

/**
 * Full-strength outlineVariant: the alpha-0.16 copy of this token is invisible
 * on a dark surface, and the rows lose their separation.
 */
@Composable
internal fun DoseSectionDivider() {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * One dose as a PsychonautWiki-style row: accent bar, time, substance, amount
 * with a dimmed route, and the dot matrix that shows the size of the dose
 * against the substance's typical dose.
 */
@Composable
internal fun SessionDoseRow(
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
private fun amountWithRoute(dose: Dose): AnnotatedString {
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

