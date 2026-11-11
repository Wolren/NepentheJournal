package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.model.rules.ActiveInteractionCheck
import app.journal.data.IJournalRepository
import app.journal.ui.LocalJournalRepository
import app.journal.model.*
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis
import app.journal.ui.session.live.*
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSessionScreen(
    repo: IJournalRepository = LocalJournalRepository.current,
    session: Session, onBack: () -> Unit
) {
    val listState = rememberLazyListState()

    val allDoses by repo.doses.collectAsState()
    val allSessions by repo.sessions.collectAsState()
    val live = allSessions.find { it.id == session.id } ?: session
    val sessionDoses = remember(allDoses, live.id) { allDoses.filter { it.sessionId == live.id }.sortedBy { it.timestamp } }
    val allTimelineEvents by repo.timelineEvents.collectAsState()
    val sessionEvents = remember(allTimelineEvents, live.id) { allTimelineEvents.filter { it.sessionId == live.id }.sortedBy { it.timestamp } }
    val allSubstances by repo.substances.collectAsState()
    val allInteractions by repo.interactions.collectAsState()
    val allMedications by repo.medications.collectAsState()

    var minuteTick by remember { mutableStateOf(currentTimeMillis() / 60_000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minuteTick = currentTimeMillis() / 60_000
        }
    }

    val activeInteractionResult = remember(
        sessionDoses, allMedications, allDoses, allSubstances, allInteractions, minuteTick
    ) {
        ActiveInteractionCheck.run(
            sessionDoses = sessionDoses,
            medications = allMedications,
            allDoses = allDoses,
            ownDoseIds = sessionDoses.map { it.id }.toSet(),
            excludeSessionId = live.id,
            substancesById = allSubstances.associateBy { it.id },
            interactions = allInteractions,
        )
    }

    var showDoseDialog by remember { mutableStateOf(false) }
    var showMoodDialog by remember { mutableStateOf(false) }
    var showCrisisDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showEndConfirm by remember { mutableStateOf(false) }
    var deletingLiveEvent by remember { mutableStateOf<TimelineEvent?>(null) }
    var editTitle by remember(live.id) { mutableStateOf(live.title.ifBlank { "Live Session" }) }
    var editSet by remember(live.id) { mutableStateOf(live.set ?: "") }
    var editSetting by remember(live.id) { mutableStateOf(live.setting ?: "") }
    var editIntention by remember(live.id) { mutableStateOf(live.intention ?: "") }

    LaunchedEffect(sessionEvents.size) {
        val lastIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (sessionEvents.isNotEmpty() && lastIndex >= sessionEvents.size - 2)
            listState.animateScrollToItem(sessionEvents.size - 1)
    }

    if (showDoseDialog) { QuickDoseDialog(substances = allSubstances, session = session, repo = repo, onDismiss = { showDoseDialog = false }) }
    if (showMoodDialog) { QuickMoodDialog(session = session, repo = repo, onDismiss = { showMoodDialog = false }) }
    if (showCrisisDialog) { CrisisResourcesDialog(onDismiss = { showCrisisDialog = false }) }
    if (showEditDialog) {
        LiveSessionEditDialog(title = editTitle, setText = editSet, settingText = editSetting, intention = editIntention,
            onTitleChange = { editTitle = it }, onSetChange = { editSet = it }, onSettingChange = { editSetting = it },
            onIntentionChange = { editIntention = it },
            onSave = {
                repo.upsertSession(live.copy(title = editTitle, set = editSet.ifBlank { null }, setting = editSetting.ifBlank { null },
                    intention = editIntention.ifBlank { null }, updatedAt = currentTimeMillis()))
                showEditDialog = false
            }, onDismiss = { showEditDialog = false })
    }
    if (showEndConfirm) {
        AlertDialog(onDismissRequest = { showEndConfirm = false }, title = { Text("End session?") },
            text = { Text("Set the session end time to now and return to the session list.") },
            confirmButton = { AppTextButton(onClick = { repo.upsertSession(live.copy(endTime = currentTimeMillis(), updatedAt = currentTimeMillis())); showEndConfirm = false; onBack() }) { Text("End") } },
            dismissButton = { AppTextButton(onClick = { showEndConfirm = false }) { Text("Cancel") } })
    }
    if (deletingLiveEvent != null) {
        AlertDialog(onDismissRequest = { deletingLiveEvent = null }, title = { Text("Delete event?") },
            text = { Text("Delete \"${deletingLiveEvent?.label}\"? This cannot be undone.") },
            confirmButton = { AppTextButton(onClick = { deletingLiveEvent?.let { repo.deleteTimelineEvent(it.id) }; deletingLiveEvent = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { AppTextButton(onClick = { deletingLiveEvent = null }) { Text("Cancel") } })
    }

    val substancesById = remember(allSubstances) { allSubstances.associateBy { it.id } }
    val mergedTimeline = remember(sessionEvents, sessionDoses, substancesById) {
        val eventItems = sessionEvents.map { LiveTimelineItem.Event(it) as LiveTimelineItem }
        val doseItems = sessionDoses.map { d -> LiveTimelineItem.Dosage(d, substancesById[d.substanceId]) as LiveTimelineItem }
        (eventItems + doseItems).sortedBy { when (it) { is LiveTimelineItem.Event -> it.event.timestamp; is LiveTimelineItem.Dosage -> it.dose.timestamp } }.reversed()
    }

    ScreenScaffold(
        title = live.title.ifBlank { "Live Session" }, onBack = onBack,
        selectable = true,
        actions = {
            Box { IconButton(onClick = { showCrisisDialog = true }) { Icon(Icons.Default.Emergency, contentDescription = "Get help", tint = MaterialTheme.colorScheme.error) } }
            Box { IconButton(onClick = { showEditDialog = true }) { Icon(Icons.Default.Edit, contentDescription = "Edit session info") } }
            Button(
                onClick = { showEndConfirm = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Stop")
            }
        }
    ) {
        item {
            TimerCard(session = live)
        }

        if (!activeInteractionResult.isEmpty) {
            item {
                InteractionWarnings(
                    result = activeInteractionResult,
                    substanceNameLookup = { id -> substancesById[id]?.name ?: id },
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTonalButton(onClick = { showDoseDialog = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Log Dose")
                    }
                    AppTonalButton(onClick = { showMoodDialog = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.Favorite, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("How I Feel")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) { PhaseChip("Onset", TimelineEventType.ONSET, session, repo) }
                    Box(Modifier.weight(1f)) { PhaseChip("Comeup", TimelineEventType.COMEUP, session, repo) }
                    Box(Modifier.weight(1f)) { PhaseChip("Peak", TimelineEventType.PEAK, session, repo) }
                    Box(Modifier.weight(1f)) { PhaseChip("Offset", TimelineEventType.OFFSET, session, repo) }
                }
            }
        }

        if (mergedTimeline.isNotEmpty()) {
            item { Text("Timeline", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
            items(mergedTimeline, key = { when (it) { is LiveTimelineItem.Event -> "evt:${it.event.id}"; is LiveTimelineItem.Dosage -> "dose:${it.dose.id}" } }) { item ->
                when (item) {
                    is LiveTimelineItem.Event -> LiveEventCard(item.event, session.startTime, repo = repo, onDelete = { deletingLiveEvent = it })
                    is LiveTimelineItem.Dosage -> LiveDoseCard(item.dose, item.substance, session.startTime)
                }
            }
        } else {
            item {
                AppCard(modifier = Modifier.fillMaxWidth(), contentPadding = appCardPadding(dense = true)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("Session started. Log a dose or check in to begin tracking.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            AppButton(onClick = { showCrisisDialog = true }, modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.Emergency, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Get Help Now", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
