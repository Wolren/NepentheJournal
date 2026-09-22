/*
 * Nepenthe Journal - GPLv3
 * Copyright (C) 2026 Wolren
 *
 * Derived from PsychonautWiki Journal (GPL-3.0-or-later)
 * Copyright (C) 2022 Isaak Hanimann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.journal.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import app.journal.data.IJournalRepository
import app.journal.data.JournalStore
import app.journal.ui.components.PersonEditorDialog

@Composable
internal fun AppDialogs(
    repo: IJournalRepository,
    journalStore: JournalStore,
    ready: Boolean,
) {
    // ── Data integrity: startup recovery dialog ──
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var recoveryMessage by remember { mutableStateOf("") }

    LaunchedEffect(ready) {
        // Check after the first render cycle to let the UI settle. Gated on
        // ready so the check runs against the loaded store, not an empty one.
        if (!ready) return@LaunchedEffect
        kotlinx.coroutines.delay(100)
        if (journalStore.lastLoadHadIssues) {
            recoveryMessage = journalStore.lastLoadIssueSummary
            showRecoveryDialog = true
        }
    }

    // ── Recovery dialog ──
    if (showRecoveryDialog) {
        AlertDialog(
            onDismissRequest = { showRecoveryDialog = false },
            title = { Text("Data Recovery Notice") },
            text = {
                Text("Your journal data had issues when loading:\n\n$recoveryMessage\n\nDo you want to continue with the partially recovered data or restore from the previous backup (.bak)?")
            },
            confirmButton = {
                TextButton(onClick = { showRecoveryDialog = false }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val restored = journalStore.restoreFromBackup()
                    if (restored) {
                        recoveryMessage = "Restored from backup successfully."
                    } else {
                        recoveryMessage = "No backup available to restore from."
                    }
                    showRecoveryDialog = false
                }) {
                    Text("Restore from backup")
                }
            }
        )
    }

    // ── First run: welcome + profile prompt ──
    val persons by repo.persons.collectAsState()
    val welcomeCompleted by repo.welcomeCompleted.collectAsState()
    var showProfileEditor by remember { mutableStateOf(false) }
    if (!welcomeCompleted && persons.isEmpty()) {
        // Shown once per install: mark completed on first display, not
        // on button press. Closing the window with the dialog open
        // otherwise persists nothing and the prompt returns forever.
        LaunchedEffect(Unit) { repo.setWelcomeCompleted(true) }
        AlertDialog(
            onDismissRequest = { repo.setWelcomeCompleted(true) },
            title = { Text("Welcome to Nepenthe Journal") },
            text = {
                Text(
                    "Trips belong to individuals. Create your profile so doses, timelines " +
                        "and dose.wiki exports carry the right demographics from the start. " +
                        "You can add more individuals later in Settings, Individuals."
                )
            },
            confirmButton = {
                TextButton(onClick = { showProfileEditor = true }) { Text("Create your profile") }
            },
            dismissButton = {
                TextButton(onClick = { repo.setWelcomeCompleted(true) }) { Text("Skip") }
            }
        )
    }
    if (showProfileEditor) {
        PersonEditorDialog(
            initial = null,
            onDismiss = { showProfileEditor = false },
            dialogTitle = "Create your profile",
            onSave = { person ->
                repo.upsertPerson(person.copy(isSelf = true))
                showProfileEditor = false
                repo.setWelcomeCompleted(true)
            }
        )
    }
}
