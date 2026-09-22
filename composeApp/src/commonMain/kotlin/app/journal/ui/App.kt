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
import app.journal.data.DataInitializer
import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
import app.journal.data.JournalStore
import app.journal.ui.theme.LocalThemeConfig
import app.journal.ui.theme.NepentheTypography
import app.journal.ui.theme.ThemeManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(repo: IJournalRepository = JournalRepository.instance) {
    val themeManager = remember { ThemeManager.instance }
    val themeConfig by themeManager.config.collectAsState()

    // Startup gate: heavy init (seed plus DoseWiki JSON) runs on a background
    // scope from the platform launcher. Show a loading screen until
    // DataInitializer.initializedFlow opens, never a blank window.
    val ready by DataInitializer.initializedFlow.collectAsState()

    val journalStore = remember(repo) { JournalStore(repo) }

    // ── Data integrity: save + auto-backup on close ──
    DisposableEffect(Unit) {
        onDispose {
            // Save in-memory changes FIRST, then back up the fresh file.
            // Backing up without saving would copy a file up to 2s stale
            // (autosave debounce) and silently drop the last edits (audit S2).
            journalStore.save()
            journalStore.triggerAutoBackup()
        }
    }

    if (!ready) {
        AppSplash(themeManager)
        return
    }

    CompositionLocalProvider(
        LocalThemeConfig provides themeConfig,
        LocalJournalRepository provides repo
    ) {
        val isDark = themeManager.isDarkTheme()
        val colorScheme = themeManager.colorScheme(isDark)

        MaterialTheme(
            colorScheme = colorScheme,
            typography = NepentheTypography,
            shapes = themeConfig.shapes
        ) {
            AppDialogs(repo = repo, journalStore = journalStore, ready = ready)
            AppNavigation(repo = repo, journalStore = journalStore, themeConfig = themeConfig, colorScheme = colorScheme)
        }  // closes MaterialTheme
    }  // closes CompositionLocalProvider
}  // closes App