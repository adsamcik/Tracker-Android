# Existing database archive cancellation — 2026-10-04

**IMPLEMENTED_UNVALIDATED / IMPLEMENTATION_ONLY.** Three exact HEAD-based selected
snapshots are pinned in `BACKUP_CANCELLATION_FILES.json`. Root supplied raw Git bytes
at `eea8e76ac393c65ea7040bd2bd55dbeb51c3e6b8`. No tests, compilation, Gradle, lint,
schema drift, diff-check, providers/device, CI, validation gates, Git or integration
were performed by this author.

## Selected behavior

`DatabaseBackupArchive.write` is now suspend and captures the admitted caller's
coroutine context. It checks cancellation before/after acquiring the existing
per-database lock, around each bounded read/write, around entry and manifest writes,
and after ZIP closure. Existing synchronous `use` cleanup still closes the input,
database lock and archive before the child completes. Cancellation cannot acquire
the next database lock or report an archive as successful after it was observed.
Fresh review B1 found that a final close could cancel the job and throw IOException
before the post-close check. The selected writer now rechecks the captured caller
after synchronous cleanup when a non-cancellation failure escapes. A cancelled
caller receives cancellation with the original failure (and its cleanup chain)
suppressed; an active caller keeps its original failure unchanged.

`DatabaseExporter` rethrows cancellation before its existing generic error handler.
Its committed suspend caller is compatible with the internal suspend writer. Existing
manual and scheduled callers already run this work on their injected IO dispatchers
inside the existing registered transfer child; no new owner, gate, dispatcher,
dependency or public API was introduced.

The selected snapshots preserve HEAD's V1 ZIP entries, manifest, database discovery,
file ordering and per-file lock lifetime. The selected patch does not add a backup
format, size cap, cross-store snapshot protocol, restore workflow or settings scope.
Those existing limitations remain explicit; this is a cancellation prerequisite.

## Selection and held working draft

Actual working `DatabaseBackupArchive.kt` and its test contain an earlier V2 backup
draft. They must **not** be staged wholesale. Select only the three `.after` snapshots
and `BACKUP_CANCELLATION_SELECTION.patch`, whose base is the supplied HEAD blobs.
The selected exporter hunk only adds a cancellation import/rethrow; the actual V2
exporter already had that behavior and was left untouched.

Cancellation was mirrored into the existing V2 draft to preserve future assembly.
Its multi-lock finally now attempts every acquired lock in reverse order even when
release fails, preserves the primary cancellation/copy/acquisition error, and adds
cleanup failures as suppressed errors. A successful copy with failed cleanup still
fails after attempting all releases. This two-path held delta is separately pinned
as `BACKUP_CANCELLATION_HELD_V2.patch` and working-before/working-after snapshots.
The held mirror performs the same B1 cancellation recheck after attempting all lock
releases. The pre-repair packet and exact selected/working snapshots are preserved
under `BACKUP_CANCELLATION_SELECTED/PRE_B1_REPAIR`.
It is excluded from staging because the V2 scope/caps/format remain held. Preflight
was left unchanged: it has no production restore caller and selecting it would bring
the pending draft contract. Full backup snapshot/restore remains incomplete.

## Authored scenarios and evidence limits

Eight new selected, unexecuted cases cover:

- Already-cancelled work refusing before any lock or output.
- Cancellation during lock acquisition, exactly one release, no sibling lock, and
  release failure suppressed onto cancellation.
- Cancellation inside copied data, a shortened first entry with no manifest or
  sibling, source files unchanged, and output/lock closed before completion.
- A destination failure preserved when its lock also fails to close.
- The actual `DatabaseExporter`/SQLite lock path propagating cancellation and
  permitting a subsequent SQLite write after the cancelled transfer ends.
- Cancellation plus IOException from actual exporter final output closure, with
  the IOException preserved and the SQLite lock released.
- Cancellation plus IOException during database-lock release, escaping only
  after physical output cleanup.
- An active caller retaining final-close IOException without conversion.

The two existing selected tests retain their original assertions and are adapted to
the internal suspend signature. The held mirror contains corresponding cases plus
two explicit multi-lock cases for release and acquisition failures. Those ten held
cases are not additional passing evidence and are not in the selected test snapshot.

Cancellation is cooperative around blocking operations; it does not interrupt a
blocked platform read/write/lock or skip physical close. Existing Clear must still
wait for actual child completion and preserve closing debt on timeout. Device,
cross-store coherence, restoration, battery and runtime behavior remain unverified.

Manifest SHA-256: `95be0844fb7186db3757b92fbc2fc13a667a8f9d3fd31d3b2ebec9ec9a2266dc`.
Selected patch SHA-256: `f7f1d06209e46b528b424b9e0f44c90a11be7d03da4b37b0c82333d7617fa8df`.
Held-only patch SHA-256: `5e26791e024cfe5154db569c889e7bd88778af0d5ed16cd3c0016338564d86f9`.
