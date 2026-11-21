# Conflict resolution: current rules and the target model

Status: phases 1 and 2 landed. Phase 3 is planned, not built.

## What runs today

Timestamp last-writer-wins on whole entities, with two branches that keep the losing text.
Since phase 2 the order is a hybrid logical clock when both copies carry one, so a write
that happened later wins even when the two clocks disagree by milliseconds.

| Entity | Rule |
|---|---|
| Sessions, doses, substances, effects, interactions, timeline events, custom units | when both copies carry a non-blank `clock`, `(physicalMs, counter)` decides first and `deviceOrigin` breaks an exact tie; otherwise incoming is accepted when `updatedAt(incoming) >= updatedAt(existing)` |
| Sessions, both directions since phase 1 | local copy blocks the incoming edit and writes `conflict:<sessionId>:<device>` with both outcomes |
| Notes | higher clock (higher `updatedAt` when either side is blank) wins, tie goes to incoming, the loser body becomes a `ConflictSibling` |
| Deletes | a tombstone outranks any write with `updatedAt <= deletedAt`; a newer write is a later operation and wins |

Phase 1 removed two holes: a push older than a local delete can no longer resurrect the
row, and the pull path now runs the same session merge as the push path
(`SyncResponse.senderDeviceId` names the conflict note).

Phase 2 added the hybrid logical clock from Kulkarni et al., 2014 to the eight synced
entities. Every local repository write takes a fresh `clock`; every sync apply keeps the
peer stamp and advances the local generator past it. Snapshot version 10 derives
`<updatedAt>:0` for rows loaded from older files, so their relative order does not change
until each row is rewritten. The wire protocol is v3.

Known limits that remain:

- Edits to different fields of the same entity are not merged; the later write replaces the whole row.
- Deletes still compare wall-clock `updatedAt`; a clocked edit whose `updatedAt` is older than
  the delete loses even when its clock is newer. Phase 3 gives tombstones their own clock.
- A pair where only one side carries a clock is ordered by `updatedAt`. That relation is not
  transitive across a mix of stamped and unstamped rows, so the merge laws hold per pair, not
  over arbitrary triples, until every row carries a stamp.
- Clock physical time is bounded to the same window as `updatedAt` (after 2000-01-01, at most
  one day ahead). Validation drops a peer stamp outside it, so a hostile value cannot pin the
  local clock for years.
- Conflict notes are local annotations; their body is labelled from the writing device's
  perspective and replicates afterwards.

## Target model

A state-based CRDT: per-field LWW registers ordered by a hybrid logical clock, plus OR-Set tombstones. The merge is a join on a semilattice, so strong eventual consistency follows from four testable properties: idempotence, commutativity, associativity, convergence under random interleavings.

1. Hybrid logical clock `(physicalMs, counter, nodeId)` replaces the wall clock. Monotonic, causal, bounded skew. The node id is the entity's `deviceOrigin`, applied as the tiebreak. Snapshot version 10 derives a clock from existing `updatedAt`.
2. Per-field registers on sessions, doses and notes: concurrent edits to different fields merge, concurrent edits to one field resolve by clock.
3. OR-Set tombstones carry their own clock and outrank older writes permanently.
4. Text: either keep both bodies (field-level siblings) or adopt an RGA text engine such as Automerge or Yrs. That decision is separate and expensive.

## Phase plan

| Phase | Change | State |
|---|---|---|
| 1 | tombstone outranks older writes; symmetric session merge on pull | done |
| 2 | hybrid logical clock in the entity timestamp; snapshot version 10 and wire protocol v3 | done |
| 3 | per-field registers for sessions, doses, notes | planned |
| 4 | text CRDT or field-level sibling retention | open question |

Verification for phases 2 and 3: property tests for the four semilattice laws over random states, a small TLA+ spec of merge and tombstones model-checked with TLC, and the existing crash fuzz for persistence.
