# Conflict resolution: current rules and the target model

Status: phase 1 landed. Phases 2 and 3 are planned, not built.

## What runs today

Timestamp last-writer-wins on whole entities, with two branches that keep the losing text.

| Entity | Rule |
|---|---|
| Sessions, doses, substances, effects, interactions, timeline events, custom units | incoming accepted when `updatedAt(incoming) >= updatedAt(existing)` |
| Sessions, both directions since phase 1 | local strictly newer blocks the incoming edit and writes `conflict:<sessionId>:<device>` with both outcomes |
| Notes | higher `updatedAt` wins, tie goes to incoming, the loser body becomes a `ConflictSibling` |
| Deletes | a tombstone outranks any write with `updatedAt <= deletedAt`; a newer write is a later operation and wins |

Phase 1 removed two holes: a push older than a local delete can no longer resurrect the row, and the pull path now runs the same session merge as the push path (`SyncResponse.senderDeviceId` names the conflict note).

Known limits that remain:

- Edits to different fields of the same entity are not merged; the later timestamp replaces the whole row.
- Timestamps are wall-clock milliseconds. Validation bounds them to after 2000-01-01 and at most one day in the future, so a fast clock still wins inside that window.
- Equal timestamps go to whichever side applies last.
- Conflict notes are local annotations; their body is labelled from the writing device's perspective and replicates afterwards.

## Target model

A state-based CRDT: per-field LWW registers ordered by a hybrid logical clock, plus OR-Set tombstones. The merge is a join on a semilattice, so strong eventual consistency follows from four testable properties: idempotence, commutativity, associativity, convergence under random interleavings.

1. Hybrid logical clock `(physicalMs, counter, nodeId)` replaces the wall clock. Monotonic, causal, bounded skew (Kulkarni et al., 2014). Snapshot version 10 derives a clock from existing `updatedAt`.
2. Per-field registers on sessions, doses and notes: concurrent edits to different fields merge, concurrent edits to one field resolve by clock.
3. OR-Set tombstones carry their own clock and outrank older writes permanently.
4. Text: either keep both bodies (field-level siblings) or adopt an RGA text engine such as Automerge or Yrs. That decision is separate and expensive.

## Phase plan

| Phase | Change | State |
|---|---|---|
| 1 | tombstone outranks older writes; symmetric session merge on pull | done |
| 2 | hybrid logical clock in the entity timestamp; snapshot and wire version bump | planned |
| 3 | per-field registers for sessions, doses, notes | planned |
| 4 | text CRDT or field-level sibling retention | open question |

Verification for phases 2 and 3: property tests for the four semilattice laws over random states, a small TLA+ spec of merge and tombstones model-checked with TLC, and the existing crash fuzz for persistence.
