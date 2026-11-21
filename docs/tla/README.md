# TLA+ model of the sync join

`SyncMerge.tla` models the tombstone half of the conflict rules. Each device keeps a
grow-only set of observed write and delete operations. A sync step unions two views.
The visible value is the newest operation: a delete at the newest clock hides the row,
and a write newer than every known delete makes it visible again.

TLC checks these properties:

- `TypeOK`: every view holds valid operations.
- `VisibleMatchesNewest`: the displayed value agrees with the newest operation.
- `NoVisibleValueOlderThanDelete`: a visible value is never older than a delete the
  device already knows.
- `EqualViewsSameValue`: equal views display the same value.
- `ViewsConvergeEventually`: under fair sync, every device reaches the same view.

## Run

Download `tla2tools.jar` from the TLA+ releases, then:

```powershell
java -cp tla2tools.jar tlc2.TLC -config docs/tla/SyncMerge.cfg docs/tla/SyncMerge.tla
```

The checked model uses 3 devices, clocks 0 to 3, and 2 values. Raise the bounds only
when a change needs a longer history; the reachable state count grows quickly.

## Scope

The model covers the delete rule and convergence under fair sync. Per-field clock
registers are covered by `MergeLawPropertyTest` in the desktop test suite; this model
does not model fields.
