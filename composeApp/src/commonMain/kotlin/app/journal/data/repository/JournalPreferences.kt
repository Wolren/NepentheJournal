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

package app.journal.data

import app.journal.model.*
import app.journal.util.TimeDisplayMode
import kotlinx.coroutines.flow.*
import app.journal.util.PlatformLock


/**
 * User preference StateFlows and their setters, split out of
 * JournalRepository in the wave2 structural refactor. Setters run under the
 * facade lock, exactly as before the split.
 */
internal class JournalPreferences(
    private val lock: PlatformLock,
    private val bumpMutationCount: () -> Unit,
) {
    private val _ratingScaleMode = MutableStateFlow(RatingScaleMode.OFF)
    val ratingScaleMode: StateFlow<RatingScaleMode> = _ratingScaleMode.asStateFlow()

    private val _useSubstanceColors = MutableStateFlow(true)
    val useSubstanceColors: StateFlow<Boolean> = _useSubstanceColors.asStateFlow()

    private val _welcomeCompleted = MutableStateFlow(false)
    val welcomeCompleted: StateFlow<Boolean> = _welcomeCompleted.asStateFlow()

    private val _seedFingerprint = MutableStateFlow<String?>(null)
    val seedFingerprint: StateFlow<String?> = _seedFingerprint.asStateFlow()

    private val _obsidianVaultPath = MutableStateFlow("")
    val obsidianVaultPath: StateFlow<String> = _obsidianVaultPath.asStateFlow()

    private val _obsidianAutoExport = MutableStateFlow(false)
    val obsidianAutoExport: StateFlow<Boolean> = _obsidianAutoExport.asStateFlow()

    private val _obsidianSubfolder = MutableStateFlow("Nepenthe")
    val obsidianSubfolder: StateFlow<String> = _obsidianSubfolder.asStateFlow()

    private val _obsidianFileOrganization = MutableStateFlow("flat")
    val obsidianFileOrganization: StateFlow<String> = _obsidianFileOrganization.asStateFlow()

    private val _showSessionsTrendChart = MutableStateFlow(false)
    val showSessionsTrendChart: StateFlow<Boolean> = _showSessionsTrendChart.asStateFlow()

    private val _startTab = MutableStateFlow(StartTab.DASHBOARD)
    val startTab: StateFlow<StartTab> = _startTab.asStateFlow()

    private val _timeDisplayMode = MutableStateFlow(TimeDisplayMode.RELATIVE)
    val timeDisplayMode: StateFlow<TimeDisplayMode> = _timeDisplayMode.asStateFlow()

    private val _dashboardHeatmap = MutableStateFlow(true)
    val dashboardHeatmap: StateFlow<Boolean> = _dashboardHeatmap.asStateFlow()

    private val _dashboardTolerance = MutableStateFlow(true)
    val dashboardTolerance: StateFlow<Boolean> = _dashboardTolerance.asStateFlow()

    private val _dashboardTopSubstances = MutableStateFlow(true)
    val dashboardTopSubstances: StateFlow<Boolean> = _dashboardTopSubstances.asStateFlow()

    private val _confirmDeletes = MutableStateFlow(true)
    val confirmDeletes: StateFlow<Boolean> = _confirmDeletes.asStateFlow()

    private val _autosaveDelay = MutableStateFlow(AutosaveDelay.FAST)
    val autosaveDelay: StateFlow<AutosaveDelay> = _autosaveDelay.asStateFlow()

    private val _autoBackupOnClose = MutableStateFlow(true)
    val autoBackupOnClose: StateFlow<Boolean> = _autoBackupOnClose.asStateFlow()

    fun setRatingScaleMode(mode: RatingScaleMode) = lock.withLock {
        _ratingScaleMode.value = mode
        bumpMutationCount()
    }

    fun setSubstanceColors(enabled: Boolean) = lock.withLock {
        _useSubstanceColors.value = enabled
    }

    fun setWelcomeCompleted(completed: Boolean) = lock.withLock {
        _welcomeCompleted.value = completed
        bumpMutationCount()
    }

    fun setSeedFingerprint(fingerprint: String?) = lock.withLock {
        _seedFingerprint.value = fingerprint
        bumpMutationCount()
    }

    fun setObsidianVaultPath(path: String) = lock.withLock {
        _obsidianVaultPath.value = path
        bumpMutationCount()
    }

    fun setObsidianAutoExport(enabled: Boolean) = lock.withLock {
        _obsidianAutoExport.value = enabled
        bumpMutationCount()
    }

    fun setObsidianSubfolder(folder: String) = lock.withLock {
        _obsidianSubfolder.value = folder
        bumpMutationCount()
    }

    fun setObsidianFileOrganization(org: String) = lock.withLock {
        _obsidianFileOrganization.value = org
        bumpMutationCount()
    }

    fun setShowSessionsTrendChart(enabled: Boolean) = lock.withLock {
        _showSessionsTrendChart.value = enabled
        bumpMutationCount()
    }

    fun setStartTab(tab: StartTab) = lock.withLock {
        _startTab.value = tab
        bumpMutationCount()
    }

    fun setTimeDisplayMode(mode: TimeDisplayMode) = lock.withLock {
        _timeDisplayMode.value = mode
        bumpMutationCount()
    }

    fun setDashboardHeatmap(shown: Boolean) = lock.withLock {
        _dashboardHeatmap.value = shown
        bumpMutationCount()
    }

    fun setDashboardTolerance(shown: Boolean) = lock.withLock {
        _dashboardTolerance.value = shown
        bumpMutationCount()
    }

    fun setDashboardTopSubstances(shown: Boolean) = lock.withLock {
        _dashboardTopSubstances.value = shown
        bumpMutationCount()
    }

    fun setConfirmDeletes(enabled: Boolean) = lock.withLock {
        _confirmDeletes.value = enabled
        bumpMutationCount()
    }

    fun setAutosaveDelay(delay: AutosaveDelay) = lock.withLock {
        _autosaveDelay.value = delay
        bumpMutationCount()
    }

    fun setAutoBackupOnClose(enabled: Boolean) = lock.withLock {
        _autoBackupOnClose.value = enabled
        bumpMutationCount()
    }

    internal fun resetForClearAll() {
        _ratingScaleMode.value = RatingScaleMode.OFF
        _useSubstanceColors.value = true
        _welcomeCompleted.value = false
        _seedFingerprint.value = null
    }
}
