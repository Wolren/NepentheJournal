package app.journal.ui.settings.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.IJournalRepository
import app.journal.model.AutosaveDelay
import app.journal.model.RatingScaleMode
import app.journal.model.StartTab
import app.journal.util.TimeDisplayMode

/**
 * Every user preference the journal stores, grouped by what it changes.
 *
 * Extracted from SettingsScreen so the contents can be rendered by a smoke
 * test without driving the two-pane navigation first. Each control writes
 * straight through the repository setter, which marks the snapshot dirty, so
 * a change is on disk after the next autosave.
 */
@Composable
internal fun PreferencesCardContent(repo: IJournalRepository) {
    val ratingMode by repo.ratingScaleMode.collectAsState()
    val timeMode by repo.timeDisplayMode.collectAsState()
    val startTab by repo.startTab.collectAsState()
    val autosave by repo.autosaveDelay.collectAsState()
    val showTrend by repo.showSessionsTrendChart.collectAsState()
    val showHeatmap by repo.dashboardHeatmap.collectAsState()
    val showTolerance by repo.dashboardTolerance.collectAsState()
    val showTop by repo.dashboardTopSubstances.collectAsState()
    val confirmDeletes by repo.confirmDeletes.collectAsState()
    val autoBackup by repo.autoBackupOnClose.collectAsState()

    PreferenceSectionTitle("Rating scale")
    RatingScaleMode.entries.forEach { mode ->
        PreferenceRadioRow(
            selected = ratingMode == mode,
            title = mode.label,
            subtitle = mode.description,
            onSelect = { repo.setRatingScaleMode(mode) },
        )
    }

    PreferenceDivider()
    PreferenceSectionTitle("Time display")
    PreferenceCaption("How session timestamps render in lists and timelines.")
    TimeDisplayMode.entries.forEach { mode ->
        val (title, hint) = mode.settingText()
        PreferenceRadioRow(
            selected = timeMode == mode,
            title = title,
            subtitle = hint,
            onSelect = { repo.setTimeDisplayMode(mode) },
        )
    }

    PreferenceDivider()
    PreferenceSectionTitle("Open on")
    StartTab.entries.forEach { tab ->
        PreferenceRadioRow(
            selected = startTab == tab,
            title = tab.label,
            subtitle = tab.description,
            onSelect = { repo.setStartTab(tab) },
        )
    }

    PreferenceDivider()
    PreferenceSectionTitle("Free-form autosave")
    PreferenceCaption("How long the live session waits after the last keystroke before it writes the draft.")
    AutosaveDelay.entries.forEach { delay ->
        PreferenceRadioRow(
            selected = autosave == delay,
            title = delay.label,
            subtitle = delay.description,
            onSelect = { repo.setAutosaveDelay(delay) },
        )
    }

    PreferenceDivider()
    PreferenceSectionTitle("Dashboard cards")
    PreferenceSwitchRow(
        title = "Activity heatmap",
        subtitle = "Year of session activity, one square per day",
        checked = showHeatmap,
        onCheckedChange = { repo.setDashboardHeatmap(it) },
    )
    PreferenceSwitchRow(
        title = "Tolerance overview",
        subtitle = "Per-substance tolerance from recent doses",
        checked = showTolerance,
        onCheckedChange = { repo.setDashboardTolerance(it) },
    )
    PreferenceSwitchRow(
        title = "Top substances",
        subtitle = "Substances ranked by sessions",
        checked = showTop,
        onCheckedChange = { repo.setDashboardTopSubstances(it) },
    )
    PreferenceSwitchRow(
        title = "Sessions per week chart",
        subtitle = "Weekly trend line across the dashboard",
        checked = showTrend,
        onCheckedChange = { repo.setShowSessionsTrendChart(it) },
    )

    PreferenceDivider()
    PreferenceSectionTitle("Deleting")
    PreferenceSwitchRow(
        title = "Ask before deleting",
        subtitle = "Confirm dialogs for sessions and timeline events",
        checked = confirmDeletes,
        onCheckedChange = { repo.setConfirmDeletes(it) },
    )

    PreferenceDivider()
    PreferenceSectionTitle("Backups")
    PreferenceSwitchRow(
        title = "Auto-backup on close",
        subtitle = "Writes a timestamped backup when the app closes",
        checked = autoBackup,
        onCheckedChange = { repo.setAutoBackupOnClose(it) },
    )
}

/** Small heading above one settings group. */
@Composable
private fun PreferenceSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** One line of explanation under a settings heading. */
@Composable
private fun PreferenceCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PreferenceDivider() {
    Spacer(Modifier.height(10.dp))
    HorizontalDivider()
    Spacer(Modifier.height(10.dp))
}

/** A radio row with a title and a one-line explanation. */
@Composable
private fun PreferenceRadioRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A switch row with a title and a one-line explanation. */
@Composable
private fun PreferenceSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Display form of a timestamp mode, shown in the Time display group. */
private fun TimeDisplayMode.settingText(): Pair<String, String> = when (this) {
    TimeDisplayMode.RELATIVE -> "Relative" to "2h ago, Yesterday, 3w ago"
    TimeDisplayMode.CLOCK -> "Clock time" to "Wall clock, 15:30"
    TimeDisplayMode.ELAPSED -> "Since session start" to "T+1:23 from the opening dose"
    TimeDisplayMode.DURATION -> "Elapsed duration" to "1h 23m of session time"
}
