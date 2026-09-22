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
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import app.journal.util.PlatformLock


/**
 * DataFrame and session-bundle export projections, split out of
 * JournalRepository in the wave2 structural refactor. Reads run under the
 * facade lock so every export is self-consistent.
 */
internal class JournalExportFrames(
    private val lock: PlatformLock,
    private val sessionsStore: EntityStore<Session>,
    private val dosesStore: EntityStore<Dose>,
    private val substancesStore: EntityStore<Substance>,
    private val indices: JournalIndices,
) {
    fun sessionsDataFrame(): List<SessionDataRow> = lock.withLock {
        val subNameCache = substancesStore.all.associate { it.id to it.name }
        sessionsStore.all.map { session ->
            val sessionDoses = indices._dosesBySession[session.id]?.toList() ?: emptyList()
            val subNames = sessionDoses.mapNotNull { subNameCache[it.substanceId] }.distinct()
            val dt = Instant.fromEpochMilliseconds(session.startTime)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val endDt = session.endTime?.let {
                Instant.fromEpochMilliseconds(it)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
            }
            val durationHours = session.endTime?.let {
                (it - session.startTime) / 3600000.0
            }
            SessionDataRow(
                id = session.id, title = session.title,
                date = dt.date.toString(), startTime = dt.toString(),
                endTime = endDt?.toString(),
                durationHours = durationHours?.let { kotlin.math.round(it * 100) / 100.0 },
                set = session.set, setting = session.setting,
                intention = session.intention, outcome = session.outcome,
                rating = session.rating, shulginRating = session.shulginRating,
                consumerName = session.consumerName,
                isFavorite = session.isFavorite, isArchived = session.isArchived,
                substanceNames = subNames.joinToString(";"),
                doseCount = sessionDoses.size
            )
        }
    }

    fun dosesDataFrame(): List<DoseDataRow> = lock.withLock {
        val subNameCache = substancesStore.all.associate { it.id to it.name }
        dosesStore.all.map { dose ->
            DoseDataRow(
                id = dose.id, sessionId = dose.sessionId,
                substanceId = dose.substanceId,
                substanceName = subNameCache[dose.substanceId] ?: "unknown",
                route = dose.routeOfAdministration,
                amount = dose.amount, unit = dose.unit,
                timestamp = dose.timestamp, redosing = dose.redosing,
                isEstimate = dose.isDoseEstimate, notes = dose.notes
            )
        }
    }

    fun substancesDataFrame(): List<SubstanceDataRow> = lock.withLock {
        substancesStore.all.map { sub ->
            SubstanceDataRow(
                id = sub.id, name = sub.name,
                aliases = sub.aliases.joinToString("; "),
                substanceClass = sub.substanceClass.joinToString("; "),
                cid = sub.cid,
                molecularFormula = sub.chemicalProperties?.molecularFormula,
                molecularWeight = sub.chemicalProperties?.molecularWeight,
                iupacName = sub.chemicalProperties?.iupacName,
                logP = sub.chemicalProperties?.xlogP,
                routes = sub.routesOfAdministration.joinToString("; "),
                effects = sub.effects.joinToString("; "),
                toxicity = sub.toxicity.joinToString("; "),
                addictionPotential = sub.addictionPotential
            )
        }
    }

    fun exportSessionBundles(): List<SessionBundle> = lock.withLock {
        sessionsStore.all.sortedByDescending { it.startTime }.map { session ->
            SessionBundle(session, indices._dosesBySession[session.id]?.toList() ?: emptyList())
        }
    }
}
