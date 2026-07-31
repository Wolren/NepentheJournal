/*
 * Nepenthe Journal — GPLv3
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

package app.journal.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class CheckIn(
    val timestamp: Long,
    val overallIntensity: Float,
    val effectScores: Map<String, Float> = emptyMap(),
    val mood: String? = null,
    val notes: String? = null
)

/** Conflict: FIELD_LEVEL_MERGE */
/** Shulgin rating scale: +/- (not sure), + (mild), ++ (moderate), +++ (strong), ++++ (very strong) */
enum class ShulginRating(val label: String, val numericValue: Int) {
    PLUS_MINUS("+/-", 1),
    PLUS("+", 3),
    PLUS_PLUS("++", 5),
    PLUS_PLUS_PLUS("+++", 7),
    PLUS_PLUS_PLUS_PLUS("++++", 9)
}

@Immutable
@Serializable
data class Session(
    override val id: String,
    
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val title: String,
    val startTime: Long,
    val endTime: Long? = null,
    val set: String? = null,
    val setting: String? = null,
    val intention: String? = null,
    val outcome: String? = null,
    val notes: String? = null,
    val rating: Int? = null,
    val shulginRating: String? = null,
    val checkins: List<CheckIn> = emptyList(),
    val isArchived: Boolean = false,
    val isFavorite: Boolean = false,
    val consumerName: String? = null,
    val profile: SessionProfile? = null
) : VaultDocument
