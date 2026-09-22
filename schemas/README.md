# Snapshot schema files

JSON Schema (draft 2020-12) definitions for `JournalSnapshot` persisted files.

| File | Status |
|---|---|
| `journal-snapshot-v5.json` | Historical schema version 5. Referenced by `docs/journal-snapshot-spec.md`; kept as the golden shape of v5-era files. Not regenerated: it must keep matching what v5 writers actually produced. |
| `journal-snapshot-v6.json` | **Never existed.** Version 6 was skipped in this directory: `git log --all --diff-filter=A -- schemas/*v6*` is empty, no commit in history ever added a v6 schema file, and the repository has no tags at all, so no tagged release carries a "v6 schema" claim. (In CODE only, `JournalSnapshot.CURRENT_VERSION` did pass through 6 for one day between `ac1f320`, 2026-09-03, 5 -> 6, and `1bfa9b6`, 2026-09-04, 6 -> 7; files written in that window declare `version: 6` but validate against their field set, which the identity migration in `AppJson.apply` loads as-is. No golden file or fixture was ever cut for them.) |
| `journal-snapshot-v7.json` | **Current.** `version.const` must equal `JournalSnapshot.CURRENT_VERSION` (`data/JournalStore.kt`), and every `$defs/<Entity>` property set must mirror that entity's serializer fields exactly. Both rules are asserted by `desktopTest/data/SnapshotSchemaTest.kt`, so edit this file in the same change as any `JournalSnapshot`/model field add or remove. |

Do not add a `journal-snapshot-v6.json` retroactively: the numbering gap is
historical fact, and a fabricated v6 golden would contradict files that
actually shipped with `version: 6`.
