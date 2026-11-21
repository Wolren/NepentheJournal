# ADR 0001: text conflict strategy

Status: accepted on 2026-11-21.

## Context

- Sync is LAN peer to peer, state based, and persisted as JSON snapshots. Phase 3
  merges sessions, doses and notes per field with hybrid logical clocks. Every other
  synced entity merges whole.
- Same-field text edits resolve last writer wins. Notes keep the losing body in
  `conflictSiblings`; a session outcome conflict becomes a
  `conflict:<sessionId>:<device>` note. Session freeform, session titles and dose notes
  have no sibling store, so a concurrent edit loses one side silently.
- Clients are Kotlin Multiplatform: desktop JVM, Android, iOS. iOS cannot be compiled
  on the Windows development host. Backups, exports and the Obsidian path all read
  plain JSON.
- Text volume is small: journal entries, notes, titles. Conflicts are rare. One person
  edits on a few devices, mostly not at the same time.

## Options

1. **Sibling retention.** Keep the losing revision next to the winner, per text field,
   and let the user reconcile. This extends the existing `ConflictSibling` idea from
   notes to sessions and doses. No new dependency, no format change beyond an additive
   JSON field, and the merge stays field-level LWW.
2. **Automerge.** Adopt a Rust CRDT with JVM, Android and iOS bindings. Character-level
   merging, but every text field becomes an opaque CRDT document with its own storage,
   sync, compaction and export path.
3. **Yrs.** Same shape as Automerge (Yjs-compatible Rust core), same binding, binary
   size and iOS verification costs.

## Decision

Adopt sibling retention for text. Do not adopt Automerge or Yrs in this cycle.

The losing revision of a text field must survive a merge and be visible to the user.
The exact field layout (a per-field sibling map, or one `conflictSiblings` list per
entity) is left to the implementation ticket, which must stay additive to snapshot
version 11 or bump the version.

## Rationale

- The existing merge already has a tested value that a text engine would not improve:
  no user text is destroyed. What is missing is coverage (session freeform, dose notes)
  and a review surface, not character-level merge quality.
- Automerge and Yrs add a native dependency on three platforms. The iOS leg cannot be
  built or tested on this host, so a text engine would ship unverified there. The
  packaging, binary size, key store interaction and backup format changes are larger
  than the conflict problem they solve.
- Character-level merging helps when two people type into one paragraph at the same
  time. The journal's conflict pattern is two devices editing the same entry hours
  apart. Sibling retention handles that case with no ambiguity.
- The JSON export, Obsidian export and encrypted backup paths stay readable if the text
  stays a string with optional siblings. An opaque CRDT blob breaks that property.

## Consequences

- A concurrent same-field text edit keeps both revisions, and the user resolves it.
  Nothing merges at character level.
- The UI must show siblings for every text field that gains them, not only notes. Until
  that lands, the retention is data-level only.
- Sibling lists grow only on real conflicts; replays are idempotent, so a conflict is
  recorded once.
- The merge stays a field-level LWW join. The four semilattice laws remain testable
  without a second engine.

## Revisit triggers

- Two people edit the same text field concurrently as a normal workflow (shared
  journal, live collaboration).
- Sibling volume becomes a support burden that manual review cannot handle.
- A maintained, KMP-packaged text CRDT appears with an iOS build verified on CI, or the
  project gains an iOS build host.

Until one of those holds, sibling retention is the cheaper correct choice.
