------------------------------ MODULE SyncMerge ------------------------------
(***************************************************************************)
(* A small model of the sync join: each device keeps the set of operations  *)
(* it has observed (a grow-only set), sync unions two views, and the value  *)
(* a user sees is the newest operation in the local view. A delete that is  *)
(* newer than every write hides the row; a write newer than every delete is *)
(* visible again.                                                           *)
(*                                                                          *)
(* This is the tombstone half of phases 1 to 3. Per-field clock registers   *)
(* are covered by MergeLawPropertyTest; this model checks the delete rule   *)
(* and convergence under fair sync.                                         *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS Devices, Clocks, Values, NoValue

ASSUME NoValue \notin Values

\* One observed operation. Delete ops carry an arbitrary value slot.
Op == [type: {"write", "delete"}, clock: Clocks, value: Values \cup {NoValue}]

VARIABLES seen, nextClock

vars == <<seen, nextClock>>

TypeOK ==
    /\ seen \in [Devices -> SUBSET Op]
    /\ nextClock \in Nat

\* A clock is used at most once, so the maximum op in any view is unique.
UnusedClock == \A d \in Devices: \A op \in seen[d]: op.clock # nextClock

Write(d, v) ==
    /\ nextClock \in Clocks
    /\ UnusedClock
    /\ seen' = [seen EXCEPT ![d] = @ \union {[type |-> "write", clock |-> nextClock, value |-> v]}]
    /\ nextClock' = nextClock + 1

Delete(d) ==
    /\ nextClock \in Clocks
    /\ UnusedClock
    /\ seen' = [seen EXCEPT ![d] = @ \union {[type |-> "delete", clock |-> nextClock, value |-> NoValue]}]
    /\ nextClock' = nextClock + 1

Sync(from, to) ==
    /\ from # to
    /\ seen' = [seen EXCEPT ![to] = @ \union seen[from]]
    /\ UNCHANGED nextClock

Next ==
    \/ \E d \in Devices, v \in Values: Write(d, v)
    \/ \E d \in Devices: Delete(d)
    \/ \E from, to \in Devices: Sync(from, to)

Init ==
    /\ seen = [d \in Devices |-> {}]
    /\ nextClock = 0

Fairness == \A from \in Devices: \A to \in Devices: WF_vars(Sync(from, to))

Spec == Init /\ [][Next]_vars /\ Fairness

\* The highest clock in a non-empty view.
MaxClock(ops) ==
    CHOOSE c \in Clocks:
        c \in {op.clock : op \in ops} /\ \A op \in ops: op.clock <= c

\* The value a user sees: the newest write, unless the newest op is a delete.
Visible(ops) ==
    IF ops = {}
    THEN NoValue
    ELSE LET max == MaxClock(ops)
         IN IF \E op \in ops: op.type = "delete" /\ op.clock = max
            THEN NoValue
            ELSE (CHOOSE op \in ops: op.type = "write" /\ op.clock = max).value

VisibleMatchesNewest ==
    \A d \in Devices:
        LET ops == seen[d]
        IN ops = {} \/ Visible(ops) =
            IF (CHOOSE op \in ops: op.clock = MaxClock(ops)).type = "delete"
            THEN NoValue
            ELSE (CHOOSE op \in ops: op.type = "write" /\ op.clock = MaxClock(ops)).value

\* When a value is visible, the newest operation is a write and it beats every
\* delete the device knows. An older same-value write does not matter.
NoVisibleValueOlderThanDelete ==
    \A d \in Devices:
        LET ops == seen[d]
        IN ops = {} \/ \A del \in ops:
            del.type = "delete" =>
                \/ Visible(ops) = NoValue
                \/ LET newest == CHOOSE op \in ops: op.clock = MaxClock(ops)
                   IN newest.type = "write" /\ newest.clock > del.clock

EqualViewsSameValue ==
    \A d1, d2 \in Devices:
        seen[d1] = seen[d2] => Visible(seen[d1]) = Visible(seen[d2])

ViewsConverge == \A d1, d2 \in Devices: seen[d1] = seen[d2]

ViewsConvergeEventually == <>ViewsConverge

=============================================================================
