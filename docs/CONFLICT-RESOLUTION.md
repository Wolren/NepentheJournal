# Conflict resolution: current rules and the target model

Status: phases 1, 2 and 3 landed. The phase 4 text decision is open.

## What runs today

Sessions, doses and notes merge field by field, ordered by a hybrid logical clock. The
rest of the synced entities keep whole-row last-writer-wins. Deletes keep the phase 1 rule.

| Entity | Rule |
|---|---|
| Sessions, doses, notes | per-field LWW registers: a local write stamps only the fields whose value changed; a merge picks each field by its stamp |
| Sessions, outcome text | a conflict note is written only when the incoming row stamped its outcome in its latest write and that outcome loses; the note keeps both outcomes |
| Notes, body text | the losing body is kept as a `ConflictSibling`; title, links and the other fields merge independently |
| Substances, effects, interactions, timeline events, custom units | whole-row rule: clock first when both copies carry one, `updatedAt` otherwise, device origin breaks a tie |
| Any pair with a pre-v10 row | whole-row `updatedAt` fallback, so an old peer keeps working |
| Deletes | a tombstone outranks any write with `updatedAt <= deletedAt`; a newer write is a later operation and wins |

Phase 1 removed two holes: a push older than a local delete can no longer resurrect the
row, and the pull path runs the same session merge as the push path.

Phase 2 added the hybrid logical clock from Kulkarni et al., 2014 to the eight synced
entities. Every local repository write takes a fresh `clock`; every sync apply keeps the
peer stamp and advances the local generator past it. Snapshot version 10 derives
`<updatedAt>:0` for rows loaded from older files.

Phase 3 added per-field registers to sessions, doses and notes (`fieldClocks`, snapshot
version 11). Two peers that edit different fields of one row now keep both edits. A local
write diffs the serialized row against the stored row and moves only the changed fields'
stamps; unchanged fields keep theirs. The merge is a deterministic join: stamp first,
device origin on a tie, the encoded value on a full tie. The wire protocol stays v3; the
map is an additive field that older peers ignore.

Known limits that remain:

- Two edits to the SAME field still resolve by clock; the loser is discarded. Session
  outcome text is the exception: it lands in a conflict note. Dose notes and note titles
  have no sibling store.
- Per-field registers cover sessions, doses and notes only. Substances, effects,
  interactions, timeline events and custom units merge whole.
- A pair where either side has a blank row clock keeps the whole-row `updatedAt` rule.
  Field merging needs both sides stamped, which every row gets on its next write.
- Deletes still compare wall-clock `updatedAt`; a clocked edit whose `updatedAt` is older
  than the delete loses even when its clock is newer. Phase 3 does not change tombstones.
- Clock physical time is bounded to the same window as `updatedAt` (after 2000-01-01, at
  most one day ahead). Validation drops a peer stamp outside it.

## Target model

A state-based CRDT: per-field LWW registers ordered by a hybrid logical clock, plus OR-Set tombstones. The merge is a join on a semilattice, so strong eventual consistency follows from four testable properties: idempotence, commutativity, associativity, convergence under random interleavings.

1. Hybrid logical clock `(physicalMs, counter, nodeId)` replaces the wall clock. Monotonic, causal, bounded skew. The node id is the entity's `deviceOrigin`, applied as the tiebreak. Snapshot version 10 derives a clock from existing `updatedAt`.
2. Per-field registers on sessions, doses and notes (done in phase 3): concurrent edits to different fields merge, concurrent edits to one field resolve by clock.
3. OR-Set tombstones carry their own clock and outrank older writes permanently.
4. Text: either keep both bodies (field-level siblings) or adopt an RGA text engine such as Automerge or Yrs. That decision is separate and expensive.

## Phase plan

| Phase | Change | State |
|---|---|---|
| 1 | tombstone outranks older writes; symmetric session merge on pull | done |
| 2 | hybrid logical clock in the entity timestamp; snapshot version 10 and wire protocol v3 | done |
| 3 | per-field registers for sessions, doses, notes; snapshot version 11 | done |
| 4 | text CRDT or field-level sibling retention | open question |

Verification for phases 2 and 3: property tests for the four semilattice laws over random states, a small TLA+ spec of merge and tombstones model-checked with TLC, and the existing crash fuzz for persistence.
