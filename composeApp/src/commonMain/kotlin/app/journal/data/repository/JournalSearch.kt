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
import app.journal.util.PlatformLock


/**
 * Full-text search index ownership plus the dirty-flag rebuild discipline,
 * split out of JournalRepository in the wave2 structural refactor. Mutations
 * only flip the dirty bit; search rebuilds lazily under the facade lock.
 */
internal class JournalSearch(
    private val lock: PlatformLock,
    private val sessionsStore: EntityStore<Session>,
    private val substancesStore: EntityStore<Substance>,
    private val notesStore: EntityStore<Note>,
    private val dosesStore: EntityStore<Dose>,
    private val timelineEventsStore: EntityStore<TimelineEvent>,
    private val effectsStore: EntityStore<Effect>,
) {
    // ---- Full-text search index ----
    val searchIndex = SearchIndex()
    /**
     * Set by every single-entity mutation, cleared by
     * [rebuildSearchIndexLocked]. Mutations only flip this bit (O(1) under
     * the repo lock) instead of tokenizing every entity; [search] rebuilds
     * lazily before querying so results are never stale, and the auto-save
     * quiet-period tail rebuilds once per burst of edits. Guarded by [lock].
     */
    private var searchIndexDirty = true

    /**
     * Rebuild-if-dirty AND the index snapshot both happen under [lock]; the
     * scoring pass runs AFTER the lock is released, on that snapshot (wave4:
     * single-pass scoring outside the repo lock). Freshness is unchanged:
     * the rebuild still precedes the snapshot inside the same critical
     * section, so a search right after an upsert/delete never sees a stale
     * index, while ranking can no longer block a concurrent mutation or be
     * blocked by one. Ordering semantics live in scoreSearchSnapshot.
     */
    fun search(query: String): List<SearchResult> {
        val snapshot = lock.withLock {
            if (searchIndexDirty) rebuildSearchIndexLocked()
            searchIndex.snapshotIndex()
        }
        return scoreSearchSnapshot(query, snapshot)
    }

    /** Rebuild the search index only when a mutation dirtied it. */
    internal fun rebuildSearchIndexIfDirty() = lock.withLock {
        if (searchIndexDirty) rebuildSearchIndexLocked()
    }

    /** Flip the dirty bit instead of tokenizing every entity (callers hold [lock]). */
    internal fun markSearchIndexDirtyLocked() {
        searchIndexDirty = true
    }

    internal fun rebuildSearchIndexLocked() {
        searchIndex.rebuild(
            // EntityStore.all already returns a fresh snapshot per store: the old
            // extra .toList() copies were redundant.
            sessions = sessionsStore.all,
            substances = substancesStore.all,
            notes = notesStore.all,
            doses = dosesStore.all,
            timelineEvents = timelineEventsStore.all,
            effects = effectsStore.all,
            substanceNames = substancesStore.all.associate { it.id to it.name }
        )
        searchIndexDirty = false
    }

    fun rebuildSearchIndex() = lock.withLock { rebuildSearchIndexLocked() }
}
